module ChangeRequest exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Url.Builder

--
-- Module to handle change request settings
--

type alias ChangeRequestSettings =
  { enableChangeMessage    : Bool
  , mandatoryChangeMessage : Bool
  , changeMessagePrompt    : String
  , enableChangeRequest    : Bool
  , changeRequestName      : String
  , message                : String
  , displayMessagePrompt   : Bool
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
