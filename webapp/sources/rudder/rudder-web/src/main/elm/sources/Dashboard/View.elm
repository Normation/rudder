module Dashboard.View exposing (..)

import Dashboard.DataTypes exposing (..)
import DateFormat.Relative
import Html exposing (..)
import Html.Attributes exposing (attribute, class, href)
import Html.Events exposing (onClick)
import Iso8601
import List
import Markdown
import Markdown.Config exposing (Options, defaultOptions)
import Utils.DateUtils exposing (posixToString, relativeTimeOptions)
import Utils.TooltipUtils exposing (buildTooltipContent)


view : Model -> Html Msg
view model =
    let
        displayDescription : String -> List (Html Msg)
        displayDescription str =
            let
                sanitizeOptions : Markdown.Config.SanitizeOptions
                sanitizeOptions =
                    { allowedHtmlElements =
                        [ "a" ]
                    , allowedHtmlAttributes =
                        [ "href" ]
                    }

                customOptions : Options
                customOptions =
                    { defaultOptions
                        | rawHtml = Markdown.Config.Sanitize sanitizeOptions
                    }
            in
            Markdown.toHtml (Just customOptions) str

        activityItem : Activity -> Html Msg
        activityItem a =
            let
                ( activityDate, relativeActivityDate ) =
                    ( a.date, DateFormat.Relative.relativeTimeWithOptions relativeTimeOptions model.currentTime a.date )

                tooltipTitle =
                    "<div class='d-flex align-items-baseline'><i class='fa fa-user me-2'></i>" ++ a.actor ++ "</div>"
            in
            li [ class "activity-item d-flex flex-column w-100" ]
                [ div []
                    [ span
                        [ class "relative-date"
                        , attribute "data-bs-toggle" "tooltip"
                        , attribute "data-bs-placement" "top"
                        , attribute "title" (buildTooltipContent tooltipTitle (posixToString model.zone activityDate))
                        , onClick (Copy (Iso8601.fromTime activityDate))
                        ]
                        [ text relativeActivityDate ]
                    , span [ class "activity-actor text-secondary" ]
                        [ text (", by " ++ a.actor) ]
                    ]
                , span [ class "activity-desc" ] (displayDescription a.description)
                ]
    in
    div []
        [ ul [ class "activity-list d-flex flex-column mb-0" ]
            (if List.isEmpty model.activities then
                [ li [ class "activity-item d-flex no-activity text-info align-items-baseline" ]
                    [ i [ class "fa fa-info-circle fs-5 me-2" ] []
                    , text "There have been no activities yet."
                    ]
                ]

             else
                List.append
                    (model.activities
                        |> List.map activityItem
                    )
                    [ li [ class "activity-item d-flex flex-column w-100" ]
                        [ a [ href (model.contextPath ++ "/secure/configurationManager/changeLogs") ]
                            [ text "See all change logs"
                            , i [ class "fas fa-long-arrow-alt-up ms-2" ] []
                            ]
                        ]
                    ]
            )
        ]
