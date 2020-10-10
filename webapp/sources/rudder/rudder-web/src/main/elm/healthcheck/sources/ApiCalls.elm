module ApiCalls exposing (..)

import DataTypes exposing (Model, Msg(..))
import Http exposing (emptyBody, expectJson, jsonBody, request, send)
import JsonDecoder exposing (decodeGetRoleApiResult)

getUrl: DataTypes.Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api" ++ url

getHealthCheck : Model -> Cmd Msg
getHealthCheck model =
    let
        req =
            request
                { method          = "GET"
                , headers         = []
                , url             = getUrl model "/healthcheck"
                , body            = emptyBody
                , expect          = expectJson decodeGetRoleApiResult
                , timeout         = Nothing
                , withCredentials = False
                }
    in
    send GetHealthCheckResult req