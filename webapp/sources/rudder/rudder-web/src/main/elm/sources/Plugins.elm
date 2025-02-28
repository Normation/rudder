port module Plugins exposing (update)

import Browser
import Browser.Navigation
import Http.Detailed as Detailed
import Json.Decode exposing (..)
import List exposing (drop, head)
import Plugins.ApiCalls exposing (getPluginInfos, requestTypeAction, updateIndex)
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
        Now time ->
            ( { model | now = time }, Cmd.none )

        CallApi apiCall ->
            ( model, apiCall model )

        RequestApi t plugins ->
            ( setLoading True model, requestTypeAction t model plugins )

        ApiGetPlugins res ->
            case res of
                Ok ( _, { license, plugins } ) ->
                    ( model |> setLoading False |> setLicense license |> processPlugins plugins, Cmd.none )

                Err err ->
                    processApiError "Error while getting the list of plugins." err model

        -- We want to update all plugins information every time the index is updated
        ApiPostPlugins UpdateIndex (Ok _) ->
            ( setLoading False model, Cmd.batch [ successNotification "Plugins list successfully updated.", getPluginInfos model ] )

        ApiPostPlugins UpdateIndex (Err err) ->
            processApiError "Error while trying to get the updated the list of plugins." err model

        ApiPostPlugins t res ->
            case res of
                Ok _ ->
                    ( setLoading False model, successNotification ("Plugin " ++ requestTypeText t ++ " successful.") )

                Err err ->
                    processApiError ("Error while trying to " ++ requestTypeText t ++ " plugins") err model

        SetModalState modalState ->
            ( model |> updatePluginsView (setModalState modalState), Cmd.none )

        ReloadPage ->
            ( model, Browser.Navigation.reload )

        Copy s ->
            ( model, copy s )

        CopyJson value ->
            ( model, copyJson value )

        CheckSelection s ->
            ( processSelect s model, Cmd.none )

        UpdateFilters filters ->
            ( processFilters filters model, Cmd.none )


processSpecificApiError : String -> Detailed.Error String -> Model -> Maybe ( Model, Cmd Msg )
processSpecificApiError msg err model =
    case err of
        Detailed.BadStatus metadata _ ->
            case metadata.statusCode of
                401 ->
                    Just
                        ( setSettingsError CredentialsError model, errorNotification msg )

                403 ->
                    Just
                        ( setSettingsError ConfigurationError model, errorNotification msg )

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

        newModel =
            processActionError ( msg, message ) model
    in
    -- specific error override other ones which no longer need to be processed
    processSpecificApiError msg err model
        |> Maybe.withDefault ( newModel, errorNotification (msg ++ ", details: \n" ++ message) )


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
