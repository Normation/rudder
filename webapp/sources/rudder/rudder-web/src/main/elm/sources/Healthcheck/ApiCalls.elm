module Healthcheck.ApiCalls exposing (..)

import Http exposing (emptyBody, expectJson, jsonBody, request)

import Healthcheck.DataTypes exposing (Model, Msg(..))
import Healthcheck.JsonDecoder exposing (decodeGetRoleApiResult)


getUrl: Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api/system" ++ url

getHealthCheck : Model -> Cmd Msg
getHealthCheck model =
    request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/healthcheck"
        , body            = emptyBody
        , expect          = expectJson GetHealthCheckResult decodeGetRoleApiResult
        , timeout         = Nothing
        , tracker         = Nothing
        }
