module Onboarding.ApiCalls exposing (..)

import Http exposing (emptyBody, expectJson, jsonBody, request)
import Json.Encode

import Onboarding.DataTypes exposing (Model, Msg(..), AccountSettings, MetricsState)
import Onboarding.JsonEncoder exposing (encodeAccountSettings, encodeMetricsSettings)
import Onboarding.JsonDecoder exposing (decodeGetAccountSettings, decodeGetMetricsSettings, decodeSetupDone)


getUrl: Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api" ++ url

getAccountSettings : Model -> Cmd Msg
getAccountSettings model =
  request
    { method          = "GET"
    , headers         = []
    , url             = getUrl model "/plugins/settings"
    , body            = emptyBody
    , expect          = expectJson GetAccountSettings decodeGetAccountSettings
    , timeout         = Nothing
    , tracker         = Nothing
    }

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
  request
    { method          = "POST"
    , headers         = []
    , url             = getUrl model "/plugins/settings"
    , body            = jsonBody (encodeAccountSettings accountSettings)
    , expect          = expectJson PostAccountSettings decodeGetAccountSettings
    , timeout         = Nothing
    , tracker         = Nothing
    }

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
  request
    { method          = "POST"
    , headers         = []
    , url             = getUrl model "/settings/rudder_setup_done"
    , body            = jsonBody (Json.Encode.object [ ("value", Json.Encode.bool res)])
    , expect          = expectJson SetupDone decodeSetupDone
    , timeout         = Nothing
    , tracker         = Nothing
    }
