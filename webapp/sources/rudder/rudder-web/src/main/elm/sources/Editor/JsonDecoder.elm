module Editor.JsonDecoder exposing (..)

import Either exposing (Either)
import Either.Decode
import Iso8601
import Json.Decode exposing (..)
import Json.Decode.Pipeline exposing (..)
import List exposing (drop, head)
import String exposing (join, split)
import Dict
import Editor.AgentValueParser exposing (..)
import Editor.DataTypes exposing (..)
import Editor.MethodConditions exposing (..)


headList : Decoder (List a) -> Decoder a
headList decoder =
  andThen ( \l ->
    case l of
      [] -> fail ""
      x :: _ -> succeed x
    ) decoder

decodeTechniqueParameter : Decoder TechniqueParameter
decodeTechniqueParameter =
  succeed TechniqueParameter
    |> required "id" (map ParameterId string)
    |> required "name" string
    |> optional "description" (maybe string) Nothing
    |> optional "documentation" (maybe string) Nothing
    |> optional "mayBeEmpty" bool False
    |> optional "constraints" decodeConstraint defaultConstraint

decodeCallParameter : List (String, List AgentValue) -> List CallParameter
decodeCallParameter params =
  List.map (\ (k,v) -> CallParameter (ParameterId k) v) params

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
          "focus-worst"             -> succeed (WorstReport FocusWorst)
          "weighted"                -> succeed WeightedReport
          "focus"                   -> succeed (FocusReport "")
          value                     ->
            if (String.startsWith "focus:" value)
            then
              let
                focusValue = String.split ":" value |> List.tail |> Maybe.withDefault [] |> String.join ""
              in
                succeed (FocusReport focusValue)
            else fail (v ++ " is not a valid reporting logic")
  in  string
    |> andThen innerDecoder

decodePolicyMode : Decoder PolicyMode
decodePolicyMode =
  andThen (\s -> case s of
                   "audit" -> succeed Audit
                   "enforce" -> succeed Enforce
                   _ -> fail ""
          ) string

decodeBlock : Decoder MethodBlock
decodeBlock =
  succeed MethodBlock
    |> required "id" (map CallId string)
    |> optional "component"  string ""
    |> required "condition"  (map parseCondition string)
    |> required "reportingLogic" decodeCompositionRule
    |> required "calls" (list  (lazy (\_ -> decodeMethodElem Nothing)))
    |> optional "policyMode" (maybe decodePolicyMode) Nothing
    |> optional "foreachName" (maybe string) Nothing
    |> optional "foreach" (maybe (list (dict string))) Nothing
    >> map (\block ->  { block | calls = List.map (\x ->
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
    |> required "parameters"  (map decodeCallParameter ( keyValuePairs (map getAgentValue string) ))
    |> required "condition"  (map parseCondition string)
    |> optional "component"  string ""
    |> optional "disabledReporting" bool False
    |> optional "policyMode" (maybe decodePolicyMode) Nothing
    |> optional "foreachName" (maybe string) Nothing
    |> optional "foreach" (maybe (list (dict string))) Nothing

decodeTechniqueMaybe : Decoder (Maybe (Either TechniqueError Technique))
decodeTechniqueMaybe =
   field "source" string |>
     andThen (\v ->
       case v of
         "editor" ->  map Just decodeTechniqueOrError
         _ -> succeed Nothing
     )

decodeOutput : Decoder CompilationOutput
decodeOutput =
  succeed CompilationOutput
    |> required "compiler" string
    |> required "resultCode"  int
    |> required "msg"  string
    |> required "stdout"  string
    |> required "stderr"  string

decodeTechniqueError : Decoder TechniqueError
decodeTechniqueError =
  succeed TechniqueError
    |> required "id" (map TechniqueId string)
    |> required "version" string
    |> optional "category" (maybe string) Nothing
    |> required "errorMsg" string
    |> required "errorPath" string

decodeTechniqueOrError : Decoder (Either TechniqueError Technique)
decodeTechniqueOrError =
  Either.Decode.either
    decodeTechniqueError
    decodeTechnique

decodeTechnique : Decoder Technique
decodeTechnique =
  succeed Technique
    |> required "id" (map TechniqueId string)
    |> required "version"  string
    |> required "name"  string
    |> required "description"  string
    |> required "documentation"  string
    |> required "category"  string
    |> required "calls" (list (lazy (\_ -> decodeMethodElem Nothing)))
    |> required "parameters" (list decodeTechniqueParameter)
    |> required "resources" (list decodeResource)
    |> required "tags" (keyValuePairs string)
    |> optional "output" (decodeOutput |> map Just) Nothing

decodeAgent : Decoder Agent
decodeAgent =
  andThen (\v ->
    case v of
      "cfengine-community" -> succeed Cfengine
      "dsc"      -> succeed Dsc
      _          -> fail (v ++ " is not a valid agent")
  ) string

decoderSelectOption : Decoder SelectOption
decoderSelectOption =
  oneOf [ map (\s ->SelectOption s Nothing) string
  , succeed SelectOption
      |> required "value" string
      |> optional "name" (maybe string) Nothing
  ]

decodeConstraint: Decoder Constraint
decodeConstraint =
  succeed Constraint
    |> optional  "allow_empty_string" (maybe bool) Nothing
    |> optional  "allow_whitespace_string" (maybe bool) Nothing
    |> optional  "max_length" (maybe int) Nothing
    |> optional  "min_length" (maybe int) Nothing
    |> optional  "regex" (maybe string) Nothing
    |> optional  "not_regex" (maybe string) Nothing
    |> optional  "select" (maybe (list decoderSelectOption)) Nothing

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
    |> required "path" string
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
    |> required "id"  (map DraftId string)
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

decodePolicyModeDirective : Decoder PolicyMode
decodePolicyModeDirective =
  andThen (\s -> case s of
                   "audit" -> succeed Audit
                   "enforce" -> succeed Enforce
                   "default" ->  succeed Default
                   _ -> fail ""
          ) string

decodeDirective : Decoder Directive
decodeDirective =
  succeed Directive
    |> required "id" (map DirectiveId string)
    |> required "displayName" string
    |> required "shortDescription" string
    |> required "longDescription" string
    |> required "techniqueName" string
    |> required "techniqueVersion" string
    |> required "priority" int
    |> required "enabled" bool
    |> required "system" bool
    |> required "policyMode" decodePolicyModeDirective
    --|> required "tags" (keyValuePairs string)