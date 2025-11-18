module Editor.JsonEncoder exposing (..)

import Iso8601
import Json.Encode exposing (..)
import Json.Encode.Extra exposing (maybe)

import Editor.DataTypes exposing (..)
import Editor.MethodConditions exposing (..)
import Editor.AgentValueParser exposing (..)


encodeDraft: Draft -> Value
encodeDraft draft =
  let
    standardData =  [
                     ("technique", encodeTechnique draft.technique)
                     , ("id", string draft.id.value)
                     , ("date", Iso8601.encode draft.date)
                     ]
    data = case draft.origin of
             Nothing -> standardData
             Just t ->
                     ("origin", encodeTechnique t) :: standardData
  in
    object data

techniqueValues: Technique -> List (String, Value)
techniqueValues technique =
  [ ("id"          , string technique.id.value )
  , ("version"     , string technique.version )
  , ("name"        , string technique.name )
  , ("description" , string technique.description )
  , ("category"    , string technique.category )
  , ("parameters"  , list encodeTechniqueParameters technique.parameters )
  , ("calls"       , list encodeMethodElem technique.elems )
  , ("resources"   , list encodeResource technique.resources )
  , ("documentation", string technique.documentation)
  , ("tags", object (List.map (Tuple.mapSecond string) technique.tags))
  ]

encodeNewTechnique: Technique -> TechniqueId -> Value
encodeNewTechnique technique internalId =
  object ( ("internalId"  , string internalId.value ) :: (techniqueValues technique) )

encodeTechnique: Technique -> Value
encodeTechnique technique =
  object (techniqueValues technique)

encodeResource: Resource -> Value
encodeResource resource =
  object [
    ("path" , string resource.name)
  , ("state", string ( case resource.state of
                         Untouched -> "untouched"
                         New       -> "new"
                         Modified  -> "modified"
                         Deleted   -> "deleted"
                     )
    )
  ]

encodeSelectOption : SelectOption -> Value
encodeSelectOption option =
    object [
      ("value", string option.value)
    , ("name", string (Maybe.withDefault option.value option.name))
    ]


encodeTechniqueConstraint : Constraint -> Value
encodeTechniqueConstraint constraint =
  let
    allowEmpty = constraint.allowEmpty |> Maybe.map ( \v -> [ ("allow_empty_string", bool v) ]) |> Maybe.withDefault []
    allowWp = constraint.allowWhiteSpace |> Maybe.map ( \v -> [ ("allow_whitespace_string", bool v) ]) |> Maybe.withDefault []
    max = constraint.maxLength |> Maybe.map ( \v -> [ ("max_length", int v) ]) |> Maybe.withDefault []
    min = constraint.minLength |> Maybe.map ( \v -> [ ("min_length", int v) ]) |> Maybe.withDefault []
    regex = constraint.matchRegex |> Maybe.map ( \v -> [ ("regex", string v) ]) |> Maybe.withDefault []
    notRegex = constraint.notMatchRegex |> Maybe.map ( \v -> [ ("not_regex", string v) ]) |> Maybe.withDefault []
    select = constraint.select |> Maybe.map ( \v -> [ ("select", list encodeSelectOption v) ]) |> Maybe.withDefault []
  in
    object (List.concat [ allowEmpty, allowWp, max, min, regex, notRegex, select ])


encodeTechniqueParameters: TechniqueParameter -> Value
encodeTechniqueParameters param =
  let
    doc = case param.documentation of
               Nothing -> []
               Just s -> [ ( "documentation", string s)]
    constraint = if (param.constraints == defaultConstraint) then
                   []
                 else
                   [ ("constraints", encodeTechniqueConstraint param.constraints) ]
    desc = case param.description of
               Nothing -> []
               Just s -> [ ( "description", string s)]
    base = [ ("id"         , string param.id.value)
           , ("name"       , string (if (String.isEmpty param.name) then (canonifyString (Maybe.withDefault "" param.description)) else param.name))
           , ("mayBeEmpty" , bool   param.mayBeEmpty)
           ]
  in
    object (List.concat [ base, doc, constraint, desc ] )


appendPolicyMode: Maybe PolicyMode -> List (String, Value) -> Value
appendPolicyMode pm base =
    case pm of
        Nothing -> object base
        Just mode -> object (("policyMode", (encodePolicyMode mode)) :: base)

encodePolicyMode: PolicyMode -> Value
encodePolicyMode policyMode =
    case policyMode of
        Audit -> string "audit"
        Enforce -> string "enforce"
        Default -> string "default"

encodeMethodElem: MethodElem -> Value
encodeMethodElem call =
  case call of
    Block _ b -> encodeMethodBlock b
    Call _ c -> encodeMethodCall c

encodeMethodCall: MethodCall -> Value
encodeMethodCall call =
  appendPolicyMode call.policyMode [
    ("id"           , string call.id.value)
  , ("component"    , string call.component)
  , ("method"  , string call.methodName.value)
  , ("condition",  string <| conditionStr call.condition)
  , ("parameters"   , object (List.map encodeCallParameters call.parameters))
  , ("disabledReporting"   , bool call.disableReporting)
  , ("type", string "call")
  , ("foreachName", maybe string call.foreachName)
  , ("foreach", (maybe (list (dict identity string))) call.foreach)
  ]

encodeCompositionRule: ReportingLogic -> Value
encodeCompositionRule composition =
  case composition of
    (WorstReport WorstReportWeightedSum) ->
       string "worst-case-weighted-sum"
    (WorstReport WorstReportWeightedOne) ->
       string "worst-case-weighted-one"
    (WorstReport FocusWorst) ->
       string "focus-worst"
    WeightedReport ->
      string "weighted"
    FocusReport value ->
      string ("focus:"++value)

encodeMethodBlock: MethodBlock -> Value
encodeMethodBlock call =
  appendPolicyMode call.policyMode [
    ("reportingLogic"  , encodeCompositionRule call.reportingLogic)
  , ("condition",  string <| conditionStr call.condition)
  , ("component"    , string call.component)
  , ("calls"   , list encodeMethodElem call.calls)
  , ("id"   , string call.id.value)
  , ("type", string "block")
  , ("foreachName", maybe string call.foreachName)
  , ("foreach", (maybe (list (dict identity string))) call.foreach)
  ]

encodeCallParameters: CallParameter -> (String, Value)
encodeCallParameters param =

   ( param.id.value , string (displayValue param.value))


encodeExportTechnique: Technique -> Value
encodeExportTechnique technique =
  object [
    ("type"    , string "ncf_technique")
  , ("version" , string "4.0")
  , ("data"    , encodeTechnique technique)
  ]
