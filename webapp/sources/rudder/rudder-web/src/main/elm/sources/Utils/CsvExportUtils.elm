module Utils.CsvExportUtils exposing (csvExportDropdownAllEntries, csvExportDropdownFilteredEntries)

import Html exposing (Html, button, div, i, li, span, text, ul)
import Html.Attributes exposing (attribute, class, disabled)
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
            , span [ class "me-2" ] [ text "Export CSV" ]
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


csvExportDropdownFilteredEntries : Bool -> msg -> msg -> Html msg
csvExportDropdownFilteredEntries isDisabled allEntriesAction filteredEntriesAction =
    div
        [ class "btn-group" ]
        [ button
            [ attribute "data-bs-toggle" "dropdown"
            , attribute "aria-expanded" "false"
            , class "btn btn-primary export-dropdown-toggle dropdown-toggle"
            , disabled isDisabled
            ]
            [ text "Export CSV"
            , span [ class "me-2 fa fa-file-download" ] []
            ]
        , ul
            [ class "dropdown-menu" ]
            [ li []
                [ button
                    [ class "dropdown-item"
                    , onClick allEntriesAction
                    ]
                    [ span [] [ text "All entries" ]
                    ]
                ]
            , li []
                [ button
                    [ class "dropdown-item"
                    , onClick filteredEntriesAction
                    ]
                    [ span [] [ text "Filtered entries only" ]
                    ]
                ]
            ]
        ]
