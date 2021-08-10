module DataTypes exposing (..)

import Http exposing (Error)

--
-- All our data types
--

type TabMenu
  = Information
  | Directives
  | Groups
  | TechnicalLogs


type alias Tag =
  { key   : String
  , value : String
  }

type ModalState = DeletionValidation Rule | DeactivationValidation Rule

type RuleTarget = NodeGroupId String | Composition  RuleTarget RuleTarget | Special String | Node String | And (List RuleTarget) | Or (List RuleTarget)

type alias RuleId = { value : String }
type alias DirectiveId = { value : String }
type alias NodeId = { value : String }

type alias Rule =
  { id                : RuleId
  , name              : String
  , categoryId        : String
  , shortDescription  : String
  , longDescription   : String
  , enabled           : Bool
  , isSystem          : Bool
  , directives        : List DirectiveId
  , targets           : List RuleTarget
  , tags              : List Tag
  }


type alias Directive =
  { id               : DirectiveId
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
  , directives : List Directive
  }

type alias Category a =
 { id : String
 , name : String
  , subElems : SubCategories a
  , elems : List a
 }

type SubCategories a = SubCategories (List (Category a))
type alias Group =
  { id : String
  , name : String
  , description : String
  , nodeIds : List String
  , dynamic : Bool
  , enabled : Bool
  }

getAllElems: Category a -> List a
getAllElems category =
  let
    subElems = case category.subElems of SubCategories l -> l
  in
    List.append category.elems (List.concatMap getAllElems subElems)

getSubElems: Category a -> List (Category a)
getSubElems cat =
  case cat.subElems of
    SubCategories subs -> subs

getAllCats: Category a -> List (Category a)
getAllCats category =
  let
    subElems = case category.subElems of SubCategories l -> l
  in
    category :: (List.concatMap getAllCats subElems)

type alias RuleCompliance =
  { ruleId            : RuleId
  , mode              : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , directives        : List DirectiveCompliance
  }

type alias DirectiveCompliance =
  { directiveId       : DirectiveId
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , components : List ComponentCompliance
  }

type alias ComponentCompliance =
  { component         : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , nodes : List NodeCompliance
  }
type alias ValueCompliance =
  { value   : String
  , reports : List Report

  }

type alias Report = { status : String, message : Maybe String}

type alias NodeCompliance =
  { nodeId            : NodeId
  , values : List ValueCompliance
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

type alias EditRuleDetails = { originRule : Rule, rule : Rule, tab :  TabMenu, editDirectives: Bool, editGroups : Bool, newTag : Tag }

type Mode = Loading | RuleTable | EditRule EditRuleDetails | CreateRule EditRuleDetails

type alias Model =
  { contextPath     : String
  , mode            : Mode
  , policyMode      : String
  , rulesTree       : Category Rule
  , groupsTree      : Category Group
  , techniquesTree  : Category Technique
  , rulesCompliance : List RuleCompliance
  , directives      : List Directive
  , modal           : Maybe ModalState
  }

type Msg
  = ChangeTabFocus TabMenu
  | GenerateId (String -> Msg)
  | EditDirectives Bool
  | EditGroups Bool
  | GetRuleDetailsResult     (Result Error Rule)
  | OpenRuleDetails RuleId
  | CloseRuleDetails
  | SelectGroup RuleTarget Bool
  | UpdateRule Rule
  | NewRule RuleId
  | UpdateNewTag Tag
  | CallApi                  (Model -> Cmd Msg)
  | GetPolicyModeResult      (Result Error String)
  | GetRulesComplianceResult (Result Error (List RuleCompliance))
  | SaveRuleDetails          (Result Error Rule)
  | SaveDisableAction        (Result Error Rule)
  | GetRulesResult           (Result Error (Category Rule))
  | GetGroupsTreeResult      (Result Error (Category Group))
  | GetTechniquesTreeResult  (Result Error ((Category Technique, List Technique)))
  | DeleteRule               (Result Error (RuleId, String))
  | DisableRule
  | CloneRule Rule RuleId
  | OpenDeletionPopup Rule
  | OpenDeactivationPopup Rule
  | ClosePopup Msg
  | Ignore