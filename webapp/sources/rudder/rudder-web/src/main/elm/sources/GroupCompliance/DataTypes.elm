module GroupCompliance.DataTypes exposing (..)

import Http exposing (Error)

import Compliance.DataTypes exposing (..)
import Rules.DataTypes exposing (RuleCompliance)
import Ui.Datatable exposing (TableFilters, SortOrder)


--
-- All our data types
--

type alias RuleId      = { value : String }
type alias DirectiveId = { value : String }
type alias NodeId      = { value : String }
type alias GroupId = { value : String }

type alias GroupCompliance =
  { compliance        : Float
  , complianceDetails : ComplianceDetails
  , rules             : List (RuleCompliance NodeValueCompliance)
  , nodes             : List NodeCompliance
  }

type alias RuleCompliance value =
  { ruleId            : RuleId
  , name              : String
  , compliance        : Float
  , policyMode        : String
  , complianceDetails : ComplianceDetails
  , directives        : List (DirectiveCompliance value)
  }

type alias DirectiveCompliance value =
  { directiveId       : DirectiveId
  , name              : String
  , compliance        : Float
  , policyMode        : String
  , complianceDetails : ComplianceDetails
  , skippedDetails    : Maybe SkippedDetails
  , components        : List (ComponentCompliance value)
  }

type alias NodeValueCompliance =
  { nodeId            : NodeId
  , name              : String
  , compliance        : Float
  , policyMode        : String
  , complianceDetails : ComplianceDetails
  , values : List ValueCompliance
  }

type alias NodeCompliance =
  { nodeId            : NodeId
  , name              : String
  , compliance        : Float
  , policyMode        : String
  , complianceDetails : ComplianceDetails
  , rules             : List (RuleCompliance ValueCompliance)
  }

type SortBy = Name

type alias UI =
  { ruleFilters       : TableFilters SortBy
  , nodeFilters       : TableFilters SortBy
  , complianceFilters : ComplianceFilters
  , viewMode          : ViewMode
  , loading           : Bool
  , loaded            : Bool
  }

type ViewMode = RulesView | NodesView

type alias Model =
  { groupId : GroupId
  , contextPath : String
  , policyMode  : String
  , ui          : UI
  , groupCompliance : Maybe GroupCompliance
  , complianceScope : ComplianceScope
  }

type ComplianceScope = GlobalCompliance | TargetedCompliance

type Msg
  = Ignore
  | UpdateFilters       (TableFilters SortBy)
  | UpdateComplianceFilters ComplianceFilters
  | GoTo                String
  | ChangeViewMode      ViewMode
  | ToggleRow           String String
  | ToggleRowSort       String String SortOrder
  | GetPolicyModeResult (Result Error String)
  | GetGroupComplianceResult (Result Error GroupCompliance)
  --| Export (Result Error String) --TODO: later
  | RefreshCompliance ComplianceScope
  | CallApi  (Model -> Cmd Msg)
  | LoadCompliance ComplianceScope

