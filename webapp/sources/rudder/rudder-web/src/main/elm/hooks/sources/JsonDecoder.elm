module JsonDecoder exposing (..)

import DataTypes exposing (..)
import Dict exposing (Dict)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import Json.Decode.Field exposing (..)
import String exposing (join, split)
import List exposing (drop, head)


-- GENERAL
decodeGetHooks =
  at [ "data" ] decodeResult

decodeResult : Decoder (ApiResult)
decodeResult =
  succeed ApiResult
    |> required "root" string
    |> required "events" decodeCategory

decodeCategory : Decoder (List Category)
decodeCategory =
  (dict (list decodeHook)) |> andThen dictToCategories

dictToCategories : Dict String (List Hook) -> Decoder (List Category)
dictToCategories dict =
  Json.Decode.succeed (
    Dict.toList dict
      |> List.map (\(k, v) ->
        let
          kind =
            if String.startsWith "node-" k then
              Node
            else if String.startsWith "policy-" k then
              Policy
            else
              Other
        in
          Category k kind v
      )
  )

decodeHook : Decoder Hook
decodeHook =
  string|> andThen stringToHook

stringToHook : String -> Decoder Hook
stringToHook str =
  Json.Decode.succeed (Hook str)

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