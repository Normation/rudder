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

type ModalState = NoModal | DeletionValidation Rule | DeactivationValidation Rule | DeletionValidationCat (Category Rule)

type RuleTarget = NodeGroupId String | Composition  RuleTarget RuleTarget | Special String | Node String | And (List RuleTarget) | Or (List RuleTarget)

type alias RuleId      = { value : String }
type alias DirectiveId = { value : String }
type alias NodeId      = { value : String }

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
  { name       : String
  , directives : List Directive
  }

type alias Category a =
  { id          : String
  , name        : String
  , description : String
  , subElems    : SubCategories a
  , elems       : List a
  }

type SubCategories a = SubCategories (List (Category a))

type alias Group =
  { id          : String
  , name        : String
  , description : String
  , nodeIds     : List String
  , dynamic     : Bool
  , enabled     : Bool
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
  , components        : List ComponentCompliance
  }

type alias ComponentCompliance =
  { component         : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , nodes             : List NodeCompliance
  }

type alias ValueCompliance =
  { value   : String
  , reports : List Report
  }

type alias Report =
  { status  : String
  , message : Maybe String
  }

type alias NodeCompliance =
  { nodeId : NodeId
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

type alias RuleDetailsUI = { editDirectives: Bool, editGroups : Bool, newTag : Tag }

type alias RuleDetails = { originRule : Maybe Rule, rule : Rule, tab :  TabMenu, ui : RuleDetailsUI }

type alias CategoryDetails = { originCategory : Maybe (Category Rule), category : Category Rule, tab :  TabMenu}

type Mode
  = Loading
  | RuleTable
  | RuleForm RuleDetails
  | CategoryForm CategoryDetails

type SortBy
  = Name
  | Parent
  | Status
  | Compliance

type alias TableFilters =
  { sortBy    : SortBy
  , sortOrder : Bool
  , filter    : String
  }

type alias TreeFilters =
  { filter : String
  , folded : List String
  }

type alias Filters =
  { tableFilters : TableFilters
  , treeFilters  : TreeFilters
  }

type alias UI =
  { ruleFilters      : Filters
  , directiveFilters : Filters
  , groupFilters     : Filters
  , modal            : ModalState
  , hasWriteRights   : Bool
  }

type alias Model =
  { contextPath     : String
  , mode            : Mode
  , policyMode      : String
  , rulesTree       : Category Rule
  , groupsTree      : Category Group
  , techniquesTree  : Category Technique
  , rulesCompliance : List RuleCompliance
  , directives      : List Directive
  , ui              : UI
  }

type Msg
  = GenerateId (String -> Msg)
  | OpenRuleDetails RuleId Bool
  | OpenCategoryDetails String Bool
  | CloseDetails
  | SelectGroup RuleTarget Bool
  | UpdateRuleForm RuleDetails
  | UpdateCategoryForm CategoryDetails
  | NewRule RuleId
  | NewCategory String
  | CallApi                  (Model -> Cmd Msg)
  | GetRuleDetailsResult     (Result Error Rule)
  | GetPolicyModeResult      (Result Error String)
  | GetCategoryDetailsResult (Result Error (Category Rule))
  | GetRulesComplianceResult (Result Error (List RuleCompliance))
  | SaveRuleDetails          (Result Error Rule)
  | SaveDisableAction        (Result Error Rule)
  | SaveCategoryResult       (Result Error (Category Rule))
  | GetRulesResult           (Result Error (Category Rule))
  | GetGroupsTreeResult      (Result Error (Category Group))
  | GetTechniquesTreeResult  (Result Error ((Category Technique, List Technique)))
  | DeleteRule               (Result Error (RuleId, String))
  | DeleteCategory           (Result Error (String, String))
  | DisableRule
  | CloneRule Rule RuleId
  | OpenDeletionPopup Rule
  | OpenDeletionPopupCat (Category Rule)
  | OpenDeactivationPopup Rule
  | ClosePopup Msg
  | Ignore
  | UpdateRuleFilters      Filters
  | UpdateDirectiveFilters Filters
  | UpdateGroupFilters     Filters
