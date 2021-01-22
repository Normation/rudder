module  JsonEncoder exposing (..)

import DataTypes exposing (..)
import Json.Encode exposing (..)

encodeRuleDetails: RuleDetails -> Value
encodeRuleDetails ruleDetails =
  let
    listTags = object(List.map (\t -> (t.key, string t.value)) ruleDetails.tags)
  in
      object [
        ("id"               , string ruleDetails.id               )
      , ("displayName"      , string ruleDetails.displayName      )
      , ("category"         , string ruleDetails.categoryId       )
      , ("shortDescription" , string ruleDetails.shortDescription )
      , ("longDescription"  , string ruleDetails.longDescription  )
      , ("enabled"          , bool   ruleDetails.enabled          )
      , ("system"           , bool   ruleDetails.isSystem         )
      , ("directves"        , list string ruleDetails.directives  )
      , ("targets"          , list encodeTargets [ruleDetails.targets]   )
      , ("tags"             , list encodeTags ruleDetails.tags    )
      ]

encodeDirectives: Directive -> Value
encodeDirectives directive=
  object [
    ("id"               , string directive.id)
  , ("displayName"      , string directive.displayName)
  , ("longDescription"  , string directive.longDescription)
  , ("techniqueName"    , string directive.techniqueName)
  , ("techniqueVersion" , string directive.techniqueVersion)
  , ("enabled"          , bool directive.enabled)
  , ("system"           , bool directive.system)
  , ("policyMode"       , string directive.policyMode)
  ]

encodeTargets : Targets -> Value
encodeTargets targets =
  object [
    ("include", encodeTargetsIncludeExclude targets.include)
  , ("exclude", encodeTargetsIncludeExclude targets.exclude)
  ]

encodeTags : Tag -> Value
encodeTags tag =
  object [
    (tag.key, string tag.value)
  ]

encodeTargetsIncludeExclude : List String -> Value
encodeTargetsIncludeExclude targets =
  let
    tt = List.map (\t -> "group:"++t) targets
  in
  object [
    ("or", list string tt)
  ]

