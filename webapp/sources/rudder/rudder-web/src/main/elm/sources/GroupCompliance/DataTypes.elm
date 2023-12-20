module GroupCompliance.DataTypes exposing (..)

import Dict exposing (Dict)
import Http exposing (Error)

import Compliance.DataTypes exposing (..)
import Rules.DataTypes exposing (RuleCompliance)
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
  , complianceDetails : ComplianceDetails
  , directives        : List (DirectiveCompliance value)
  }

type alias DirectiveCompliance value =
  { directiveId       : DirectiveId
  , name              : String
  , compliance        : Float
  , policyMode        : String
  , complianceDetails : ComplianceDetails
  , components        : List (ComponentCompliance value)
  }

type alias NodeValueCompliance =
  { nodeId            : NodeId
  , name              : String
  , compliance        : Float
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


type alias TableFilters =
  { sortOrder  : SortOrder
  , filter     : String
  , openedRows : Dict String (String, SortOrder)
  }

type SortOrder = Asc | Desc

type alias UI =
  { ruleFilters       : TableFilters
  , nodeFilters       : TableFilters
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
  | UpdateFilters       TableFilters
  | UpdateComplianceFilters ComplianceFilters
  | GoTo                String
  | ChangeViewMode      ViewMode
  | ToggleRow           String String
  | ToggleRowSort       String String SortOrder
  | GetPolicyModeResult (Result Error String)
  | GetGroupComplianceResult (Result Error GroupCompliance)
  --| Export (Result Error String) --TODO: later
  | CallApi  (Model -> Cmd Msg)
  | LoadCompliance ComplianceScope

