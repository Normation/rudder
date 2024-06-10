module GroupRelatedRules.DataTypes exposing (..)

import Http exposing (Error)

import Ui.Datatable exposing (Category)


-- All our data types
--

type alias GroupId      = { value : String }
type alias RuleId       = { value : String }
type alias DirectiveId  = { value : String }
type alias RelatedRules = { value : List RuleId }

type alias Rule =
  { id               : RuleId
  , name             : String
  , categoryId       : String
  , enabled          : Bool
  , tags             : List Tag
  }

type RuleTarget = Composition RuleTarget RuleTarget | And (List RuleTarget) | Or (List RuleTarget) | NodeGroupId String | Special String | Node String

type alias RuleStatus =
  { value   : String
  , details : Maybe String
  }

type alias RulesMeta = 
  { includedRules : List RuleId
  , excludedRules : List RuleId
  }

type alias UI =
  { ruleTreeFilters : Filters
  , isAllCatFold    : Bool
  , loading         : Bool
  }

type alias Tag =
  { key   : String
  , value : String
  }

type alias Filters =
  { filter : String
  , folded : List String
  , newTag : Tag
  , tags   : List Tag
  }

type alias Model =
  { rulesMeta   : RulesMeta
  , contextPath : String
  , ui          : UI
  , ruleTree    : Category Rule
  }

type ComplianceScope = GlobalCompliance | TargetedCompliance

type Msg
  = GoTo String
  | GetRulesResult RelatedRules (Result Error (Category Rule))
  | LoadRelatedRulesTree (List RuleId)
  | UpdateRuleFilters Filters
  | FoldAllCategories Filters
