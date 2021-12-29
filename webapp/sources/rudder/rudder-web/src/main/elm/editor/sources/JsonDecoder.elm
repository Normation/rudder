module  JsonDecoder exposing (..)


import DataTypes exposing (..)
import Iso8601
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import List exposing (drop, head)
import MethodConditions exposing (..)
import AgentValueParser exposing (..)
import String exposing (join, split)

decodeTechniqueParameter : Decoder TechniqueParameter
decodeTechniqueParameter =
  succeed TechniqueParameter
    |> required "id" (map ParameterId string)
    |> required "name" string
    |> required "description" string
    |> optional "mayBeEmpty" bool False

decodeCallParameter : Decoder CallParameter
decodeCallParameter =
  succeed CallParameter
    |> required "name" (map ParameterId string)
    |> required "value" (map getAgentValue string)

parseCondition : String -> Condition
parseCondition class_context =
     case String.split "." class_context of
       [] -> Condition Nothing ""
       [ "any" ] -> Condition Nothing ""
       [_] ->
         case parseOs class_context of
           Nothing ->  Condition Nothing class_context
           os -> Condition os ""
       [ head , "any" ] ->
         case parseOs head of
           Nothing ->  Condition Nothing head
           os -> Condition os ""

       head :: rest ->
         case parseOs head of
           Nothing ->  Condition Nothing class_context
           os -> Condition os (String.join "." rest)

decodeMethodElem : Maybe CallId -> Decoder MethodElem
decodeMethodElem parent =

  oneOf [(map (Call parent) decodeMethodCall), (map (Block parent) decodeBlock) ]

decodeCompositionRule : Decoder ReportingLogic
decodeCompositionRule =
  let
    innerDecoder =
      \v ->
        case v of
          "worst-case-weighted-sum" -> succeed (WorstReport WorstReportWeightedSum)
          "worst-case-weighted-one" -> succeed (WorstReport WorstReportWeightedOne)
          "weighted"                -> succeed WeightedReport
          "focus"                   -> succeed FocusReport
                                       |> required "value" string
          _                         -> fail (v ++ " is not a valid reporting logic")
  in succeed innerDecoder
    |> required "type" string
    |> resolve

decodeBlock : Decoder MethodBlock
decodeBlock =
  succeed MethodBlock
    |> required "id" (map CallId string)
    |> optional "component"  string ""
    |> required "condition"  (map parseCondition string)
    |> required "reportingLogic" decodeCompositionRule
    |> required "calls" (list  (lazy (\_ -> decodeMethodElem Nothing)))
    >> andThen (\block -> succeed { block | calls = List.map (\x ->
                                                               case x of
                                                                 Block _ b -> Block (Just block.id) b
                                                                 Call _ c -> Call (Just block.id) c
                                   ) block.calls}
               )

decodeMethodCall : Decoder MethodCall
decodeMethodCall =
  succeed MethodCall
    |> required "id" (map CallId string)
    |> required "method" (map MethodId string)
    |> required "parameters"  (list decodeCallParameter )
    |> required "condition"  (map parseCondition string)
    |> optional "component"  string ""
    |> optional "disableReporting" bool False

decodeTechnique : Decoder Technique
decodeTechnique =
  succeed Technique
    |> required "id" (map TechniqueId string)
    |> required "version"  string
    |> required "name"  string
    |> required "description"  string
    |> required "category"  string
    |> required "calls" (list (lazy (\_ -> decodeMethodElem Nothing)))
    |> required "parameter" (list decodeTechniqueParameter)
    |> required "resources" (list decodeResource)

decodeAgent : Decoder Agent
decodeAgent =
  andThen (\v ->
    case v of
      "cfengine-community" -> succeed Cfengine
      "dsc"      -> succeed Dsc
      _          -> fail (v ++ " is not a valid agent")
  ) string

decodeConstraint: Decoder (List Constraint)
decodeConstraint =
  andThen ( \v ->
    List.foldl (\val acc ->
      case val of
        ("allow_empty_string", value) ->
          case decodeValue bool value of
            Ok b -> andThen (\t -> succeed (AllowEmpty b :: t) ) acc
            Err e-> fail (errorToString e)
        ("allow_whitespace_string", value) ->
          case decodeValue bool value of
            Ok b -> andThen (\t -> succeed (AllowWhiteSpace b :: t) ) acc
            Err e-> fail (errorToString e)
        ("max_length", value) ->
          case decodeValue int value of
            Ok b -> andThen (\t -> succeed (MaxLength b :: t) ) acc
            Err e-> fail (errorToString e)
        ("min_length", value) ->
          case decodeValue int value of
            Ok b -> andThen (\t -> succeed (MinLength b :: t) ) acc
            Err e-> fail (errorToString e)
        ("regex", value) ->
          case decodeValue string value of
            Ok b -> andThen (\t -> succeed (MatchRegex b :: t) ) acc
            Err e-> fail (errorToString e)
        ("not_regex", value) ->
          case decodeValue string value of
            Ok b -> andThen (\t -> succeed (NotMatchRegex b :: t) ) acc
            Err e-> fail (errorToString e)
        ("select", value) ->
          case decodeValue (list string) value of
            Ok b -> andThen (\t -> succeed (Select b :: t) ) acc
            Err e-> fail (errorToString e)
        _ -> acc

    ) (succeed []) v

  ) (keyValuePairs value)

decodeMethodParameter: Decoder MethodParameter
decodeMethodParameter =
  succeed MethodParameter
    |> required "name" (map ParameterId string)
    |> required "description" string
    |> required "type" string
    |> required "constraints" decodeConstraint

decodeMethod : Decoder Method
decodeMethod =
  succeed Method
    |> required "id" (map MethodId string)
    |> required "name" string
    |> required "description" string
    |> requiredAt [ "condition", "prefix" ] string
    |> requiredAt [ "condition", "parameter" ] (map ParameterId string)
    |> required "agents" (list decodeAgent)
    |> required "parameters" (list decodeMethodParameter)
    |> optional "documentation" (maybe string) Nothing
    |> optionalAt [ "deprecated", "info" ] (maybe string) Nothing
    |> optionalAt [ "deprecated", "replacedBy<" ] (maybe string) Nothing

decodeDeleteTechniqueResponse : Decoder TechniqueId
decodeDeleteTechniqueResponse =
  succeed TechniqueId |>
    required "id" string

decodeCategory : Decoder TechniqueCategory
decodeCategory =
  succeed TechniqueCategory
    |> required "id" string
    |> required "name" string
    |> required "path" string
    |> optional  "subCategories" (map SubCategories (list (lazy (\_ -> decodeCategory)))) ( SubCategories [] )

decodeResource : Decoder Resource
decodeResource =
  succeed Resource
    |> required "name" string
    |> required "state" (andThen (\s -> case s of
                                          "new" -> succeed New
                                          "untouched" -> succeed Untouched
                                          "modified" -> succeed Modified
                                          "deleted" -> succeed Deleted
                                          _ -> fail "not a valid state"
                        ) string)


decodeDraft : Decoder (Maybe Draft)
decodeDraft =
  maybe (succeed Draft
    |> required "technique" decodeTechnique
    |> optional "origin"  (maybe decodeTechnique) Nothing
    |> required "id"  string
    |> required "date"  Iso8601.decoder)

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