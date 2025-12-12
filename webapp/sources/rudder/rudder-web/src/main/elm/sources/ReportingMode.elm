module ReportingMode exposing (..)

import Browser
import ReportingMode.DataTypes exposing (..)
import ReportingMode.Init exposing (..)
import ReportingMode.View exposing (view)
import ReportingMode.JsonEncoder exposing (..)

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
        reportingMode = case model.newMode of
          FullCompliance  -> "full-compliance"
          ChangesOnly     -> "changes-only"
          ReportsDisabled -> "reports-disabled"
          ErrorMode m     -> ""

        cmd = case model.newMode of
          ErrorMode m -> (errorNotification ("Error while saving reporting mode. Reason : Unknown mode '" ++ m ++ "'"))
          _ -> (saveMode (encodeMode reportingMode))
      in
      ( {model | reportingMode = model.newMode} , cmd)
