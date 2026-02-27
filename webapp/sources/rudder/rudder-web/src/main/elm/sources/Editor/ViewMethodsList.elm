module Editor.ViewMethodsList exposing (..)

import Dict
import Dict.Extra
import Json.Decode
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Markdown
import Maybe.Extra
import String.Extra
import Dom exposing (..)
import Dom.DragDrop as DragDrop

import Utils.TooltipUtils exposing (buildTooltipContent)


import Editor.DataTypes exposing (..)
import Editor.MethodElemUtils exposing (defaultMethodUiInfo)


--
-- Display the method list/category UI
--

getTooltipContent: Method -> String
getTooltipContent method =
  let
    description = case method.description of
        "" -> ""
        d  -> "<div class='description'>"++ d ++"</div>"
    deprecation = case method.deprecated of
      Nothing -> ""
      Just  m -> "<div class='deprecated-info'><div>This generic method is <b>deprecated</b>.</div> <div class='deprecated-message'><b>↳</b>"++ m ++"</div></div>"
  in
    buildTooltipContent ("Method '<b>"++ method.name ++"</b>'") (description ++ deprecation)

methodsList: Model -> Html Msg
methodsList model =
  case model.mode of
    Introduction -> text""
    _ ->
      let
        methodsUI = model.methodsUI
        filter = methodsUI.filter
        filterMethods = List.filter ( filterMethod methodsUI )  (Dict.values model.methods)
        methodByCategories = Dict.Extra.groupBy (\m -> Maybe.withDefault m.id.value (List.head (String.split "_" m.id.value)) |> String.Extra.toTitleCase) (filterMethods)

        block = element "li"
              |> appendChild
                 ( element "div"
                   |> DragDrop.makeDraggable model.dnd NewBlock dragDropMessages
                   |> addClass "method method-elmt"
                   |> appendChildList
                      [ element "div"
                        |> addClass "cursorMove"
                        |> appendChild
                           ( element "i"
                             |> addClass "fas fa-cubes"
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
             h1 [] [ text "Methods" ]
           , div [ class "header-buttons" ] [
               button [ class "btn btn-sm btn-default", stopPropagationOn "click" (Json.Decode.succeed  (OpenTechniques,True))  ] [ text "Close"]
             ]
           ]

         , div [ class "header-filter" ] [
             div [ class "input-group" ] [
               input [ class "form-control",  type_ "text",  placeholder "Filter", value filter.name, onInput (\s -> UpdateMethodFilter { filter | name = s  }) ] []
             , button [ class "btn btn-outline-secondary btn-toggle-filters" , onClick ToggleFilter] [ --ng-click="ui.showMethodsFilter=!ui.showMethodsFilter">
                 i [ class "ion ion-android-options"] []
               ]
             ]
           ]
         , if (filter.state == FilterOpened) then
           div [ class "filters-container" ]
           [ div[class "form-group"]
             [ label [ class "label-btn-group align-self-center" ]
               [ text "Agent type: "
               ]
             , div [ class "btn-group space-left" ] [
                 button [ class ("btn btn-default" ++ (if filter.agent == Nothing then " active" else "")), onClick (UpdateMethodFilter {filter | agent = Nothing })  ] [text "All"]
               , button [ class ("btn btn-default" ++ (if filter.agent == Just Cfengine then " active" else "")), onClick (UpdateMethodFilter {filter | agent = Just Cfengine }) ] [text "Linux"]
               , button [ class ("btn btn-default" ++ (if filter.agent == Just Dsc then " active" else "")), onClick (UpdateMethodFilter {filter | agent = Just Dsc }) ] [
                   text "Windows "
                 , span [ class "dsc-icon" ] []
                 ]
               ]
             ]
           , div [ class "input-group" ] [
               label [ for "showDeprecated", class "input-group-text" ] [
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
         ]

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

filterMethod: MethodListUI -> Method -> Bool
filterMethod methodsUI method =
  let
    filter = methodsUI.filter
    nameCheck = String.contains (String.toUpper filter.name) (String.toUpper method.name)
    agentCheck = case filter.agent of
      Nothing -> True
      Just ag -> List.member ag method.agentSupport
    deprecatedCheck = filter.showDeprecated ||
      case method.deprecated of
        Nothing -> True
        _ -> False
    docOpenedCheck = List.member method.id methodsUI.docsOpen
  in
    ( nameCheck && agentCheck && deprecatedCheck
    || docOpenedCheck
    )

showMethodsCategories : Model -> (String, List  Method) -> Element Msg
showMethodsCategories model (category, methods) =
  let
    addIndex = case model.mode of
      TechniqueDetails t _ _ _ -> List.length t.elems
      _ -> 0
    header = element "h5"
             |> addAttribute ( id category )
             |> appendText category
    methodsElem = List.map (\m  -> showMethod model.methodsUI m model.mode model.dnd) methods
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
          [ span
            [ class "cursor-help"
            , attribute "data-bs-toggle" "tooltip"
            , attribute "data-bs-placement" "top"
            , attribute "data-bs-html" "true"
            , title "<div>All generic methods in this category are <b>deprecated</b>.</div>"
            ] [ i [ class "fa fa-info-circle deprecated-icon" ] []]
          ]
        else []
      )
  ]

showMethod: MethodListUI -> Method -> Mode -> ( DragDrop.State DragElement DropElement) -> Element Msg
showMethod ui method mode dnd =
  let
    docOpen = List.member method.id ui.docsOpen
    attributes = class ("method method-elmt " ++ (if docOpen then "doc-opened" else ""))::  id method.id.value :: []
    methodUi =
      case mode of
        TechniqueDetails _ _ techUiInfo _ ->
          Maybe.withDefault (defaultMethodUiInfo Nothing) (Dict.get method.id.value techUiInfo.callsUI)
        _  -> (defaultMethodUiInfo Nothing)
  in
    element "li"
    |> appendChild
       ( element "div"
       |> DragDrop.makeDraggable dnd (NewMethod method) dragDropMessages
       |> addAttributeList attributes
       |> appendChildList
          [ element "div"
            |> addClass "cursorMove"
            |> addAction ("click",  UIMethodAction method.id {methodUi | mode = if(methodUi.mode == Opened) then Closed else Opened})
            |> appendChild
                           ( element "i"
                             |> addClass "fas fa-cog"
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
                         [ class "fa fa-info-circle tooltip-icon deprecated-icon"
                         , attribute "data-bs-toggle" "tooltip"
                         , attribute "data-bs-placement" "top"
                         , attribute "data-bs-html" "true"
                         , title ((getTooltipContent method))
                         ]
                    )
               ) (Maybe.Extra.isJust  method.deprecated )
            |> appendChildConditional
               ( element "span"
                 |> addAttributeList [ class "dsc-icon" ]
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
             |> appendNodeList ( Markdown.toHtml Nothing (Maybe.withDefault "" method.documentation))
           )
         )
       else
         identity
