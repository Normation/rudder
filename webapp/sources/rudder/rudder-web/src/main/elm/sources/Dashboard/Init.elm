port module Dashboard.Init exposing (..)

import Dashboard.ApiCalls exposing (getActivities)
import Dashboard.DataTypes exposing (..)

-- PORTS / SUBSCRIPTIONS
port errorNotification : String -> Cmd msg


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none


init : { contextPath : String } -> ( Model, Cmd Msg )
init flags =
    let
        initModel =
            Model flags.contextPath [] (UI False)

        initActions =
            [ getActivities initModel
            ]
    in
    ( initModel, Cmd.batch initActions )
