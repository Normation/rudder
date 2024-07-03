module QuickSearch.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import List exposing (drop, head)
import String exposing (join, split)

import QuickSearch.Datatypes exposing (..)


decoderResult : Decoder SearchResult
decoderResult =
  succeed SearchResult
    |> required "header" decoderHeader
    |> required "items" (list decoderItem)

decoderHeader : Decoder SearchResultHeader
decoderHeader =
  succeed SearchResultHeader
    |> required "type" decoderType
    |> required "summary" string
    |> required "numbers" int

decoderItem : Decoder SearchResultItem
decoderItem =
  succeed SearchResultItem
    |> required "type" decoderType
    |> required "name" string
    |> required "id" string
    |> required "value" string
    |> required "desc" string
    |> required "url" string

decoderType : Decoder Kind
decoderType =
    string |>
      andThen ( \v ->
        case String.toLower v of
          "node" -> succeed Node
          "group" -> succeed Group
          "parameter" -> succeed Parameter
          "directive" -> succeed Directive
          "technique" -> succeed Technique
          "rule" -> succeed Rule
          _ -> fail ("'" ++ v ++ "' is not valid quicksearch result type")
      )


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