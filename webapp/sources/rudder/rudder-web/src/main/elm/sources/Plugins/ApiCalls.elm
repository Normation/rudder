module Plugins.ApiCalls exposing (..)

import Http exposing (emptyBody, expectStringResponse, header, request)
import Http.Detailed as Detailed
import Plugins.DataTypes exposing (..)
import Plugins.JsonDecoder exposing (decodeGetPluginMetadata)
import Plugins.JsonEncoder exposing (encodePluginIds)
import Plugins.PluginData exposing (..)
import Set exposing (Set)


getUrl : Model -> String -> String
getUrl m url =
    m.contextPath ++ "/secure/api" ++ url


updateIndex : Model -> Cmd Msg
updateIndex model =
    request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model "/pluginsinternal/update"
        , body = emptyBody
        , expect = expectWhateverStringError <| ApiPostPlugins UpdateIndex
        , timeout = Nothing
        , tracker = Nothing
        }


getPluginInfos : Model -> Cmd Msg
getPluginInfos model =
    request
        { method = "GET"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model "/pluginsinternal"
        , body = emptyBody
        , expect = Detailed.expectJson ApiGetPlugins decodeGetPluginMetadata
        , timeout = Nothing
        , tracker = Nothing
        }


installPlugins : Set PluginId -> Model -> Cmd Msg
installPlugins plugins model =
    request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model "/pluginsinternal/install"
        , body = Http.jsonBody (encodePluginIds plugins)
        , expect = expectWhateverStringError <| ApiPostPlugins Install
        , timeout = Nothing
        , tracker = Nothing
        }


removePlugins : Set PluginId -> Model -> Cmd Msg
removePlugins plugins model =
    request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model "/pluginsinternal/remove"
        , body = Http.jsonBody (encodePluginIds plugins)
        , expect = expectWhateverStringError <| ApiPostPlugins Uninstall
        , timeout = Nothing
        , tracker = Nothing
        }


changePluginStatus : RequestType -> Set PluginId -> Model -> Cmd Msg
changePluginStatus requestType plugins model =
    request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model ("/pluginsinternal/" ++ requestTypeText requestType)
        , body = Http.jsonBody (encodePluginIds plugins)
        , expect = expectWhateverStringError <| ApiPostPlugins requestType
        , timeout = Nothing
        , tracker = Nothing
        }


requestTypeAction : RequestType -> Model -> Set PluginId -> Cmd Msg
requestTypeAction t model selected =
    case t of
        Install ->
            installPlugins selected model

        Uninstall ->
            removePlugins selected model

        Enable ->
            changePluginStatus Enable selected model

        Disable ->
            changePluginStatus Disable selected model

        UpdateIndex ->
            updateIndex model


{-| Expect for a result that is ignored, but a BadStatus that needs to be read as String (e.g. JSON response from API)
-}
expectWhateverStringError : (Result (Detailed.Error String) () -> msg) -> Http.Expect msg
expectWhateverStringError toMsg =
    expectStringResponse (Result.map (\_ -> ()) >> toMsg) Detailed.responseToString
