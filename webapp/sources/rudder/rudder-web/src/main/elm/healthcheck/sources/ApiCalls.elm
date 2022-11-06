module ApiCalls exposing (..)

import DataTypes exposing (Model, Msg(..))
import Http exposing (emptyBody, expectJson, jsonBody, request)
import JsonDecoder exposing (decodeGetRoleApiResult)

getUrl: DataTypes.Model -> String -> String
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
