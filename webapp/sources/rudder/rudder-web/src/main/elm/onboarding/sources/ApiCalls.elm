module ApiCalls exposing (..)

import DataTypes exposing (Model, Msg(..), AccountSettings, MetricsState)
import Http exposing (emptyBody, expectJson, jsonBody, request, send)
import Json.Encode
import JsonDecoder exposing (decodeGetAccountSettings, decodeGetMetricsSettings, decodeSetupDone)
import JsonEncoder exposing (encodeAccountSettings, encodeMetricsSettings)

getUrl: Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api" ++ url

getAccountSettings : Model -> Cmd Msg
getAccountSettings model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/plugins/settings"
        , body            = emptyBody
        , expect          = expectJson decodeGetAccountSettings
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetAccountSettings req

{-
getMetricsSettings : Model -> Cmd Msg
getMetricsSettings model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model "/settings/send_metrics"
        , body            = emptyBody
        , expect          = expectJson decodeGetMetricsSettings
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send GetMetricsSettings req
-}
postAccountSettings : Model -> AccountSettings -> Cmd Msg
postAccountSettings model accountSettings =
  let
    req =
      request
        { method          = "POST"
        , headers         = []
        , url             = getUrl model "/plugins/settings"
        , body            = jsonBody (encodeAccountSettings accountSettings)
        , expect          = expectJson decodeGetAccountSettings
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send PostAccountSettings req
{-
postMetricsSettings : Model -> MetricsState -> Cmd Msg
postMetricsSettings model metrics =
  let
    req =
      request
        { method          = "POST"
        , headers         = []
        , url             = getUrl model "/settings/send_metrics"
        , body            = jsonBody (encodeMetricsSettings metrics)
        , expect          = expectJson decodeGetMetricsSettings
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send PostMetricsSettings req
-}

setupDone : Model -> Bool -> Cmd Msg
setupDone model res =
  let
    req =
      request
        { method          = "POST"
        , headers         = []
        , url             = getUrl model "/settings/rudder_setup_done"
        , body            = jsonBody (Json.Encode.object [ ("value", Json.Encode.bool res)])
        , expect          = expectJson decodeSetupDone
        , timeout         = Nothing
        , withCredentials = False
        }
  in
    send SetupDone req