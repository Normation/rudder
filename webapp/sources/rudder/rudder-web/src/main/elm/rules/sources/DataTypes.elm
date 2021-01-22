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

type alias Tag =
  { key   : String
  , value : String
  }

type alias RuleDetails =
  { id                : String
  , displayName       : String
  , categoryId        : String
  , shortDescription  : String
  , longDescription   : String
  , enabled           : Bool
  , isSystem          : Bool
  , directives        : List String
  , targets           : Targets
  , tags              : List Tag
  }

type alias RuleCompliance =
  { ruleId            : String
  , mode              : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  }

type alias ComplianceDetails =
  { successNotApplicable       : Maybe Float
  , successAlreadyOK           : Maybe Float
  , successRepaired            : Maybe Float
  , error                      : Maybe Float
  , auditCompliant             : Maybe Float
  , auditNonCompliant          : Maybe Float
  , auditError                 : Maybe Float
  , auditNotApplicable         : Maybe Float
  , unexpectedUnknownComponent : Maybe Float
  , unexpectedMissingComponent : Maybe Float
  , noReport                   : Maybe Float
  , reportsDisabled            : Maybe Float
  , applying                   : Maybe Float
  , badPolicyMode              : Maybe Float
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

type alias Techniquex =
  { name     : String
  , versions : List String
  }

type alias RuleUI =
  { newTag : Tag
  }

type RulesTreeItem
  = Category String String (List RulesTreeItem) (List RulesTreeItem)
  | Rule String String String Bool

type GroupsTreeItem
  = GroupCat String String String String (List GroupsTreeItem) (List GroupsTreeItem)
  | Group String String String (List String) Bool Bool

type TechniquesTreeItem
  = TechniqueCat String String (List TechniquesTreeItem) (List TechniquesTreeItem)
  | Technique String -- (List String)

type alias Model =
  { contextPath     : String
  , tab             : TabMenu
  , editDirectives  : Bool
  , editGroups      : Bool
  , selectedRule    : Maybe RuleDetails
  , policyMode      : String
  , rulesTree       : RulesTreeItem
  , techniques      : List Techniquex
  , directives      : List Directive
  , groupsTree      : GroupsTreeItem
  , techniquesTree  : TechniquesTreeItem
  , rulesCompliance : List RuleCompliance
  , ruleUI          : RuleUI
  }

type Msg
  = GetRulesResult          (Result Error RulesTreeItem)
  | ChangeTabFocus TabMenu
  | EditDirectives Bool
  | EditGroups Bool
  | OpenRuleDetails String
  | CloseRuleDetails
  | SelectDirective String
  | SelectGroup String Bool
  | UpdateRuleName String
  | UpdateRuleCategory String
  | UpdateRuleShortDesc String
  | UpdateRuleLongDesc  String
  | UpdateTagKey String
  | UpdateTagVal String
  | AddTag
--| RemoveTag String
  | CallApi                  (Model -> Cmd Msg)
  | GetTechniquesResult      (Result Error (List Techniquex))
  | GetDirectivesResult      (Result Error (List Directive))
  | GetPolicyModeResult      (Result Error String)
  | GetGroupsTreeResult      (Result Error GroupsTreeItem)
  | GetTechniquesTreeResult  (Result Error TechniquesTreeItem)
  | GetRuleDetailsResult     (Result Error RuleDetails)
  | GetRulesComplianceResult (Result Error (List RuleCompliance))
  | SaveRuleDetails          (Result Error RuleDetails)