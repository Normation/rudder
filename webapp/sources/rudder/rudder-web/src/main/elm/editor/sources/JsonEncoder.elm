module  JsonEncoder exposing (..)

import DataTypes exposing (..)
import Json.Encode exposing (..)
import MethodConditions exposing (..)

encodeTechnique: Technique -> Value
encodeTechnique technique =
  object [
    ("bundle_name" , string technique.id.value )
  , ("version"     , string technique.version )
  , ("name"        , string technique.name )
  , ("description" , string technique.description )
  , ("category"    , string technique.category )
  , ("parameter"   , list encodeTechniqueParameters technique.parameters )
  , ("method_calls", list encodeMethodCall technique.calls )
  , ("resources"   , list encodeResource technique.resources )
  ]

encodeResource: Resource -> Value
encodeResource resource =
  object [
    ("name" , string resource.name)
  , ("state", string ( case resource.state of
                         Unchanged -> "unchanged"
                         New       -> "new"
                         Modified  -> "modified"
                         Deleted   -> "deleted"
                     )
    )
  ]

encodeTechniqueParameters: TechniqueParameter -> Value
encodeTechniqueParameters param =
  object [
    ("id"         , string param.id.value)
  , ("name"       , string param.name)
  , ("description", string param.description)
  ]


encodeMethodCall: MethodCall -> Value
encodeMethodCall call =
  object [
    ("id"           , string call.id.value)
  , ("method_name"  , string call.methodName.value)
  , ("class_context",  string <| conditionStr call.condition)
  , ("component"    , string call.component)
  , ("parameters"   , list encodeCallParameters call.parameters)
  ]

encodeCallParameters: CallParameter -> Value
encodeCallParameters param =
  object [
    ("name" , string param.id.value)
  , ("value", string param.value)
  ]

encodeExportTechnique: Technique -> Value
encodeExportTechnique technique =
  object [
    ("type"    , string "ncf_technique")
  , ("version" , string "3.0")
  , ("data"    , encodeTechnique technique)
  ]
