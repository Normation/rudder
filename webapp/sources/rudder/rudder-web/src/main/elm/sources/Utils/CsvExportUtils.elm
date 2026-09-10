module Utils.CsvExportUtils exposing (csvExportDropdownAllEntries, csvExportDropdownFilteredEntries)

import Html exposing (Html, button, div, i, li, span, text, ul)
import Html.Attributes exposing (attribute, class)
import Html.Events exposing (onClick)


csvExportDropdownAllEntries : msg -> String -> Html msg
csvExportDropdownAllEntries onClickAction btnClass =
    div
        [ class ("btn-group " ++ btnClass) ]
        [ button
            [ attribute "data-bs-toggle" "dropdown"
            , attribute "aria-expanded" "false"
            , class "btn btn-primary export-dropdown-toggle dropdown-toggle"
            ]
            [ span [ class "me-2 fa fa-file-download" ] []
            , text "Export CSV"
            , i [ class "ms-2 fa fa-dl" ] []
            , i [ class "caret" ] []
            ]
        , ul
            [ class "dropdown-menu" ]
            [ li []
                [ button
                    [ class "dropdown-item"
                    , onClick onClickAction
                    ]
                    [ span [] [ text "All entries" ]
                    ]
                ]
            ]
        ]


csvExportDropdownFilteredEntries : msg -> Html msg
csvExportDropdownFilteredEntries onClickAction =
    div
        [ class "btn-group" ]
        [ button
            [ attribute "data-bs-toggle" "dropdown"
            , attribute "aria-expanded" "false"
            , class "btn btn-primary export-dropdown-toggle dropdown-toggle"
            ]
            [ text "Export CSV"
            , span [ class "ms-2 fa fa-file-download" ] []
            , i [ class "me-2 fa fa-dl" ] []
            , i [ class "caret" ] []
            ]
        , ul
            [ class "dropdown-menu" ]
            [ li []
                [ button
                    [ class "dropdown-item"
                    , onClick onClickAction
                    ]
                    [ span [] [ text "Filtered entries" ]
                    ]
                ]
            ]
        ]
