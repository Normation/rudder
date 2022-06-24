module JsonDecoder exposing (..)

import DataTypes exposing (..)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import String exposing (join, split)
import List exposing (drop, head, reverse)


-- GENERAL
decodeGetHooks =
  at [ "data" ] (Json.Decode.list <| decodeCategory)

decodeCategory : Decoder Category
decodeCategory =
  succeed Category
    |> required "basePath" decodeCategoryName
    |> hardcoded Other -- this parameter will be modified in GetHooksResult
    |> required "hooksFile" (Json.Decode.list <| decodeHook)

decodeHook : Decoder Hook
decodeHook =
  string |> andThen stringToHook

decodeCategoryName : Decoder String
decodeCategoryName =
  string |> andThen (pathToCategoryName)

pathToCategoryName : String -> Decoder String
pathToCategoryName path =
  let
    lastElemOfPath = head (reverse (split "/" path))
    categoryName =
      case lastElemOfPath of
        Just str -> str
        Nothing -> "ERROR: missing hooks directories"
   in
   Json.Decode.succeed categoryName

stringToHook : String -> Decoder Hook
stringToHook name =
   Json.Decode.succeed (Hook name)

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