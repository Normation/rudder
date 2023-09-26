module Compliance.DataTypes exposing (..)

import Dict exposing (Dict)
import Http exposing (Error)


--
-- All our data types
--

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

type alias ComplianceFilters =
  { showComplianceFilters : Bool
  , showOnlyStatus        : Bool
  , selectedStatus        : List String
  }

