module GroupRelatedRules.JsonDecoder exposing (..)

import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import GroupRelatedRules.DataTypes exposing (..)

decodeGetRulesTree : Decoder (Category Rule)
decodeGetRulesTree =
  at [ "data" , "ruleCategories" ] (decodeCategory "id" "categories" "rules" decodeRule)

decodeCategory : String -> String -> String -> Decoder a -> Decoder (Category a)
decodeCategory idIdentifier categoryIdentifier elemIdentifier elemDecoder =
  succeed Category
    |> required idIdentifier  string
    |> required "name"        string
    |> required "description" string
    |> required categoryIdentifier (map SubCategories  (list (lazy (\_ -> (decodeCategory idIdentifier categoryIdentifier elemIdentifier elemDecoder)))))
    |> required elemIdentifier      (list elemDecoder)

decodeRule : Decoder Rule
decodeRule =
  succeed Rule
    |> required "id"              (map RuleId string)
    |> required "displayName"      string
    |> required "categoryId"       string
    |> required "enabled"          bool
    |> required "tags"            (list (keyValuePairs string) |> andThen toTags)

toTags : List (List ( String, String )) -> Decoder (List Tag)
toTags lst =
  let
    concatList = List.concat lst
  in
    succeed
      ( List.map (\t -> Tag (Tuple.first t) (Tuple.second t)) concatList )
