module Plugins.ApiCalls exposing (..)

import Http exposing (emptyBody, header, request)
import Http.Detailed as Detailed
import Plugins.DataTypes exposing (..)
import Plugins.JsonDecoder exposing (decodeGetPluginsInfo)
import Plugins.JsonEncoder exposing (encodePluginIds)


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
        , expect = Detailed.expectWhatever <| ApiPostPlugins << Result.map (\_ -> UpdateIndex)
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
        , expect = Detailed.expectJson ApiGetPlugins decodeGetPluginsInfo
        , timeout = Nothing
        , tracker = Nothing
        }


installPlugins : List PluginId -> Model -> Cmd Msg
installPlugins plugins model =
    request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model "/pluginsinternal/install"
        , body = Http.jsonBody (encodePluginIds plugins)
        , expect = Detailed.expectWhatever <| ApiPostPlugins << Result.map (\_ -> Install)
        , timeout = Nothing
        , tracker = Nothing
        }


removePlugins : List PluginId -> Model -> Cmd Msg
removePlugins plugins model =
    request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model "/pluginsinternal/remove"
        , body = Http.jsonBody (encodePluginIds plugins)
        , expect = Detailed.expectWhatever <| ApiPostPlugins << Result.map (\_ -> Uninstall)
        , timeout = Nothing
        , tracker = Nothing
        }


changePluginStatus : RequestType -> List PluginId -> Model -> Cmd Msg
changePluginStatus requestType plugins model =
    request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model ("/pluginsinternal/" ++ requestTypeText requestType)
        , body = Http.jsonBody (encodePluginIds plugins)
        , expect = Detailed.expectWhatever <| ApiPostPlugins << Result.map (\_ -> requestType)
        , timeout = Nothing
        , tracker = Nothing
        }


requestTypeAction : RequestType -> Model -> Cmd Msg
requestTypeAction t model =
    case t of
        Install ->
            installPlugins model.ui.selected model

        Uninstall ->
            removePlugins model.ui.selected model

        Enable ->
            changePluginStatus Enable model.ui.selected model

        Disable ->
            changePluginStatus Disable model.ui.selected model

        UpdateIndex ->
            updateIndex model
