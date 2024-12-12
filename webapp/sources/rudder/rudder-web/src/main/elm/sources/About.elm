port module About exposing (update)

import Browser
import Result
import Json.Decode exposing (Value)
import Http.Detailed as Detailed

import About.DataTypes exposing (..)
import About.Init exposing (init, subscriptions) -- fakeData
import About.View exposing (view)
import List exposing (drop, head)
import String exposing (join, split)
import Json.Decode exposing (..)

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
        ApiGetAboutInfo res ->
            let
                ui = model.ui
                newModel = {model | ui = {ui | loading = False}}
            in
                case res of
                    Ok (_, info) ->
                        ({newModel | info = Just info}, Cmd.none)
                    Err err ->
                      processApiError "Error while fetching information" err model
        Copy s ->
            ( model, copy s )

        CopyJson value ->
            (model, copyJson value)

        UpdateUI newUI ->
            ({model | ui = newUI}, Cmd.none)


processApiError : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiError msg err model =
    let
        modelUi =
            model.ui

        message =
            case err of
                Detailed.BadUrl url ->
                    "The URL " ++ url ++ " was invalid"

                Detailed.Timeout ->
                    "Unable to reach the server, try again"

                Detailed.NetworkError ->
                    "Unable to reach the server, check your network connection"

                Detailed.BadStatus metadata body ->
                    let
                        ( title, errors ) =
                            decodeErrorDetails body
                    in
                    title ++ "\n" ++ errors

                Detailed.BadBody metadata body m ->
                    m
    in
    ( model , errorNotification (msg ++ ", details: \n" ++ message) )


decodeErrorDetails : String -> ( String, String )
decodeErrorDetails json =
    let
        errorMsg =
            decodeString (Json.Decode.at [ "errorDetails" ] string) json

        msg =
            case errorMsg of
                Ok s ->
                    s

                Err e ->
                    "fail to process errorDetails"

        errors =
            split "<-" msg

        title =
            head errors
    in
    case title of
        Nothing ->
            ( "", "" )

        Just s ->
            ( s, join " \n " (drop 1 (List.map (\err -> "\t ‣ " ++ err) errors)) )
