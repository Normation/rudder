module Accounts.JsonDecoder exposing (..)

import Dict exposing (Dict)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (..)
import String exposing (join, split)
import List exposing (drop, head)
import Tuple

import Accounts.DataTypes exposing (..)
import Accounts.DatePickerUtils exposing (stringToPosix)


-- GENERAL
decodeGetAccounts datePickerInfo=
  at [ "data" ] (decodeResult datePickerInfo)

decodeAccount : DatePickerInfo -> Decoder Account
decodeAccount datePickerInfo =
  succeed Account
    |> required "id"                    string
    |> required "name"                  string
    |> required "description"           string
    |> required "authorizationType"     string
    |> required "kind"                  string
    |> required "enabled"               bool
    |> required "creationDate"          string
    |> required "token"                 string
    |> required "tokenGenerationDate"   string
    |> required "expirationDateDefined" bool
    |> optional "expirationDate"        ( string
      |> andThen (\s -> case stringToPosix datePickerInfo s of
        Just date -> succeed (Just date)
        Nothing   -> fail "Expiration date invalid : bad format"
      )
    ) Nothing
    |> optional "acl" (map Just (list <| decodeAcl)) Nothing

decodeAcl : Decoder AccessControl
decodeAcl =
  succeed AccessControl
    |> required "path" string
    |> required "verb" string

decodeResult : DatePickerInfo -> Decoder (ApiResult)
decodeResult datePickerInfo =
  succeed ApiResult
    |> required "aclPluginEnabled" bool
    |> required "accounts" (list (decodeAccount datePickerInfo))

decodePostAccount : DatePickerInfo -> Decoder Account
decodePostAccount datePickerInfo =
  at [ "data" , "accounts" ] (index 0 (decodeAccount datePickerInfo))

decodeErrorDetails : String -> (String, String)
decodeErrorDetails json =
  let
    errorMsg = decodeString (Json.Decode.at ["errorDetails"] string) json
    msg = case errorMsg of
      Ok s -> s
      Err e -> "fail to process errorDetails"
    errors = split "<-" msg
    title = head errors
  in
  case title of
    Nothing -> ("" , "")
    Just s -> (s , (join " \n " (drop 1 (List.map (\err -> "\t ‣ " ++ err) errors))))