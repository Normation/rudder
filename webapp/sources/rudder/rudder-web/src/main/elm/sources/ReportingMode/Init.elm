port module ReportingMode.Init exposing (..)

import Json.Decode exposing (..)
import ReportingMode.DataTypes exposing (..)


-- PORTS / SUBSCRIPTIONS
port saveMode            : Value  -> Cmd msg
port errorNotification   : String -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions model =
  Sub.none

init : { contextPath : String, hasWriteRights : Bool, reportingMode : {mode : String}, globalMode : {mode : String} } -> ( Model, Cmd Msg )
init flags =
  let
    getReportingMode : String -> ReportingMode
    getReportingMode mode =
      case mode of
        "full-compliance"  -> FullCompliance
        "changes-only"     -> ChangesOnly
        "reports-disabled" -> ReportsDisabled
        _                  -> ErrorMode mode

    initUi      = UI flags.hasWriteRights
    currentMode = getReportingMode flags.reportingMode.mode
    initModel   = Model flags.contextPath initUi currentMode currentMode (getReportingMode flags.globalMode.mode)
  in
    ( initModel , Cmd.none )
