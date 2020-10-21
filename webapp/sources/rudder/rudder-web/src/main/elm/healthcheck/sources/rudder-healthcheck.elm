module Healthcheck exposing (processApiError, update)

import Browser
import DataTypes exposing (Model, Msg(..), SeverityLevel(..))
import Http exposing (Error)
import Init exposing (init, subscriptions)
import View exposing (chooseHigherSecurityLevel, view)
import Result

main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }

update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    CallApi call ->
      (model, call model)
    GetHealthCheckResult res ->
      case res of
        Ok h ->
          let
            isWarningOrCritical =   (chooseHigherSecurityLevel h ==  DataTypes.Warning)
                                 || (chooseHigherSecurityLevel h ==  DataTypes.Critical)
            testUI = True
            testCheck = [
                DataTypes.Check "CPU Core" "12 cores available" CheckPassed
              , DataTypes.Check "RAM available" "Only 5GB of RAM left" Warning
              , DataTypes.Check "/var usage" "3GB left on /var" Critical
              , DataTypes.Check "Frontend standard" "CSS alignment is terrible, good luck" Warning
              , DataTypes.Check "Networking config" "Port 480 is open" CheckPassed
              , DataTypes.Check "File descriptor limit" "Limited to 10_000" Critical
              , DataTypes.Check "Certificate inspection" "Certificate is up to date" CheckPassed]

          in
            ( { model |
                  healthcheck  = testCheck -- replace `testCheck` by `h`
                , showChecks   = testUI -- replace `testUI` by `isWarningOrCritical`
              }
              , Cmd.none
             )
        Err err ->
          processApiError err model
    ChangeTabFocus newTab ->
      if model.tab == newTab then
        (model, Cmd.none)
      else
        ({model | tab = newTab}, Cmd.none)
    CheckListDisplay ->
       if model.showChecks then
         ({model | showChecks = False}, Cmd.none)
       else
         ({model | showChecks = True}, Cmd.none)

processApiError : Error -> Model -> ( Model, Cmd Msg )
processApiError err model =
    --let
    --    newModel =
            ({ model | healthcheck = []}, Cmd.none)
    --in
    --( newModel, Cmd.none ) |> createErrorNotification "Error while trying to fetch settings." err
