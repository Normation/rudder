port module Plugins exposing (update)

import Browser
import Browser.Navigation
import Http
import Http.Detailed as Detailed
import Json.Decode exposing (..)
import List exposing (drop, head)
import Plugins.ApiCalls exposing (getPluginInfos, requestTypeAction)
import Plugins.DataTypes exposing (..)
import Plugins.Init exposing (init, subscriptions)
import Plugins.View exposing (view)
import Process
import String exposing (join, split)
import Task



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

        RequestApi t ->
            ( withLoading True model, requestTypeAction t model )

        ApiGetPlugins res ->
            case res of
                Ok ( _, { license, plugins } ) ->
                    ( withLoading False { model | license = license, plugins = plugins }, Cmd.none )

                Err err ->
                    processApiError "Error while getting the list of plugins." err model

        -- We want to update all plugins information every time the index is updated
        ApiPostPlugins UpdateIndex (Ok _) ->
            ( withLoading False model, Cmd.batch [ successNotification "Plugins list successfully updated.", getPluginInfos model ] )

        ApiPostPlugins UpdateIndex (Err err) ->
            processApiError "Error while trying to get the updated the list of plugins." err model

        ApiPostPlugins t res ->
            case res of
                Ok _ ->
                    ( withLoading False model, successNotification ("Plugin " ++ requestTypeText t ++ " successful.") )

                Err err ->
                    processApiError ("Error while trying to " ++ requestTypeText t) err model

        SetModalState modalState ->
            ( { model | ui = (\ui -> { ui | modalState = modalState }) model.ui }, Cmd.none )

        ReloadPage ->
            ( model, Browser.Navigation.reload )

        Copy s ->
            ( model, copy s )

        CopyJson value ->
            ( model, copyJson value )

        CheckSelection s ->
            ( processSelect s model, Cmd.none )


processSpecificApiError : String -> Detailed.Error String -> Model -> Maybe ( Model, Cmd Msg )
processSpecificApiError msg err model =
    case err of
        Detailed.BadStatus metadata body ->
            case metadata.statusCode of
                401 ->
                    Just
                        ( withSettingsError
                            ( "There are credentials errors related to plugin management. Please refresh the list of plugins after you update your configuration credentials.", decodeErrorContent body )
                            model
                        , errorNotification msg
                        )

                403 ->
                    Just
                        ( withSettingsError
                            ( "There are configuration errors related to plugin management. Please refresh the list of plugins after you update your configuration URL or check your access.", decodeErrorContent body )
                            model
                        , errorNotification msg
                        )

                502 ->
                    -- Bad Gateway may indicate that the server has probably restarted meanwhile to apply changes on plugins
                    Just
                        ( model, Cmd.batch [ waitAndReload 5000, successNotification "This page will reload automatically in a few seconds" ] )

                503 ->
                    -- Service Unavailable may indicate that the server has probably restarted meanwhile to apply changes on plugins
                    Just
                        ( model, Cmd.batch [ waitAndReload 5000, successNotification "This page will reload automatically in a few seconds" ] )

                _ ->
                    Nothing

        _ ->
            Nothing


processApiError : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiError msg err model =
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
                    decodeErrorContent body

                Detailed.BadBody _ _ m ->
                    m
    in
    -- specific error override other ones which no longer need to be processed
    processSpecificApiError msg err model
        |> Maybe.withDefault ( model, errorNotification (msg ++ ", details: \n" ++ message) )


decodeErrorContent : String -> String
decodeErrorContent body =
    let
        ( title, errors ) =
            decodeErrorDetails body
    in
    title ++ "\n" ++ errors


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


waitAndReload : Float -> Cmd Msg
waitAndReload millis =
    Process.sleep millis
        |> Task.perform (\_ -> ReloadPage)
