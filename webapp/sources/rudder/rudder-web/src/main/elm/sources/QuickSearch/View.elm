module QuickSearch.View exposing (..)

import Html.Attributes.Extra exposing (role)
import Html.Events exposing (onClick, onInput)
import List.Extra
import QuickSearch.Datatypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (..)
import String.Extra


kindName : Kind -> String
kindName k =
  case k of
    Node -> "node"
    Group -> "group"
    Parameter -> "parameter"
    Directive -> "directive"
    Technique -> "technique"
    Rule -> "rule"

viewItem: SearchResultItem -> Html Msg
viewItem item =
  li [ class "list-group-item" ] [
    a [href item.url, style "text-decoration" "none", onClick Close] [
      span [] [ text item.name ]
    , div [ class "description" ] [ text item.desc ]
    ]
  ]

viewResult : SearchResult -> Html Msg
viewResult result =
  let
    name = kindName result.header.type_ |> String.Extra.toSentenceCase
  in
  div [class "panel panel-default"] [
  div [] [
    div [ class "panel-heading",  role "tab",  id ("resultGroup"++ name)] [
      h4 [ class "panel-title" ] [
        a [ role "button", attribute "data-bs-toggle" "collapse", href ("#result" ++name), attribute "aria-expanded" "true" ] [
          span [ class "fa fa-chevron-right" ] []
        , text name
        , span [ class "description" ]
          [ text ((String.fromInt result.header.numbers) ++" found")
          , text (if result.header.numbers > 10 then ", only displaying the first 10. Please refine your query." else "")
          ]
        ]
      ]
    ]
  , div [ id ("result"++name) , class "panel-collapse collapse show", role "tabpanel", attribute "aria-labelledby" ("result"++name), attribute "aria-expanded" "true" ] [
      ul [ class "list-group" ]
        (List.map viewItem result.items)
      ]
  ]
  ]

filterButton : Model -> Filter -> Html Msg
filterButton model filter =
  let
     name = case filter of
       All -> "all"
       FilterKind k -> kindName k
     numbers =model.results |> case filter of
       All ->  List.map (.header >> .numbers) >> List.sum
       FilterKind k -> List.Extra.find (.header >> .type_ >> (==) k) >> Maybe.map (.header >> .numbers)  >> Maybe.withDefault 0
     check = model.selectedFilter |>
       case filter of
           All -> List.isEmpty
           FilterKind k -> List.member k
  in


    label [ for ("filter-" ++ name), class ("btn btn-default " ++ (if check then "active" else "")),  onClick (UpdateFilter filter)] [
      input [ type_ "checkbox", id ("filter-" ++ name),  checked check ] []
    , text (String.Extra.toSentenceCase name)
    , span [ class "badge pull-right" ] [
        case model.state of
          Searching -> span [class "loading fa fa-sync-alt" ] []
          _ -> span [] [
                 text (String.fromInt numbers)
               ]
      ]
    ]



view : Model -> Html Msg
view model =
  let
    open = if (model.state == Closed) then "" else "show"
    filteredResult =
      if (List.isEmpty model.selectedFilter) then model.results
      else model.results |> List.filter (\r -> List.member r.header.type_ model.selectedFilter  )
  in
  div [ class "quicksearch-form ms-2" ]
  [ div [ class "input-group"]
    [ label [ class "input-group-text", for "searchInput" ]
      [ span [ class "fa fa-search" ] []
      ]
    , input [ type_ "text", value model.search, placeholder "Search anything", onClick Open, onInput UpdateSearch , id "searchInput" , class "form-control input", autocomplete False] []
    , label [ class ("input-group-text " ++ if (String.isEmpty model.search) then "noRemove" else ""),  id "clear-search", for "searchInput", onClick (UpdateSearch "") ]
      [ span [ class "fa fa-times" ] []
      ]
    , a [ class "input-group-text", id "help-search", href "/rudder-doc/reference/current/usage/node_management.html#search-nodes",  target "_blank" ]
      [ span [ class "fa fa-question-circle" ] []
      ]
    ]
  , div [ id "background-fade-quicksearch", class open, onClick Close  ][]

  , ul [ class ("dropdown-menu dropdown-search " ++ open) ]
    [ li []
      [ div [ id "search-tab" ]
        [ div [ class "filter-search" ] [
                  div [ class "main-panel" ] [
                    div [ class "panel-heading heading-search p-2" ] [
                      div [ class "btn-toolbar", role "toolbar" ] [
                        div [ class "btn-group group-all" ] [
                          filterButton model All
                        ]
                      , div  [ class "btn-group group-filters ms-3" ]
                          (List.map (filterButton model) allFilters)
                      ]
                  ]
                  , div [ class "panel-body results-content p-2" ] [
                       div [ class "info-messages" ] [
                        div [ class "angucomplete-searching", hidden ((String.length model.search ) >= 3 )] [
                          span [ class "fa fa-exclamation-triangle" ] []
                        , text "The field size must be greater than 2 characters"
                        ]
                      , div [ hidden ((String.length model.search ) < 3 )] [
                          div [ class "angucomplete-searching", hidden (model.state /= Searching) ] [
                             text "Searching..."
                          ]
                        , div [ class "angucomplete-searching text-danger", hidden ((model.state /= Opened) || (List.length model.results > 0))  ] [
                            span [ class "fa fa-exclamation-triangle text-danger" ] []
                          , text "No results found"
                         ]
                         ]
                      , div [ class "panel-group", hidden (model.state == Searching || (List.isEmpty model.results )  ) ] [
                          div [ class "dropdown-search ", role "tablist" ]
                            (List.map viewResult filteredResult)
                        ]
                      ]
                    ]
                  ]
                ]
              ]
          ]
    ]
  ]