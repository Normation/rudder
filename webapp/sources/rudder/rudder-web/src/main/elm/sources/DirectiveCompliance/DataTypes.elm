module DirectiveCompliance.DataTypes exposing (..)

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

type alias DirectiveCompliance =
  { compliance        : Float
  , policyMode        : String
  , complianceDetails : ComplianceDetails
  , rules             : List (RuleCompliance NodeValueCompliance)
  , nodes             : List NodeCompliance
  }

type alias RuleCompliance value =
  { ruleId            : RuleId
  , name              : String
  , compliance        : Float
  , complianceDetails : ComplianceDetails
  , components        : List (ComponentCompliance value)
  }

type alias NodeValueCompliance =
  { nodeId            : NodeId
  , name              : String
  , policyMode        : String
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
  , compliance : ComplianceFilters
  }

type SortOrder = Asc | Desc

type alias UI =
  { ruleFilters  : TableFilters
  , nodeFilters  : TableFilters
  , viewMode     : ViewMode
  , loading      : Bool
  , loaded       : Bool
  }

type ViewMode = RulesView | NodesView

type alias Model =
  { directiveId : DirectiveId
  , contextPath : String
  , policyMode  : String
  , ui          : UI
  , directiveCompliance : Maybe DirectiveCompliance
  }

type Msg
  = Ignore
  | UpdateFilters       TableFilters
  | UpdateComplianceFilters ComplianceFilters
  | GoTo                String
  | ChangeViewMode      ViewMode
  | ToggleRow           String String
  | ToggleRowSort       String String SortOrder
  | GetPolicyModeResult (Result Error String)
  | GetDirectiveComplianceResult (Result Error DirectiveCompliance)
  | Export (Result Error String)
  | CallApi  (Model -> Cmd Msg)
  | LoadCompliance String

