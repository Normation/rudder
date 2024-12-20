port module About exposing (update)

import Browser
import Result
import Json.Decode exposing (Value)

import About.DataTypes exposing (..)
import About.Init exposing (init, subscriptions) -- fakeData
import About.View exposing (view)

--
-- Port for interacting with external JS
--

port errorNotification : String -> Cmd msg
port copy : String -> Cmd msg
port copyJson : Value -> Cmd msg

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        Ignore ->
            ( model, Cmd.none )
        GetAboutInfo res ->
            let
                ui = model.ui
                newModel = {model | ui = {ui | loading = False}}
            in
                case res of
                    Ok info ->
                        ({newModel | info = Just info}, Cmd.none)
                    Err _ ->
                        (newModel, (errorNotification "Error while fetching information"))
                        -- ({ newModel | info = Just fakeData}, (errorNotification "Error while fetching information"))
        Copy s ->
            ( model, copy s )

        CopyJson value ->
            (model, copyJson value)

        UpdateUI newUI ->
            ({model | ui = newUI}, Cmd.none)
