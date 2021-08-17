module  JsonEncoder exposing (..)

import DataTypes exposing (..)
import Json.Encode exposing (..)

encodeRuleDetails: Rule -> Value
encodeRuleDetails ruleDetails =
  let
    listTags = object(List.map (\t -> (t.key, string t.value)) ruleDetails.tags)
  in
      object [
        ("id"               , string ruleDetails.id.value               )
      , ("displayName"      , string ruleDetails.name      )
      , ("category"         , string ruleDetails.categoryId       )
      , ("shortDescription" , string ruleDetails.shortDescription )
      , ("longDescription"  , string ruleDetails.longDescription  )
      , ("enabled"          , bool   ruleDetails.enabled          )
      , ("system"           , bool   ruleDetails.isSystem         )
      , ("directives"        , list string (List.map .value ruleDetails.directives)  )
      , ("targets"          , list encodeTargets ruleDetails.targets   )
      , ("tags"             , list encodeTags ruleDetails.tags    )
      ]

encodeCategoryDetails: (Category Rule) -> Value
encodeCategoryDetails category =
  object [
    ("name"        , string category.name        )
  , ("description" , string category.description )
  ]

encodeDirectives: Directive -> Value
encodeDirectives directive=
  object [
    ("id"               , string directive.id.value)
  , ("displayName"      , string directive.displayName)
  , ("longDescription"  , string directive.longDescription)
  , ("techniqueName"    , string directive.techniqueName)
  , ("techniqueVersion" , string directive.techniqueVersion)
  , ("enabled"          , bool directive.enabled)
  , ("system"           , bool directive.system)
  , ("policyMode"       , string directive.policyMode)
  ]

encodeTargets : RuleTarget -> Value
encodeTargets target =
  case target of
    NodeGroupId id -> string ("group:" ++ id)
    Special spe -> string spe
    Node id -> string ("node:"++id)
    Composition include exclude ->
      object [
        ("include", encodeTargets include)
      , ("exclude", encodeTargets exclude)
      ]
    Or t ->
      object [
        ("or", list encodeTargets t)
      ]
    And t ->
      object [
        ("and", list encodeTargets t)
      ]

encodeTags : Tag -> Value
encodeTags tag =
  object [
    (tag.key, string tag.value)
  ]
