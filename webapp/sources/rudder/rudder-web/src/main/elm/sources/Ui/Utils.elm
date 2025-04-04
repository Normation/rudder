module Ui.Utils exposing (..)

import Html exposing (Html, div, text, b, span)
import Html.Attributes exposing (class, attribute, style)

htmlTooltip : List (Html msg) -> Html msg
htmlTooltip tooltipContent =
    div [class "tool-tip", attribute "inert" "", attribute "role" "tooltip",  attribute "tip-position" "bottom" ] tooltipContent

badgePolicyMode : String -> String -> Html msg
badgePolicyMode globalPolicyMode policyMode =
    let
        mode =
            if policyMode == "default" then
                globalPolicyMode

            else
                policyMode

        defaultMsg =
            div[]
                [ text "This mode is the globally defined default. You can change it in the global "
                , b[][text "settings"]
                ]

        tooltipContent =
            case mode of
                "enforce" ->
                    [ div[]
                      [ text "This rule is in "
                      , b [style "color" "#9bc832"][text "enforce"]
                      , text " mode"
                      ]
                    , defaultMsg
                    ]

                "audit" ->
                    [ div[]
                      [ text "This rule is in "
                      , b [style "color" "#3694d1"][text "audit"]
                      , defaultMsg
                      ]
                    ]

                "mixed" ->
                    [ div[]
                      [ text "This rule is in "
                      , b [][text "mixed"]
                      , text " mode."
                      , div[]
                        [ text "This rule is applied on at least one node or directive that will "
                        , b [style "color" "#9bc832"][text "enforce"]
                        , text " one configuration, and at least one that will "
                        , b [style "color" "#3694d1"][text "audit"]
                        , text " them."
                        ]
                      ]
                    ]
                _ ->
                    [ text "Unknown policy mode"
                    ]
    in
        -- span [ class ("treeGroupName rudder-label label-sm label-" ++ mode), attribute "data-bs-toggle" "tooltip", attribute "data-bs-placement" "bottom", title (buildTooltipContent "Policy mode" msg) ][]
        span [ class ("treeGroupName rudder-label label-sm label-" ++ mode) ]
            [htmlTooltip tooltipContent]