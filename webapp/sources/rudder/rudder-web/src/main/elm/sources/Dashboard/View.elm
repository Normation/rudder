module Dashboard.View exposing (..)

import Dashboard.DataTypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (class, href)
import Markdown
import Markdown.Config exposing (Options, defaultOptions)
-- import DateFormat.Relative
import List


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
               -- (activityDate, relativeActivityDate) = (a.date, DateFormat.Relative.relativeTimeWithOptions relativeTimeOptions now a.date)
               (activityDate, relativeActivityDate) = (a.date, a.date)
            in
                li[class "activity-item d-flex flex-column w-100"]
                    [ span[class "activity-date text-secondary"][text relativeActivityDate]
                    , span[class "activity-desc"] (displayDescription a.description)
                    ]
    in
        div []
            [ ul[class "activity-list d-flex flex-column mb-0"] ( List.map activityItem model.activities )
            ]
