module Rules.ViewRepairedReports exposing (..)

import Dict exposing (Dict)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Rules.DataTypes exposing (..)
import Time exposing (Zone)
import Utils.DateUtils exposing (offsetString, posixToDateString, posixToString, posixToTimeString)


type alias TableColumn =
    { id : String
    , toRow : RepairedReport -> String
    }


columns : Model -> List TableColumn
columns model =
    TableColumn "Execution date" (.executionDate >> posixToString model.zone)
        :: TableColumn "Directive" (\r -> Maybe.withDefault r.directiveId.value (Maybe.map .displayName (Dict.get r.directiveId.value model.directives)))
        :: TableColumn "Node" (\r -> Maybe.withDefault r.nodeId.value (Maybe.map .hostname (Dict.get r.nodeId.value model.nodes)))
        :: TableColumn "Component" .component
        :: TableColumn "Value" .value
        :: TableColumn "Message" .message
        :: []


options : Zone -> List Changes -> List (Html Msg)
options zone changes =
    List.reverse <| List.indexedMap (\id c -> option [ value (String.fromInt id) ] [ text (showChanges zone c) ]) <| changes


technicalLogsTab : Model -> RuleDetails -> Html Msg
technicalLogsTab model details =
    let
        col =
            columns model

        tableTitle =
            case Dict.get details.rule.id.value model.changes of
                Just changes ->
                    div [ class "table-title" ]
                        [ div [ class "input-group w-auto" ]
                            [ label [ class "input-group-text", for "dateRange" ] [ i [ class "fa fa-calendar" ] [] ]
                            , select [ id "dateRange", onInput (\s -> GetRepairedReport details.rule.id (Maybe.withDefault 0 (String.toInt s))), class "form-select" ] (options model.zone changes)
                            ]
                        ]

                Nothing ->
                    text ""
    in
    div [ class "tab-table-content" ]
        [ tableTitle
        , div [ class "table-header" ]
            [ input
                [ type_ "text"
                , placeholder "Filter"
                , class "input-sm form-control"
                , value model.ui.groupFilters.tableFilters.filter
                , onInput
                    (\s ->
                        let
                            groupFilters =
                                model.ui.groupFilters

                            tableFilters =
                                groupFilters.tableFilters
                        in
                        UpdateGroupFilters { groupFilters | tableFilters = { tableFilters | filter = s } }
                    )
                ]
                []
            , button [ class "btn btn-default", onClick (RefreshReportsTable details.rule.id) ] [ i [ class "fa fa-refresh" ] [] ]
            ]
        , div [ class "table-container" ]
            [ table [ class "dataTable compliance-table" ]
                [ thead []
                    [ tr [ class "head" ] (List.map (\row -> th [] [ text row.id ]) col)
                    ]
                , tbody []
                    (if List.length details.reports <= 0 then
                        [ tr []
                            [ td [ class "empty", colspan 6 ] [ i [ class "fa fa-exclamation-triangle" ] [], text "No data available." ] ]
                        ]

                     else
                        List.map
                            (\r ->
                                tr [] (List.map (\c -> td [] [ text (c.toRow r) ]) col)
                            )
                            details.reports
                    )
                ]
            ]
        ]


showChanges : Zone -> Changes -> String
showChanges zone c =
    let
        formatChangeDate d =
            posixToDateString zone d ++ " " ++ posixToTimeString zone d ++ offsetString zone d

        formatChangeCount n =
            String.fromFloat n
                ++ (if n > 1 then
                        " changes"

                    else
                        " change"
                   )
    in
    ("Between " ++ formatChangeDate c.start ++ " and " ++ formatChangeDate c.end ++ " (" ++ formatChangeCount c.changes) ++ ")"
