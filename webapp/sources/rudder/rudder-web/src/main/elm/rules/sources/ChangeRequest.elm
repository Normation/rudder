module ChangeRequest exposing (..)

import Json.Encode exposing (..)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (..)

--
-- Module to handle change request settings
--

type alias ChangeRequestSettings =
  { enableChangeMessage    : Bool
  , mandatoryChangeMessage : Bool
  , changeMessagePrompt    : String
  , enableChangeRequest    : Bool
  , changeRequestName      : String
  , newMessagePrompt       : String
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

encodeChangeRequestSettings: Maybe ChangeRequestSettings -> List (String, Json.Encode.Value)
encodeChangeRequestSettings changeRequestSettings =
  case changeRequestSettings of
    Nothing -> []
    Just settings ->
      let
        changeAuditParams =
          if settings.enableChangeMessage then
            [ ( "reason" , Json.Encode.string settings.newMessagePrompt ) ]
          else
            []

        changeRequestParams =
          if settings.enableChangeRequest then
            [ ( "changeRequestName"        , Json.Encode.string settings.changeRequestName )
            , ( "changeRequestDescription" , Json.Encode.string settings.newMessagePrompt  )
            ]
          else
            []
      in
        List.append changeAuditParams changeRequestParams