module Rules.DataTypes exposing (..)

import Compliance.DataTypes exposing (..)
import Dict exposing (Dict)
import Http exposing (Error)
import Rudder.Table
import Rules.ChangeRequest exposing (ChangeRequest, ChangeRequestSettings)
import Time
import Time.ZonedDateTime exposing (ZonedDateTime)

import Ui.Datatable exposing (TableFilters, SortOrder, Category, getAllElems, getAllCats, getSubElems)


--
-- All our data types
--


type TabMenu
    = Information
    | Directives
    | Nodes
    | Groups
    | TechnicalLogs
    | Rules


type alias Tag =
    { key : String
    , value : String
    }


type ModalState
    = NoModal
    | DeletionValidation Rule (Maybe ChangeRequestSettings)
    | DeactivationValidation Rule (Maybe ChangeRequestSettings)
    | DeletionValidationCat (Category Rule)
    | SaveAuditMsg Bool Rule (Maybe Rule) ChangeRequestSettings


type RuleTarget
    = Composition RuleTarget RuleTarget
    | And (List RuleTarget)
    | Or (List RuleTarget)
    | NodeGroupId String
    | Special String
    | Node String


type alias RuleId =
    { value : String }


type alias DirectiveId =
    { value : String }


type alias NodeId =
    { value : String }


type alias Rule =
    { id : RuleId
    , name : String
    , categoryId : String
    , shortDescription : String
    , longDescription : String
    , enabled : Bool
    , isSystem : Bool
    , directives : List DirectiveId
    , targets : List RuleTarget
    , policyMode : String
    , status : RuleStatus
    , tags : List Tag
    , changeRequestId : Maybe String
    }


type alias Directive =
    { id : DirectiveId
    , displayName : String
    , longDescription : String
    , techniqueName : String
    , techniqueVersion : String
    , enabled : Bool
    , system : Bool
    , policyMode : String
    , tags : List Tag
    }


type alias Technique =
    { name : String
    , directives : List Directive
    }


type alias NodeInfo =
    { id : String
    , hostname : String
    , description : String
    , policyMode : String
    }


type alias Group =
    { id : String
    , name : String
    , description : String
    , nodeIds : List String
    , dynamic : Bool
    , enabled : Bool
    , target : String
    }


-- get all missing categories


getAllMissingCats : Category a -> List (Category a)
getAllMissingCats category =
    let
        missingCategory =
            List.filter (\sub -> sub.id == missingCategoryId) (getSubElems category)
    in
    List.concatMap getAllCats missingCategory



-- get all rules who as an unknown category id


getAllMissingCatsRules : Category a -> List a
getAllMissingCatsRules category =
    let
        missingCategory =
            List.filter (\sub -> sub.id == missingCategoryId) (getSubElems category)
    in
    List.concatMap getAllElems missingCategory


type alias RuleComplianceGlobal =
    { id : RuleId
    , compliance : Float
    , complianceDetails : ComplianceDetails
    }


type alias RuleCompliance =
    { ruleId : RuleId
    , mode : String
    , compliance : Float
    , complianceDetails : ComplianceDetails
    , directives : List (DirectiveCompliance NodeValueCompliance)
    , nodes : List NodeCompliance
    }


type alias NodeCompliance =
    { nodeId : NodeId
    , name : String
    , compliance : Float
    , complianceDetails : ComplianceDetails
    , directives : List (DirectiveCompliance ValueLine)
    }


type alias DirectiveCompliance value =
    { directiveId : DirectiveId
    , name : String
    , compliance : Float
    , complianceDetails : ComplianceDetails
    , skippedDetails : Maybe SkippedDetails
    , components : List (ComponentCompliance value)
    }


type alias NodeValueCompliance =
    { nodeId : NodeId
    , name : String
    , compliance : Float
    , complianceDetails : ComplianceDetails
    , values : List ValueLine
    }


type alias ValueLine =
    { value : String
    , message : String
    , status : String
    }


type alias Report =
    { status : String
    , message : Maybe String
    }


type alias RuleStatus =
    { value : String
    , details : Maybe String
    }


type alias RepairedReport =
    { directiveId : DirectiveId
    , nodeId : NodeId
    , component : String
    , value : String
    , executionTimeStamp : ZonedDateTime
    , executionDate : ZonedDateTime
    , message : String
    }


type alias RuleDetailsUI =
    { editDirectives : Bool, editGroups : Bool, newTag : Tag, openedRows : Dict String ( String, SortOrder ) }


type alias RuleDetails =
    { originRule : Maybe Rule, rule : Rule, tab : TabMenu, ui : RuleDetailsUI, numberOfNodes : Maybe Int, numberOfDirectives : Maybe Int, compliance : Maybe RuleCompliance, reports : List RepairedReport }


type alias RuleNodesDirectives =
    { id : String, numberOfNodes : Int, numberOfDirectives : Int }


type alias CategoryDetails =
    { originCategory : Maybe (Category Rule), category : Category Rule, parentId : String, tab : TabMenu }

type alias RuleWithCompliance =
    { id : RuleId
    , name : String
    , policyMode : String
    , categoryId : String
    , categoryName : String
    , status : RuleStatus
    , compliance : Maybe RuleComplianceGlobal
    , changes : Float
    , tags : List Tag
    }


type Mode
    = Loading
    | RuleTable
    | RuleForm RuleDetails
    | CategoryForm CategoryDetails


type alias TreeFilters =
    { filter : String
    , folded : List String
    , newTag : Tag
    , tags : List Tag
    }

type SortBy
    = Name
    | Parent
    | Status
    | Compliance
    | RuleChanges

type alias Filters =
    { tableFilters : TableFilters SortBy
    , treeFilters : TreeFilters
    }


type alias UI =
    { ruleFilters : Filters
    , directiveFilters : Filters
    , groupFilters : Filters
    , complianceFilters : ComplianceFilters
    , modal : ModalState
    , hasWriteRights : Bool
    , canReadChanqeRequest : Bool
    , loadingRules : Bool
    , isAllCatFold : Bool
    , saving : Bool
    , crSettings : Maybe ChangeRequestSettings
    }


type alias Changes =
    { start : ZonedDateTime
    , end : ZonedDateTime
    , changes : Float
    }



-- this is the Id used by the API to group missing categories with their rules


missingCategoryId =
    "ui-missing-rule-category"


type alias Model =
    { contextPath : String
    , mode : Mode
    , policyMode : String
    , rulesTree : Category Rule
    , groupsTree : Category Group
    , techniquesTree : Category Technique
    , rulesCompliance : Dict String RuleComplianceGlobal
    , changes : Dict String (List Changes)
    , directives : Dict String Directive
    , nodes : Dict String NodeInfo
    , ui : UI
    , rulesTable : Rudder.Table.Model RuleWithCompliance Msg
    , csvExportOptions : Rudder.Table.CsvExportOptions RuleWithCompliance Msg
    }


type Msg
    = Copy String
    | GenerateId (String -> Msg)
    | OpenRuleDetails RuleId Bool
    | OpenCategoryDetails String Bool
    | CloseDetails
    | SelectGroup String Bool
    | UpdateRuleForm RuleDetails
    | UpdateCategoryForm CategoryDetails
    | NewRule RuleId
    | NewCategory String
    | GetRepairedReport RuleId Int
    | CallApi Bool (Model -> Cmd Msg)
    | GetRuleDetailsResult (Result Error Rule)
    | GetPolicyModeResult (Result Error String)
    | GetEnableChangeMsg (Result Error Bool)
    | GetMandatoryMsg (Result Error Bool)
    | GetMsgPrompt (Result Error String)
    | GetEnableCr (Result Error Bool)
    | GetPendingChangeRequests (Result Error (List ChangeRequest))
    | GetCategoryDetailsResult (Result Error (Category Rule))
    | GetRulesComplianceResult (Result Error (List RuleComplianceGlobal))
    | GetRuleNodesDirectivesResult RuleId (Result Error RuleNodesDirectives)
    | GetRuleComplianceResult RuleId (Result Error RuleCompliance)
    | GetNodesList (Result Error (List NodeInfo))
    | SaveRuleDetails Bool (Result Error Rule)
    | SaveDisableAction (Result Error Rule)
    | SaveCategoryResult (Result Error (Category Rule))
    | GetRulesResult (Result Error (Category Rule))
    | GetGroupsTreeResult (Result Error (Category Group))
    | GetRuleChanges (Result Error (Dict String (List Changes)))
    | GetRepairedReportsResult RuleId ZonedDateTime ZonedDateTime (Result Error (List RepairedReport))
    | GetTechniquesTreeResult (Result Error ( Category Technique, List Technique ))
    | DeleteRule (Result Error ( RuleId, String ))
    | DeleteCategory (Result Error ( String, String ))
    | DisableRule
    | CloneRule Rule RuleId
    | OpenDeletionPopup Rule
    | OpenDeletionPopupCat (Category Rule)
    | OpenDeactivationPopup Rule
    | OpenSaveAuditMsgPopup Rule ChangeRequestSettings
    | ClosePopup Msg
    | Ignore
    | ToggleRow String String
    | ToggleRowSort String String SortOrder
    | UpdateRuleFilters Filters
    | UpdateDirectiveFilters Filters
    | UpdateGroupFilters Filters
    | UpdateComplianceFilters ComplianceFilters
    | GoTo String
    | FoldAllCategories Filters
    | RefreshComplianceTable RuleId
    | RefreshReportsTable RuleId
    | UpdateCrSettings ChangeRequestSettings
    | RudderTableMsg (Rudder.Table.Msg Msg)
    | ExportCsvWithCurrentDate Time.Posix
