port module Plugins exposing (update)

-- fakeData

import Browser
import Bytes exposing (Bytes)
import Bytes.Decode
import Http.Detailed as Detailed
import Json.Decode exposing (..)
import List exposing (drop, head)
import Plugins.ApiCalls exposing (getPluginInfos)
import Plugins.DataTypes exposing (..)
import Plugins.Init exposing (init, subscriptions)
import Plugins.View exposing (view)
import String exposing (join, split)



--
-- Port for interacting with external JS
--


port successNotification : String -> Cmd msg


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
        CallApi apiCall ->
            ( model, apiCall model )

        ApiGetPlugins res ->
            case res of
                Ok ( _, { license, plugins } ) ->
                    ( { model | license = license, plugins = plugins }, Cmd.none )

                Err err ->
                    processApiErrorString "Error while fetching information" err model

        -- We want to update all plugins information every time the index is updated
        ApiPostPlugins (Ok UpdateIndex) ->
            ( model, Cmd.batch [ successNotification ("Plugin " ++ requestTypeText UpdateIndex ++ " successful"), getPluginInfos model ] )

        ApiPostPlugins res ->
            case res of
                Ok t ->
                    ( model, successNotification ("Plugin " ++ requestTypeText t ++ " successful") )

                Err err ->
                    processApiErrorBytes "Error while fetching information" err model

        SetModalState modalState ->
            ( { model | ui = (\ui -> { ui | modalState = modalState }) model.ui }, Cmd.none )

        Copy s ->
            ( model, copy s )

        CopyJson value ->
            ( model, copyJson value )

        CheckSelection s ->
            ( processSelect s model, Cmd.none )



-- UpdateUI newUI ->
--     ({model | ui = newUI}, Cmd.none)


processSpecificApiError : (a -> String) -> Detailed.Error a -> Model -> Maybe ( Model, Cmd Msg )
processSpecificApiError errDetails err model =
    case err of
        Detailed.BadStatus metadata body ->
            case metadata.statusCode of
                401 ->
                    Just
                        ( withSettingsError
                            ( "There are credentials error related to plugin management. Please refresh the page after you update your configuration.", decodeErrorContent (errDetails body) )
                            model
                        , Cmd.none
                        )

                _ ->
                    Nothing

        _ ->
            Nothing


processApiError : (a -> String) -> String -> Detailed.Error a -> Model -> ( Model, Cmd Msg )
processApiError errDetails msg err model =
    let
        message =
            case err of
                Detailed.BadUrl url ->
                    "The URL " ++ url ++ " was invalid"

                Detailed.Timeout ->
                    "Unable to reach the server, try again"

                Detailed.NetworkError ->
                    "Unable to reach the server, check your network connection"

                Detailed.BadStatus _ body ->
                    errDetails body

                Detailed.BadBody _ _ m ->
                    m
    in
    -- specific error override other ones which no longer need to be processed
    processSpecificApiError errDetails err model
        |> Maybe.withDefault ( model, errorNotification (msg ++ ", details: \n" ++ message) )


processApiErrorString : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiErrorString msg err model =
    processApiError decodeErrorContent msg err model


decodeErrorContent : String -> String
decodeErrorContent body =
    let
        ( title, errors ) =
            decodeErrorDetails body
    in
    title ++ "\n" ++ errors


processApiErrorBytes : String -> Detailed.Error Bytes -> Model -> ( Model, Cmd Msg )
processApiErrorBytes msg err model =
    let
        -- this 2048 chars should fit the notification box
        f =
            Bytes.Decode.decode (Bytes.Decode.string 2048)
                >> Maybe.withDefault "Unknown error"
    in
    processApiError f msg err model


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
                    "fail to process errorDetails : " ++ errorToString e

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
