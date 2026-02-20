port module Dashboard.Init exposing (..)


import Dict
import Time exposing (Month(..), Posix, Zone)
import TimeZone
import Task
import Dashboard.ApiCalls exposing (getActivities)
import Dashboard.DataTypes exposing (..)


-- PORTS / SUBSCRIPTIONS
port errorNotification : String -> Cmd msg
port copy : String -> Cmd msg
port initTooltips : String -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.batch
        [ Time.every 1000 Tick -- Update of the current time every second
        ]

init : { contextPath : String, timeZone: String } -> ( Model, Cmd Msg )
init flags =
    let
        initTimeZone =
            Dict.get flags.timeZone TimeZone.zones
                |> Maybe.withDefault (\() -> Time.utc)

        initModel =
            Model flags.contextPath [] (Time.millisToPosix 0) (initTimeZone ())

        initActions =
            [ getActivities initModel
            , initTooltips ""
            , Task.perform Tick Time.now
            ]
    in
    ( initModel, Cmd.batch initActions )