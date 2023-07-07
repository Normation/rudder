module Compliancemode exposing (..)

import Browser
import Dict
import Dict.Extra
import Http exposing (..)
import Http.Detailed as Detailed
import Result
import List.Extra
import Random
import UUID
import Json.Encode exposing (..)
import Task


import ComplianceMode.DataTypes exposing (..)
import ComplianceMode.Init exposing (..)
import ComplianceMode.View exposing (view)
import ComplianceMode.JsonEncoder exposing (..)

main = Browser.element
  { init          = init
  , view          = view
  , update        = update
  , subscriptions = subscriptions
  }

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    -- Do an API call
    CallApi call ->
      (model, call model)

    -- neutral element
    Ignore ->
      (model , Cmd.none)

    UpdateMode mode ->
      ({model | newMode = mode}, Cmd.none)

    SaveChanges ->
      let
        complianceMode = case model.newMode of
          FullCompliance  -> "full-compliance"
          ChangesOnly     -> "changes-only"
          ReportsDisabled -> "reports-disabled"
          ErrorMode m     -> ""

        cmd = case model.newMode of
          ErrorMode m -> (errorNotification ("Error while saving reporting mode. Reason : Unknown mode '" ++ m ++ "'"))
          _ -> (saveMode (encodeMode complianceMode))
      in
      ( {model | complianceMode = model.newMode} , cmd)