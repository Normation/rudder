module Agentpolicymode.ApiCalls exposing (..)

import Http exposing (..)
import Http.Detailed as Detailed
import Json.Decode exposing (Decoder)
import Task exposing (Task)
import Url.Builder exposing (QueryParameter)

import Agentpolicymode.DataTypes exposing (..)
import Agentpolicymode.JsonDecoder exposing (..)
import Agentpolicymode.JsonEncoder exposing (..)


--
-- This files contains all API calls for the Directive compliance UI
--

getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: url) p

getGlobalPolicyMode : Model -> Cmd Msg
getGlobalPolicyMode model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "settings", "global_policy_mode" ] []
        , body    = emptyBody
        , expect  = expectJson GetGlobalPolicyModeResult decodeGetPolicyMode
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getPolicyModeOverridable : Model -> Cmd Msg
getPolicyModeOverridable model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "settings", "global_policy_mode_overridable" ] []
        , body    = emptyBody
        , expect  = expectJson GetPolicyModeOverridableResult decodeGetPolicyModeOverridable
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getNodePolicyMode : Model -> String -> Cmd Msg
getNodePolicyMode model nodeId =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "nodes", nodeId ] []
        , body    = emptyBody
        , expect  = expectJson GetNodePolicyModeResult decodeGetNodeDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveChanges : (Maybe Reason) -> Model -> Cmd Msg
saveChanges reason model =
  let
    (url, encodeSettings, decoder) = case model.ui.form of
      GlobalForm  -> ([ "settings" ], encodeGlobalSettings, decodeGetGlobalSettings)
      NodeForm id -> ([ "nodes", id ], encodeNodeSettings reason, decodeSaveNodeDetails)
    req =
      request
        { method  = "POST"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model url []
        , body    = encodeSettings model.selectedSettings |> jsonBody
        , expect  = expectJson SaveChanges decoder
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getChangeMessageSettings : Model -> Cmd Msg
getChangeMessageSettings model =
  Task.attempt GetChangeMessageSettings (getChangeMessageSettingsTask model)


getChangeMessageSettingsTask : Model -> Task (Detailed.Error String) ChangeMessageSettings
getChangeMessageSettingsTask model =
  let
    default =
      ChangeMessageSettings False False ""
    allSettings : Task (Detailed.Error String) ChangeMessageSettings
    allSettings =
      Task.map3 ChangeMessageSettings
        (getEnableChangeMessageSetting model)
        (getMandatoryChangeMessageSetting model)
        (getChangeMessagePromptSetting model)
  in
  allSettings
    |> handle403With default



getEnableChangeMessageSetting : Model -> Task (Detailed.Error String) (Bool)
getEnableChangeMessageSetting model =
  Http.task
    { method   = "GET"
    , headers  = [header "X-Requested-With" "XMLHttpRequest"]
    , url      = getUrl model [ "settings", "enable_change_message" ] []
    , body     = emptyBody
    , resolver = Http.stringResolver <| handleJsonResponse <| decodeGetEnableChangeMsg
    , timeout  = Nothing
    }


getMandatoryChangeMessageSetting : Model -> Task (Detailed.Error String) (Bool)
getMandatoryChangeMessageSetting model =
  Http.task
    { method   = "GET"
    , headers  = [header "X-Requested-With" "XMLHttpRequest"]
    , url      = getUrl model [ "settings", "mandatory_change_message" ] []
    , body     = emptyBody
    , resolver = Http.stringResolver <| handleJsonResponse <| decodeGetMandatoryMsg
    , timeout  = Nothing
    }


getChangeMessagePromptSetting : Model -> Task (Detailed.Error String) (String)
getChangeMessagePromptSetting model =
  Http.task
    { method   = "GET"
    , headers  = [header "X-Requested-With" "XMLHttpRequest"]
    , url      = getUrl model [ "settings", "change_message_prompt" ] []
    , body     = emptyBody
    , resolver = Http.stringResolver <| handleJsonResponse <| decodeGetMsgPrompt
    , timeout  = Nothing
    }


changeMessageReasonParams : String -> List Url.Builder.QueryParameter
changeMessageReasonParams reason =
  [ Url.Builder.string "reason" reason ]


expectJson : (Result (Detailed.Error String) b -> c) -> Decoder b -> Expect c
expectJson msg =
  Detailed.expectJson (Result.map Tuple.second >> msg)


handleJsonResponse : Decoder a -> Http.Response String -> Result (Detailed.Error String) a
handleJsonResponse decoder response =
    case response of
        Http.BadUrl_ url ->
            Err (Detailed.BadUrl url)

        Http.Timeout_ ->
            Err Detailed.Timeout

        Http.BadStatus_ metadata body ->
            Err (Detailed.BadStatus metadata body)

        Http.NetworkError_ ->
            Err Detailed.NetworkError

        Http.GoodStatus_ metadata body ->
          Json.Decode.decodeString decoder body
            |> Result.mapError (Json.Decode.errorToString >> Detailed.BadBody metadata body)

handle403With : a -> Task (Detailed.Error body) a -> Task (Detailed.Error body) a
handle403With default =
    Task.onError
      ( \e -> case e of
          Detailed.BadStatus { statusCode } _ ->
              case statusCode of
                403 ->
                  Task.succeed default

                _ ->
                  Task.fail e

          _ ->
            Task.fail e
      )