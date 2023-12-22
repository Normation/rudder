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
          ({ model | healthcheck  = h}, Cmd.none)
        Err err ->
          processApiError err model
    ChangeTabFocus newTab ->
      if model.tab == newTab then
        (model, Cmd.none)
      else
        ({model | tab = newTab}, Cmd.none)

processApiError : Error -> Model -> ( Model, Cmd Msg )
processApiError err model =
    --let
    --    newModel =
            ({ model | healthcheck = []}, Cmd.none)
    --in
    --( newModel, Cmd.none ) |> createErrorNotification "Error while trying to fetch settings." err
