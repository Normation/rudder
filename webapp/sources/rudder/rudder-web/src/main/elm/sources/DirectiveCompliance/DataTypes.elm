module DirectiveCompliance.DataTypes exposing (..)

import Dict exposing (Dict)
import Http exposing (Error)

--
-- All our data types
--

type alias RuleId      = { value : String }
type alias DirectiveId = { value : String }
type alias NodeId      = { value : String }



type alias RuleCompliance value =
  { ruleId            : RuleId
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

type alias ValueCompliance =
  { value   : String
  , reports : List Report
  }

type alias Report =
  { status  : String
  , message : Maybe String
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

type alias DirectiveCompliance =
  { compliance        : Float
  , policyMode : String
  , complianceDetails : ComplianceDetails
  , rules : List (RuleCompliance NodeValueCompliance)
  , nodes : List NodeCompliance
  }
type alias TableFilters =
  { sortOrder : SortOrder
  , filter    : String
  , openedRows : Dict String (String, SortOrder)
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
  | GoTo                String
  | ChangeViewMode      ViewMode
  | ToggleRow           String String
  | ToggleRowSort       String String SortOrder
  | GetPolicyModeResult (Result Error String)
  | GetDirectiveComplianceResult (Result Error DirectiveCompliance)
  | Export (Result Error String)
  | CallApi  (Model -> Cmd Msg)
  | LoadCompliance String

