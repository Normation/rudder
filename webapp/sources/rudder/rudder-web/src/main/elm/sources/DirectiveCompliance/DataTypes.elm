module DirectiveCompliance.DataTypes exposing (..)

import Compliance.DataTypes exposing (..)
import Date
import Dict exposing (Dict)
import Http exposing (Error)
import Rules.DataTypes exposing (RuleCompliance)
import Ui.Datatable exposing (SortOrder, TableFilters)



--
-- All our data types
--


type alias RuleId =
    { value : String }


type alias DirectiveId =
    { value : String }


type alias NodeId =
    { value : String }


type alias DirectiveCompliance =
    { compliance : Float
    , policyMode : String
    , complianceDetails : ComplianceDetails
    , rules : List (RuleCompliance NodeValueCompliance)
    , nodes : List NodeCompliance
    }


type alias RuleCompliance value =
    { ruleId : RuleId
    , name : String
    , compliance : Float
    , policyMode : String
    , complianceDetails : ComplianceDetails
    , skippedDetails : Maybe SkippedDetails
    , components : List (ComponentCompliance value)
    }


type alias NodeValueCompliance =
    { nodeId : NodeId
    , name : String
    , policyMode : String
    , compliance : Float
    , complianceDetails : ComplianceDetails
    , values : List ValueCompliance
    }


type alias NodeCompliance =
    { nodeId : NodeId
    , name : String
    , compliance : Float
    , policyMode : String
    , complianceDetails : ComplianceDetails
    , rules : List (RuleCompliance ValueCompliance)
    }


type SortBy
    = Name


type alias UI =
    { ruleFilters : TableFilters SortBy
    , nodeFilters : TableFilters SortBy
    , complianceFilters : ComplianceFilters
    , viewMode : ViewMode
    , loading : Bool
    , loaded : Bool
    }


type ViewMode
    = RulesView
    | NodesView


type alias Model =
    { directiveId : DirectiveId
    , contextPath : String
    , policyMode : String
    , ui : UI
    , directiveCompliance : Maybe DirectiveCompliance
    }


type Msg
    = Ignore
    | UpdateFilters (TableFilters SortBy)
    | UpdateComplianceFilters ComplianceFilters
    | GoTo String
    | ChangeViewMode ViewMode
    | ToggleRow String String
    | ToggleRowSort String String SortOrder
    | GetPolicyModeResult (Result Error String)
    | GetDirectiveComplianceResult (Result Error DirectiveCompliance)
    | CallApi (Model -> Cmd Msg)
    | LoadCompliance String
    | ExportDirectiveComplianceByNode DirectiveId Date.Date
    | ExportDirectiveComplianceByRule DirectiveId Date.Date
    | DirectiveComplianceCsvExported String (Result Error String)
