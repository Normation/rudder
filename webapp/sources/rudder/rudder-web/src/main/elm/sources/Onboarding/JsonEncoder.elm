module Onboarding.JsonEncoder exposing (..)

import Json.Encode exposing (Value, object, string)
import Json.Encode.Extra exposing (maybe)

import Onboarding.DataTypes exposing (AccountSettings, MetricsState(..))


encodeAccountSettings : AccountSettings -> Value
encodeAccountSettings accountSettings =
  let
    data = object
      [ ("username"      , maybe string accountSettings.username     )
      , ("password"      , maybe string accountSettings.password     )
      ]
  in
    data

encodeMetricsSettings : MetricsState -> Value
encodeMetricsSettings metrics =
  let
    metricsValue = case metrics of
      NotDefined -> "not_defined"
    {-  NoMetrics  -> "no"
      Minimal    -> "minimal"
      Complete   -> "complete" -}
  in
    object
    [ ("value", string metricsValue)
    ]