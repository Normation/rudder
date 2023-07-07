port module ComplianceMode.Init exposing (..)

import Http exposing (Error)
import Task
import Json.Decode exposing (..)
import Json.Decode.Pipeline as D exposing (..)

import ComplianceMode.DataTypes exposing (..)


-- PORTS / SUBSCRIPTIONS
port saveMode            : Value  -> Cmd msg
port errorNotification   : String -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions model =
  Sub.none

init : { contextPath : String, hasWriteRights : Bool, complianceMode : {name : String, heartbeatPeriod : Int}, globalMode : {name : String, heartbeatPeriod : Int} } -> ( Model, Cmd Msg )
init flags =
  let
    getComplianceMode : String -> ComplianceMode
    getComplianceMode mode =
      case mode of
        "full-compliance"  -> FullCompliance
        "changes-only"     -> ChangesOnly
        "reports-disabled" -> ReportsDisabled
        _                  -> ErrorMode mode

    initUi      = UI flags.hasWriteRights
    currentMode = getComplianceMode flags.complianceMode.name
    initModel   = Model flags.contextPath initUi currentMode currentMode (getComplianceMode flags.globalMode.name)
  in
    ( initModel , Cmd.none )