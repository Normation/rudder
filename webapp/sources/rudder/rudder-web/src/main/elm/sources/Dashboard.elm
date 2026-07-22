port module Dashboard exposing (..)

import Activity.ApiCalls exposing (..)
import Activity.DataTypes exposing (Activity, ActivityMsg(..), ContextPath(..))
import Activity.HtmlParserAdapter
import Browser
import DateFormat.Relative
import Dict
import Html exposing (Html, a, div, i, li, span, text, ul)
import Html.Attributes exposing (attribute, class, href)
import Html.Events exposing (onClick)
import Iso8601
import Result
import Task
import Time exposing (Month(..), Posix, Zone)
import TimeZone
import Utils.DateUtils exposing (posixToString, relativeTimeOptions)
import Utils.TooltipUtils exposing (buildTooltipContent)


port errorNotification : String -> Cmd msg


port copy : String -> Cmd msg


port initTooltips : String -> Cmd msg


type alias Model =
    { contextPath : String
    , activities : List Activity
    , currentTime : Posix
    , zone : Zone
    }


type Msg
    = CallApi (Model -> Cmd Msg)
    | Tick Posix
    | Copy String
    | ActivityMessage ActivityMsg



{--
This application manage the list of API Accounts and their token properties.
The general behavior is:
- there is a list of API Accounts with action buttons for editing, deleting, token generation, etc
- new one can be created
- action button create a modal window
- there is a main data type about current state of modal (none, new account, editing, etc)
--}


main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }


init : { contextPath : String, timeZone : String } -> ( Model, Cmd Msg )
init flags =
    let
        initTimeZone =
            Dict.get flags.timeZone TimeZone.zones
                |> Maybe.withDefault (\() -> Time.utc)

        initModel : Model
        initModel =
            { contextPath = flags.contextPath
            , activities = []
            , currentTime = Time.millisToPosix 0
            , zone = initTimeZone ()
            }

        bodyParameters =
            { search = Nothing
            , filterTypes = []
            }

        initActions : List (Cmd Msg)
        initActions =
            [ Cmd.map ActivityMessage (getActivities bodyParameters (ContextPath initModel.contextPath))
            , initTooltips ""
            , Task.perform Tick Time.now
            ]
    in
    ( initModel, Cmd.batch initActions )



--
-- update loop --
--


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        -- Do an API call
        CallApi call ->
            ( model, call model )

        Tick newTime ->
            ( { model | currentTime = newTime }, Cmd.none )

        Copy s ->
            ( model, copy s )

        ActivityMessage activityMsg ->
            case activityMsg of
                GetActivities res ->
                    case res of
                        Ok ( metadata, activities ) ->
                            ( { model | activities = activities }
                            , initTooltips ""
                            )

                        Err err ->
                            ( model, processActivityApiError "Getting activities list" err errorNotification )

                CopyToClipboard s ->
                    ( model, copy s )


view : Model -> Html Msg
view model =
    let
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
                , span [] [ Activity.HtmlParserAdapter.toHtml a.description ]
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


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.batch
        [ Time.every 1000 Tick -- Update of the current time every second
        ]
