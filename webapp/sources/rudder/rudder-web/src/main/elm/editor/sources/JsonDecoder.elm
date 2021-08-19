module  JsonDecoder exposing (..)


import DataTypes exposing (..)
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import MethodConditions exposing (..)

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
    |> required "value" string

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
          "worst" -> succeed WorstReport
          "sum"   -> succeed SumReport
          "focus" -> succeed FocusReport
                       |> required "value" string
          _       -> fail (v ++ " is not a valid reporting logic")
  in succeed innerDecoder
    |> required "type" string
    |> resolve

decodeBlock : Decoder MethodBlock
decodeBlock =
  succeed MethodBlock
    |> required "id" (map CallId string)
    |> required "component"  string
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
    |> required "method_name" (map MethodId string)
    |> required "parameters"  (list decodeCallParameter )
    |> required "class_context"  (map parseCondition string)
    |> required "component"  string
    |> optional "disableReporting" bool False

decodeTechnique : Decoder Technique
decodeTechnique =
  succeed Technique
    |> required "bundle_name" (map TechniqueId string)
    |> required "version"  string
    |> required "name"  string
    |> required "description"  string
    |> required "category"  string
    |> required "method_calls" (list (lazy (\_ -> decodeMethodElem Nothing)))
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
    |> required "bundle_name" (map MethodId string)
    |> required "name" string
    |> required "description" string
    |> required "class_prefix" string
    |> required "class_parameter" (map ParameterId string)
    |> required "agent_support" (list decodeAgent)
    |> required "parameter" (list decodeMethodParameter)
    |> optional "documentation" (maybe string) Nothing
    |> optional "deprecated" (maybe string) Nothing
    |> optional "rename" (maybe string) Nothing

decodeDeleteTechniqueResponse : Decoder (TechniqueId,String)
decodeDeleteTechniqueResponse =
  succeed Tuple.pair
    |> required "id" (map TechniqueId string)
    |> required "version" string

decodeCategory : Decoder TechniqueCategory
decodeCategory =
  succeed TechniqueCategory
    |> required "path" string
    |> required "name" string

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
