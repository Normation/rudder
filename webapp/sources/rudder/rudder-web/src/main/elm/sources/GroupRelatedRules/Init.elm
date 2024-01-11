module GroupRelatedRules.Init exposing (..)

import GroupRelatedRules.ApiCalls exposing (..)
import GroupRelatedRules.DataTypes exposing (..)
import GroupRelatedRules.ViewUtils exposing (emptyCategory)


init : { contextPath : String, includedRules : List String, excludedRules : List String} -> ( Model, Cmd Msg )
init flags =
  let
    initFilters   = Filters "" [] (Tag "" "") []
    initUI        = UI initFilters False True
    initRulesMeta = (RulesMeta (List.map RuleId flags.includedRules) (List.map RuleId flags.excludedRules))
    initModel     = Model initRulesMeta flags.contextPath initUI emptyCategory
  in
    ( initModel
    , Cmd.none
    )