module NodeCompliance.DataTypes exposing (..)

import Http exposing (Error)

import Compliance.DataTypes exposing (..)
import Ui.Datatable exposing (TableFilters, SortOrder)


--
-- All our data types
--

type alias RuleId      = { value : String }
type alias DirectiveId = { value : String }
type alias NodeId      = { value : String }

type SortBy = Name

type alias NodeCompliance =
  { nodeId            : NodeId
  , name              : String
  , compliance        : Float
  , policyMode        : String
  , complianceDetails : ComplianceDetails
  , rules             : List RuleCompliance
  }

type alias RuleCompliance =
  { ruleId            : RuleId
  , name              : String
  , compliance        : Float
  , policyMode        : String
  , complianceDetails : ComplianceDetails
  , directives        : List (DirectiveCompliance ValueCompliance)
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


type alias UI =
  { tableFilters      : (TableFilters SortBy)
  , complianceFilters : ComplianceFilters
  , loading           : Bool
  }

type alias Model =
  { nodeId         : NodeId
  , contextPath    : String
  , policyMode     : String
  , ui             : UI
  , nodeCompliance : Maybe NodeCompliance
  , onlySystem     : Bool
  }

type Msg
  = Ignore
  | UpdateFilters       (TableFilters SortBy)
  | UpdateComplianceFilters ComplianceFilters
  | GoTo                String
  | ToggleRow           String String
  | ToggleRowSort       String String SortOrder
  | GetPolicyModeResult (Result Error String)
  | GetNodeComplianceResult (Result Error NodeCompliance)
  | CallApi            (Model -> Cmd Msg)
  | Refresh

