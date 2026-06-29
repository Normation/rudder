module Healthcheck.ApiCalls exposing (..)

import Healthcheck.DataTypes exposing (Model, Msg(..))
import Healthcheck.JsonDecoder exposing (decodeGetRoleApiResult)
import Http exposing (emptyBody, expectJson, header, request)


getUrl : Model -> String -> String
getUrl m url =
    m.contextPath ++ "/secure/api/system" ++ url


getHealthCheck : Model -> Cmd Msg
getHealthCheck model =
    request
        { method = "GET"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model "/healthcheck"
        , body = emptyBody
        , expect = expectJson GetHealthCheckResult decodeGetRoleApiResult
        , timeout = Nothing
        , tracker = Nothing
        }
