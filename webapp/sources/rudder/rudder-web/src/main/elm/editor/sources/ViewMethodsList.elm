module ViewMethodsList exposing (..)

import DataTypes exposing (..)
import Dict
import Dict.Extra
import DnDList.Groups
import Json.Decode
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Markdown.Render
import Markdown.Option exposing (..)
import Maybe.Extra
import String.Extra

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
        filterMethods = List.filter (\(_,m) -> filterMethod filter m ) (List.indexedMap (Tuple.pair) (Dict.values model.methods))
        methodByCategories = Dict.Extra.groupBy (\(_,m) -> Maybe.withDefault m.id.value (List.head (String.split "_" m.id.value)) |> String.Extra.toTitleCase) (filterMethods)
        dscIcon = if filter.agent == Just Dsc then "dsc-icon-white.svg" else "dsc-icon.svg"
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
               div [ id "methods-list-container" ] (List.map (showMethodsCategories model) (Dict.toList methodByCategories) )
           , ul [ id "categories-list" ]
               ( h4 [ id "categories" ] [ text "Categories" ] ::
                 List.map (\(c,b) -> showCategory c b ) (Dict.toList (Dict.map( \k methods -> List.all (\(_,m) -> Maybe.Extra.isJust m.deprecated) methods) methodByCategories))
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

showMethodsCategories : Model -> (String, (List  (Int, Method))) -> Html Msg
showMethodsCategories model (category, methods) =
  let
    addIndex = case model.mode of
      TechniqueDetails t _ _ -> List.length t.calls
      _ -> 0
  in
    ul [ class "list-unstyled" ]
      (h5 [ id category ] [ text category ]
      :: (List.map (\(index,m)  -> showMethod model.methodsUI m (index+addIndex)) methods) )


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

showMethod: MethodListUI -> Method -> Int -> Html Msg
showMethod ui method index =
  let
    docOpen = List.member method.id ui.docsOpen
    attributes = class ("method " ++ (if docOpen then "doc-opened" else ""))::  id method.id.value :: dndSystem.dragEvents index method.id.value
  in
    li []
      ( div  attributes   --ng-class="{'used':isUsed(method)
        (div [ class "cursorMove" ] [
          b [] [ text ":::" ]
        ] ::
        div [ class "method-name col",  onClick (GenerateId (\s -> AddMethod method (CallId s))) ]
          ( text method.name ::
            ( case method.deprecated of
              Nothing -> text ""
              Just _ ->
                span [ class "cursor-help" ] [
                  i [ class "fa fa-info-circle tooltip-icon deprecated-icon popover-bs"
                    , attribute "data-toggle" "popover", attribute "data-trigger" "hover"
                    , attribute "data-container" "body", attribute "data-placement" "top"
                    , attribute "data-title" method.name, attribute "data-content" (getTooltipContent method)
                    , attribute "data-html" "true"
                  ] []
                ]
            ) ::
            if (List.member Dsc method.agentSupport) then  [ img [ src "../../images/dsc-icon.svg",  class "dsc-icon" ] [] ] else []
          )
         ::
        case method.documentation of
         Just _ ->
           [ div [ class "show-doc", onClick (ToggleDoc method.id)] [
             i [ class "fa fa-book" ] [] ] ]
         Nothing -> []
        )
       ::
      case method.documentation of
        Just doc ->
          if docOpen then
            [ div [ class "markdown" ] [ Html.map (\_ -> Ignore) (Markdown.Render.toHtml Standard doc) ] ]
          else
            []
        Nothing -> []
      )

