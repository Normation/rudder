module ChangeRequest exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Url.Builder

--
-- Module to handle change request settings
--
type Status = Unknown | Cancelled | Open | PendingValidation | PendingDeployment | Deployed

type alias ChangeRequest =
  { id          : Int
  , name        : String
  , description : String
  , status      : Status
  }

type alias ChangeRequestSettings =
  { enableChangeMessage    : Bool
  , mandatoryChangeMessage : Bool
  , changeMessagePrompt    : String
  , enableChangeRequest    : Bool
  , changeRequestName      : String
  , message                : String
  , displayMessagePrompt   : Bool
  , pendingChangeRequests  : List ChangeRequest
  , collapsePending        : Bool
  }

decodeGetChangeRequestSettings : Decoder ChangeRequestSettings
decodeGetChangeRequestSettings =
  at ["data", "settings" ] decodeChangeRequestSettings

decodeChangeRequestSettings : Decoder ChangeRequestSettings
decodeChangeRequestSettings =
  succeed ChangeRequestSettings
    |> required "enable_change_message"    Json.Decode.bool
    |> required "mandatory_change_message" Json.Decode.bool
    |> required "change_message_prompt"    Json.Decode.string
    |> required "enable_change_request"    Json.Decode.bool
    |> hardcoded ""
    |> hardcoded ""
    |> hardcoded False
    |> hardcoded []
    |> hardcoded False

decodePendingChangeRequests : Decoder (List ChangeRequest)
decodePendingChangeRequests =
  at [ "data" ] (list decodeChangeRequest)

decodeChangeRequest : Decoder ChangeRequest
decodeChangeRequest =
  succeed ChangeRequest
    |> required "id"          int
    |> required "displayName" string
    |> required "description" string
    |> required "status"       ( string |> andThen (\s -> toStatus s) )

toStatus : String -> Decoder Status
toStatus str =
  succeed ( case str of
    "Deployed"           -> Deployed
    "Pending deployment" -> PendingDeployment
    "Cancelled"          -> Cancelled
    "Pending validation" -> PendingValidation
    "Open"               -> Open
    _                    -> Unknown
  )

toLabel : Status -> String
toLabel status =
  case status of
    Deployed          -> "Deployed"
    PendingDeployment -> "Pending deployment"
    Cancelled         -> "Cancelled"
    PendingValidation -> "Pending validation"
    Open              -> "Open"
    _                 -> "Unknown"

changeRequestParameters: Maybe ChangeRequestSettings -> List Url.Builder.QueryParameter
changeRequestParameters changeRequestSettings =
  case changeRequestSettings of
    Nothing -> []
    Just settings ->
      let
        changeAuditParams =
          if settings.enableChangeMessage then
            [ Url.Builder.string "reason" settings.message ]
          else
            []

        changeRequestParams =
          if settings.enableChangeRequest then
            [ Url.Builder.string  "changeRequestName" settings.changeRequestName
            , Url.Builder.string "changeRequestDescription"  settings.message
            ]
          else
            []
      in
        List.append changeAuditParams changeRequestParams
