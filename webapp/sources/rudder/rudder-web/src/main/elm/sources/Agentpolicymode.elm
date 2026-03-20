port module Agentpolicymode exposing (..)

import Agentpolicymode.JsonDecoder exposing (decodeErrorDetails)
import Browser
import Http exposing (..)
import Http.Detailed as Detailed
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

    GetChangeMessageSettings res ->
      case res of
        Ok settings ->
          let
            ui = model.ui
          in
            ( { model | ui = { ui | modalSettings = modalSettings settings } }
              , Cmd.none
            )
        Err err ->
          processApiError "Getting change message settings, saving policy mode may not be possible" err model

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

    StartSaving reason ->
      let
        ui = model.ui
        newModel = {model | ui = {ui | saving = True}}
      in
      ( newModel , saveChanges reason model)

    SaveChanges res ->
      let
        ui = model.ui
        selectedSettings = model.selectedSettings
        currentSettings  = model.currentSettings
        newModel = {model | ui = {ui | saving = False}}
        processError err =
          case model.ui.modalState of
            ConfirmModal value ({ isMandatory } as settings) ->
              if isMandatory then
                transformModalError err
                  |> Maybe.map (\e ->
                    ({ newModel | ui = { ui | saving = False, modalState = ConfirmModal { value | error = Just e } settings } }, Cmd.none)
                  )
                  |> Maybe.withDefault (
                    processApiError "Saving changes" err newModel
                  )

              else
                processApiError "Saving changes" err newModel

            _ ->
              processApiError "Saving changes" err newModel

      in
        case res of
          Ok s ->
              ( { newModel |
                    currentSettings = {currentSettings | policyMode = s.policyMode}
                  , selectedSettings = {selectedSettings | policyMode = s.policyMode}
                  , ui = {ui | saving = False, modalState = NoModal}
                }
                , successNotification ""
              )
          Err err ->
              processError err

    CloseModal ->
      let
        ui = model.ui
        newModel = {model | ui = {ui | modalState = NoModal}}
      in
      ( newModel, Cmd.none )

    OpenModal settings ->
      let
        ui = model.ui
      in
      ( { model | ui = { ui | modalState = ConfirmModal { value = "", error = Nothing } settings } }
      , Cmd.none
      )

    UpdateChangeMessage value ->
      let
        ui = model.ui
        modalState =
          case ui.modalState of
            ConfirmModal { error } settings ->
              ConfirmModal { value = value, error = error } settings

            _ ->
              ui.modalState
      in
      ( { model | ui = { ui | modalState = modalState } }
      , Cmd.none
      )


processApiError : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
  let
    modelUi = model.ui
    message =
      case err of
        Detailed.BadUrl url ->
          "The URL " ++ url ++ " was invalid"
        Detailed.Timeout ->
          "Unable to reach the server, try again"
        Detailed.NetworkError ->
          "Unable to reach the server, check your network connection"
        Detailed.BadStatus metadata body ->
          let
            (title, errors) = decodeErrorDetails body
          in
            title ++ "\n" ++ errors
        Detailed.BadBody metadata body msg ->
          msg
  in
    ({ model | ui = { modelUi | saving = False}}, errorNotification ("Error when "++ apiName ++", details: \n" ++ message ))

getUrl : Model -> String
getUrl model = model.contextPath ++ "/secure/configurationManager/directiveManagement"


modalSettings : ChangeMessageSettings -> Maybe ModalSettings
modalSettings { enableChangeMessage, changeMessagePrompt, mandatoryChangeMessage } =
  if not enableChangeMessage then
    Nothing
  else
    Just (ModalSettings changeMessagePrompt mandatoryChangeMessage)


transformModalError : Detailed.Error String -> Maybe String
transformModalError err =
  case err of
    Detailed.BadStatus metadata body ->
      body
        |> decodeErrorDetails
        |> Tuple.first
        |> Just

    Detailed.BadBody metadata body msg ->
      Just msg

    _ ->
      Nothing
