module NodeCompliance.DataTypes exposing (..)

import Dict exposing (Dict)
import Http exposing (Error)

import Compliance.DataTypes exposing (..)
import Rules.DataTypes exposing (Rule, Directive)

--
-- All our data types
--

type alias RuleId      = { value : String }
type alias DirectiveId = { value : String }
type alias NodeId      = { value : String }


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
  , components        : List (ComponentCompliance value)
  }

type alias TableFilters =
  { sortOrder  : SortOrder
  , filter     : String
  , openedRows : Dict String (String, SortOrder)
  }

type SortOrder = Asc | Desc

type alias UI =
  { tableFilters      : TableFilters
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
  | UpdateFilters       TableFilters
  | UpdateComplianceFilters ComplianceFilters
  | GoTo                String
  | ToggleRow           String String
  | ToggleRowSort       String String SortOrder
  | GetPolicyModeResult (Result Error String)
  | GetNodeComplianceResult (Result Error NodeCompliance)
  | CallApi            (Model -> Cmd Msg)
  | Refresh

