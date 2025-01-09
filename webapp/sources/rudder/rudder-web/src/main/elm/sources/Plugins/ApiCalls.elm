module Plugins.ApiCalls exposing (..)

import Http exposing (emptyBody, expectJson, header, request)
import Http.Detailed as Detailed
import Plugins.DataTypes exposing (..)
import Plugins.JsonDecoder exposing (decodeGetPluginInfos)


getUrl : Model -> String -> String
getUrl m url =
    m.contextPath ++ "/secure/api" ++ url


getPluginInfos : Model -> Cmd Msg
getPluginInfos model =
    request
        { method = "GET"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model "/pluginsinternal"
        , body = emptyBody
        , expect = Detailed.expectJson ApiGetPlugins decodeGetPluginInfos
        , timeout = Nothing
        , tracker = Nothing
        }
--}
