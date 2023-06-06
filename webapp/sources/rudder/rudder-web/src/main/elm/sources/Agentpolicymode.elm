port module Agentpolicymode exposing (..)

import Browser
import Http exposing (..)
import Result

import Agentpolicymode.DataTypes exposing (..)
import Agentpolicymode.Init exposing (init)
import Agentpolicymode.View exposing (view)
import Agentpolicymode.ApiCalls exposing (saveChanges)

-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg
port successNotification : String -> Cmd msg
port initTooltips        : String -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions _ =
  Sub.none

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    Ignore ->
      ( model , Cmd.none)

    GetGlobalPolicyModeResult res ->
      case res of
        Ok p ->
          let
            newModel  = { model | globalPolicyMode = p }
            initModel = if model.ui.form == GlobalForm then
              let
                settings  = model.currentSettings
                newSettings = {settings | policyMode = p}
              in
                { newModel | currentSettings = newSettings, selectedSettings = newSettings}
              else
              newModel
          in
            ( initModel , initTooltips "" )
        Err err ->
          processApiError "Getting Policy Mode" err model

    GetNodePolicyModeResult res ->
      case res of
        Ok s ->
          let
            newModel = case model.ui.form of
              GlobalForm -> model
              NodeForm _ ->
                let
                  settings = model.currentSettings
                  newSettings = {settings | policyMode = s.policyMode}
                in
                  {model | currentSettings = newSettings, selectedSettings = newSettings}
          in
            ( newModel , initTooltips "" )
        Err err ->
          processApiError "Getting Node info" err model

    GetPolicyModeOverridableResult res ->
      case res of
        Ok o ->
          let
            settings = model.currentSettings
            newSettings = {settings | overridable = o}
          in
            ( { model | currentSettings = newSettings, selectedSettings = newSettings }
              , initTooltips ""
            )
        Err err ->
          processApiError "Getting Policy Mode Overridable info" err model

    SelectMode mode ->
      let
        settings = model.selectedSettings
        newSettings = {settings | policyMode = mode}
      in
        ({model | selectedSettings = newSettings}, Cmd.none)

    SelectOverrideMode mode ->
      let
        settings = model.selectedSettings
        newSettings = {settings | overridable = mode}
      in
        ({model | selectedSettings = newSettings}, Cmd.none)

    StartSaving ->
      let
        ui = model.ui
        newModel = {model | ui = {ui | saving = True}}
      in
      ( newModel , saveChanges model)

    SaveChanges res ->
      let
        ui = model.ui
        selectedSettings = model.selectedSettings
        currentSettings  = model.currentSettings
        newModel = {model | ui = {ui | saving = False}}
      in
        case res of
          Ok s ->
              ( {newModel | currentSettings = {currentSettings | policyMode = s.policyMode}, selectedSettings = {selectedSettings | policyMode = s.policyMode}} , successNotification "")
          Err err ->
            processApiError "Saving changes" err newModel

processApiError : String -> Error -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
  let
    message =
      case err of
        Http.BadUrl url ->
            "The URL " ++ url ++ " was invalid"
        Http.Timeout ->
            "Unable to reach the server, try again"
        Http.NetworkError ->
            "Unable to reach the server, check your network connection"
        Http.BadStatus 500 ->
            "The server had a problem, try again later"
        Http.BadStatus 400 ->
            "Verify your information and try again"
        Http.BadStatus _ ->
            "Unknown error"
        Http.BadBody errorMessage ->
            errorMessage

  in
    (model, errorNotification ("Error when " ++ apiName ++ ", details: \n" ++ message ) )

getUrl : Model -> String
getUrl model = model.contextPath ++ "/secure/configurationManager/directiveManagement"