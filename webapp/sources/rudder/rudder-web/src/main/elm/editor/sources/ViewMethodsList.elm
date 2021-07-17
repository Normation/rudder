module ViewMethodsList exposing (..)

import DataTypes exposing (..)
import Dict
import Dict.Extra
import Json.Decode
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Markdown.Render
import Markdown.Option exposing (..)
import Maybe.Extra
import String.Extra
import Dom exposing (..)
import Dom.DragDrop as DragDrop

--
-- Display the method list/category UI
--

getTooltipContent: Method -> String
getTooltipContent method =
  let
    description = case method.description of
        "" -> ""
        d  -> "<div class='description'>"++d++"</div>"
    deprecation = case method.deprecated of
      Nothing -> ""
      Just  m -> "<div class='deprecated-info'><div>This generic method is <b>deprecated</b>.</div> <div class='deprecated-message'><b>↳</b>"++m++"</div></div>"
  in
    "<div>" ++ description ++ deprecation ++ "</div>"

methodsList: Model -> Html Msg
methodsList model =
  case model.mode of
    Introduction -> text""
    _ ->
      let
        filter = model.methodsUI.filter
        filterMethods = List.filter ( filterMethod filter )  (Dict.values model.methods)
        methodByCategories = Dict.Extra.groupBy (\m -> Maybe.withDefault m.id.value (List.head (String.split "_" m.id.value)) |> String.Extra.toTitleCase) (filterMethods)
        dscIcon = if filter.agent == Just Dsc then "dsc-icon-white.svg" else "dsc-icon.svg"

        block = element "li"
              |> DragDrop.makeDraggable model.dnd NewBlock dragDropMessages
              |> appendChild
                 ( element "div"
                   |> addClass "method"
                   |> appendChildList
                      [ element "div"
                        |> addClass "cursorMove"
                        |> appendChild
                           ( element "i"
                             |> addClass "fas fa-grip-horizontal"
                           )
                      , element "div"
                        |> addAttributeList [ class "method-name col",  onClick (GenerateId (\s -> AddBlock (CallId s))) ]
                        |> appendText "New block"
                      ]
                 )
        blockHeader = element "h5"
                        |> addAttribute ( id "block" )
                        |> appendText "Block"
        methodsElem = element "div"
                      |> addAttribute (id "methods-list-container")
                      |> appendChild
                         ( element "ul"
                           |> addClass "list-unstyled"
                           |> appendChildList
                              [ blockHeader, block ]
                         )
                      |> appendChildList
                         (List.map (showMethodsCategories model) (Dict.toList methodByCategories) )
      in
        div [ class "template-sidebar sidebar-right col-methods", onClick OpenMethods ] [
         div [ class "sidebar-header" ] [
           div  [ class "header-title" ] [
             h1 [] [ text "Generic Methods" ]
           , div [ class "header-buttons" ] [
               button [ class "btn btn-sm btn-default", stopPropagationOn "click" (Json.Decode.succeed  (OpenTechniques,True))  ] [ text "Close"]
             ]
           ]

         , div [ class "header-filter" ] [
             div [ class "input-group" ] [
               input [ class "form-control",  type_ "text",  placeholder "Filter", value filter.name, onInput (\s -> UpdateMethodFilter { filter | name = s  }) ] []
             , div [ class "input-group-btn" ] [
                 button [ class "btn btn-outline-secondary btn-toggle-filters" , onClick ToggleFilter] [ --ng-click="ui.showMethodsFilter=!ui.showMethodsFilter">
                   i [ class "ion ion-android-options"] []
                 ]
               ]
             ]
           ]
         ]
       , if (filter.state == FilterOpened) then
           div [ class "filters-container" ] [-- ng-class="{'hidden':!ui.showMethodsFilter}">
             label [ class "label-btn-group align-self-center" ] [
               text "Agent type:"
             ]
           , div [ class "btn-group" ] [
               button [ class ("btn btn-default" ++ (if filter.agent == Nothing then " active" else "")), onClick (UpdateMethodFilter {filter | agent = Nothing })  ] [text "All"]
             , button [ class ("btn btn-default" ++ (if filter.agent == Just Cfengine then " active" else "")), onClick (UpdateMethodFilter {filter | agent = Just Cfengine }) ] [text "Classic"]
             , button [ class ("btn btn-default" ++ (if filter.agent == Just Dsc then " active" else "")), onClick (UpdateMethodFilter {filter | agent = Just Dsc }) ] [
                 text "DSC "
               , img [ src ("../../images/" ++ dscIcon),  class "dsc-icon" ] []
               ]
             ]
           , div [ class "input-group" ] [
               label [ for "showDeprecated", class "input-group-addon" ] [
                 input [ id "showDeprecated",  type_ "checkbox", checked filter.showDeprecated, onCheck (\b -> UpdateMethodFilter { filter | showDeprecated = b}) ]  []
               ]
             , label [ for "showDeprecated",  class "form-control label-checkbox" ][
                 text "Show deprecated generic methods"
               , i [ class "fa fa-info-circle deprecated-icon" ] []
               ]
             ]
           ]
         else
           text ""

       , div [ class "sidebar-body" ] [
           div [ class "generic-methods-container" ] [
             if List.isEmpty (Dict.toList model.methods) then
               div [ class "empty" ] [ text "No method matches the filters." ]
             else
               (render methodsElem)
           , ul [ id "categories-list" ]


               ( h4 [ id "categories" ] [ text "Categories" ] ::
                 li [ class ("active") ] [
                   a [  onClick (ScrollCategory "block") ] [ text "Block" ]
                 ] ::
                 List.map (\(c,b) -> showCategory c b ) (Dict.toList (Dict.map( \_ methods -> List.all (\m -> Maybe.Extra.isJust m.deprecated) methods) methodByCategories))
               )

           ]
         ]
       ]

filterMethod: MethodFilter -> Method -> Bool
filterMethod filter method =
  (String.contains filter.name method.name) &&
    ( case filter.agent of
      Nothing -> True
      Just ag -> List.member ag method.agentSupport
    ) && (filter.showDeprecated ||
           case method.deprecated of
             Nothing -> True
             _ -> False
         )

showMethodsCategories : Model -> (String, List  Method) -> Element Msg
showMethodsCategories model (category, methods) =
  let
    addIndex = case model.mode of
      TechniqueDetails t _ _ -> List.length t.elems
      _ -> 0
    header = element "h5"
             |> addAttribute ( id category )
             |> appendText category
    methodsElem = List.map (\m  -> showMethod model.methodsUI m model.dnd) methods
  in
    element "ul"
    |> addClass "list-unstyled"
    |> appendChildList
       ( header :: methodsElem)



showCategory: String -> Bool -> Html Msg
showCategory  category allDeprecated =
  li [ class ("active" ++ (if allDeprecated then " deprecatedCategory" else "") ) ] [
    a [  onClick (ScrollCategory category) ]
      ( text category ::
        if (allDeprecated) then
          [ span [ class "cursor-help popover-bs", attribute "data-toggle" "popover"
                 , attribute "data-trigger" "hover", attribute "data-container" "body"
                 , attribute "data-placement" "bottom", attribute "data-title" category
                 , attribute "data-content" "<div>All generic methods in this category are <b>deprecated</b>.</div>"
                 , attribute "data-html" "true"
                 ] [ i [ class "glyphicon glyphicon-info-sign deprecated-icon" ] []]
          ]
        else []
      )
  ]

showMethod: MethodListUI -> Method -> ( DragDrop.State DragElement DropElement) -> Element Msg
showMethod ui method dnd =
  let
    docOpen = List.member method.id ui.docsOpen
    attributes = class ("method " ++ (if docOpen then "doc-opened" else ""))::  id method.id.value :: []
  in
    element "li"
    |> DragDrop.makeDraggable dnd (NewMethod method) dragDropMessages
    |> appendChild
       ( element "div"
       |> addAttributeList  attributes   --ng-class="{'used':isUsed(method)
       |> appendChildList
          [ element "div"
            |> addClass "cursorMove"
            |> appendChild
                           ( element "i"
                             |> addClass "fas fa-grip-horizontal"
                           )
          , element "div"
            |> addAttributeList [ class "method-name col",  onClick (GenerateId (\s -> AddMethod method (CallId s))) ]
            |> appendText method.name
            |> appendChildConditional
               ( element "span"
                 |> addClass "cursor-help"
                 |> appendChild
                    ( element "i"
                      |> addAttributeList
                         [ class "fa fa-info-circle tooltip-icon deprecated-icon popover-bs"
                         , attribute "data-toggle" "popover", attribute "data-trigger" "hover"
                         , attribute "data-container" "body", attribute "data-placement" "top"
                         , attribute "data-title" method.name, attribute "data-content" (getTooltipContent method)
                         , attribute "data-html" "true"
                         ]
                    )
               ) (Maybe.Extra.isJust  method.deprecated )
            |> appendChildConditional
               ( element "img"
                 |> addAttributeList [ src "../../images/dsc-icon.svg",  class "dsc-icon" ]
               ) (List.member Dsc method.agentSupport)
          ]
       |> appendChildConditional
            ( element "div"
              |> addAttributeList [ class "show-doc", onClick (ToggleDoc method.id)]
              |> appendChild
                   (element "i" |> addClass "fa fa-book" )
            ) (Maybe.Extra.isJust method.documentation)
       )
    |> if docOpen then
         ( appendChild
           ( element "div"
             |> addClass "markdown"
             |> appendNode ( Html.map (\_ -> Ignore) (Markdown.Render.toHtml Standard (Maybe.withDefault "" method.documentation)))
           )
         )
       else
         identity