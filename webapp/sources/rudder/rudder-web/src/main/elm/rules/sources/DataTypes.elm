module DataTypes exposing (..)

import Dict exposing (Dict)
import Http exposing (Error)

--
-- All our data types
--

type TabMenu
  = Information
  | Directives
  | Nodes
  | Groups
  | TechnicalLogs


type alias Tag =
  { key   : String
  , value : String
  }

type ModalState = NoModal | DeletionValidation Rule | DeactivationValidation Rule | DeletionValidationCat (Category Rule)

type RuleTarget = Composition RuleTarget RuleTarget | And (List RuleTarget) | Or (List RuleTarget) | NodeGroupId String | Special String | Node String

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
  , policyMode        : String
  , status            : RuleStatus
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
  , tags             : List Tag
 }

type alias Technique =
  { name       : String
  , directives : List Directive
  }

type alias NodeInfo =
  { id          : String
  , hostname    : String
  , description : String
  , policyMode  : String
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
  , target      : String
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


type alias RuleComplianceGlobal =
  { id                : RuleId
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  }

type alias RuleCompliance =
  { ruleId            : RuleId
  , mode              : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , directives        : List (DirectiveCompliance NodeValueCompliance)
  , nodes             : List NodeCompliance
  }

type alias NodeCompliance =
  { nodeId            : NodeId
  , name              : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , directives        : List (DirectiveCompliance ValueCompliance)
  }

type alias DirectiveCompliance value =
  { directiveId       : DirectiveId
  , name              : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , components        : List (ComponentCompliance value)
  }


type ComponentCompliance value = Block (BlockCompliance value) | Value (ComponentValueCompliance value)
type alias BlockCompliance value =
    { component         : String
    , compliance        : Float
    , complianceDetails : ComplianceDetails
    , components        : List (ComponentCompliance value)
    }


type alias ComponentValueCompliance value =
  { component         : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , values            : List value
  }

type SortOrder = Asc | Desc

type alias NodeValueCompliance =
  { nodeId : NodeId
  , name   : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , values : List ValueCompliance
  }

type alias ValueCompliance =
  { value   : String
  , reports : List Report
  }

type alias Report =
  { status  : String
  , message : Maybe String
  }

type alias RuleStatus =
  { value   : String
  , details : Maybe String
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

type alias RuleDetailsUI = { editDirectives: Bool, editGroups : Bool, newTag : Tag, openedRows : Dict String (String, SortOrder)  }

type alias RuleDetails = { originRule : Maybe Rule, rule : Rule, tab :  TabMenu, ui : RuleDetailsUI, compliance : Maybe RuleCompliance }

type alias CategoryDetails = { originCategory : Maybe (Category Rule), category : Category Rule, parentId : String, tab :  TabMenu}

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
  , sortOrder : SortOrder
  , filter    : String
  , unfolded  : List String
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
  , rulesCompliance : Dict String RuleComplianceGlobal
  , directives      : Dict String Directive
  , nodes           : Dict String NodeInfo
  , ui              : UI
  }

type Msg
  = GenerateId (String -> Msg)
  | OpenRuleDetails RuleId Bool
  | OpenCategoryDetails String Bool
  | CloseDetails
  | SelectGroup String Bool
  | UpdateRuleForm RuleDetails
  | UpdateCategoryForm CategoryDetails
  | NewRule RuleId
  | NewCategory String
  | CallApi                  (Model -> Cmd Msg)
  | GetRuleDetailsResult     (Result Error Rule)
  | GetPolicyModeResult      (Result Error String)
  | GetCategoryDetailsResult (Result Error (Category Rule))
  | GetRulesComplianceResult (Result Error (List RuleComplianceGlobal))
  | GetRuleComplianceResult  RuleId (Result Error RuleCompliance)
  | GetNodesList             (Result Error (List NodeInfo))
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
  | ToggleRow              String String
  | ToggleRowSort          String String SortOrder
  | UpdateRuleFilters      Filters
  | UpdateDirectiveFilters Filters
  | UpdateGroupFilters     Filters
