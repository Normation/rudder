module DataTypes exposing (..)

import Http exposing (Error)

type TabMenu
  = Information
  | Directives
  | Groups
  | TechnicalLogs

type alias Targets =
  { include : List String
  , exclude : List String
  }

type alias RuleDetails =
  { id               : String
  , displayName      : String
  , categoryId       : String
  , shortDescription : String
  , longDescription  : String
  , enabled          : Bool
  , isSystem         : Bool
  , directives       : List String
  , targets          : Targets
  }

type alias RuleCompliance =
  { ruleId           : String
  , mode             : String
  , compliance       : Float
  }

type alias Directive =
  { id               : String
  , displayName      : String
  , longDescription  : String
  , techniqueName    : String
  , techniqueVersion : String
  , enabled          : Bool
  , system           : Bool
  , policyMode       : String
  }

type alias Technique =
  { name     : String
  , versions : List String
  }

type GroupsTreeItem
  = GroupCat String String String String (List GroupsTreeItem) (List GroupsTreeItem)
  | Group String String String (List String) Bool Bool

type RulesTreeItem
  = Category String String (List RulesTreeItem) (List RulesTreeItem)
  | Rule String String String Bool

type alias Model =
  { contextPath     : String
  , tab             : TabMenu
  , editDirectives  : Bool
  , editGroups      : Bool
  , selectedRule    : Maybe RuleDetails
  , policyMode      : String
  , rulesTree       : RulesTreeItem
  , techniques      : List Technique
  , directives      : List Directive
  , groupsTree      : GroupsTreeItem
  , rulesCompliance : List RuleCompliance
  }

type Msg
  = GetRulesResult       (Result Error RulesTreeItem)
  | GetTechniquesResult  (Result Error (List Technique))
  | GetDirectivesResult  (Result Error (List Directive))
  | GetPolicyModeResult  (Result Error String)
  | GetGroupsTreeResult  (Result Error GroupsTreeItem)
  | CallApi (Model -> Cmd Msg)
  | ChangeTabFocus TabMenu
  | EditDirectives Bool
  | EditGroups Bool
  | GetRuleDetailsResult     (Result Error RuleDetails)
  | OpenRuleDetails String
  | CloseRuleDetails