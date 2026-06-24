module Utils.CsvExportUtils exposing (exportToCsvButton)

import Html exposing (Html, button, i, span, text)
import Html.Attributes exposing (attribute, class, title)
import Html.Events exposing (onClick)
import Utils.TooltipUtils exposing (buildTooltipContent)


exportToCsvButton : msg -> Html msg
exportToCsvButton onClickAction =
    button
        [ class "btn btn-sm btn-primary btn-export me-2"
        , attribute "data-bs-toggle" "tooltip"
        , title (buildTooltipContent "Export to CSV" "User-defined filters are not taken into account when exporting this table to CSV (the full compliance table will be exported).")
        , onClick onClickAction
        ]
        [ span []
            [ text "Export "
            , i [ class "fa fa-download" ] []
            ]
        ]
