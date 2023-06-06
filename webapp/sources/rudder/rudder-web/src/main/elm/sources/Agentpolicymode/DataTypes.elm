module Agentpolicymode.DataTypes exposing (..)

import Http exposing (Error)

--
-- All our data types
--

type alias UI =
  { hasWriteRights : Bool
  , form           : Form
  , saving         : Bool
  }

type PolicyMode
  = Default
  | Audit
  | Enforce
  | None

type Form
  = NodeForm String
  | GlobalForm

type alias Settings =
  { policyMode  : PolicyMode
  , overridable : Bool
  }

type alias Model =
  { contextPath      : String
  , globalPolicyMode : PolicyMode
  , currentSettings  : Settings
  , selectedSettings : Settings
  , ui               : UI
  }

type Msg
  = Ignore
  | GetGlobalPolicyModeResult (Result Error PolicyMode)
  | GetPolicyModeOverridableResult (Result Error Bool)
  | GetNodePolicyModeResult (Result Error Settings)
  | StartSaving
  | SelectMode PolicyMode
  | SelectOverrideMode Bool
  | SaveChanges (Result Error Settings)

