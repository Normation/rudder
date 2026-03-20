module Agentpolicymode.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed as Detailed

--
-- All our data types
--

type alias UI =
  { hasWriteRights : Bool
  , form : Form
  , modalState : ModalState
  , modalSettings : Maybe ModalSettings
  , saving : Bool
  }

type alias Reason = String

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

type alias ModalSettings =
  { prompt : String
  , isMandatory: Bool
  }

type ModalState
  = NoModal
  | ConfirmModal { value : String, error : Maybe String } ModalSettings

{- partial duplicate of Rules.ChangeRequest : decoding model -}
type alias ChangeMessageSettings =
  { enableChangeMessage    : Bool
  , mandatoryChangeMessage : Bool
  , changeMessagePrompt    : String
  }

type Msg
  = Ignore
  | GetGlobalPolicyModeResult (Result (Detailed.Error String) PolicyMode)
  | GetPolicyModeOverridableResult (Result (Detailed.Error String) Bool)
  | GetNodePolicyModeResult (Result (Detailed.Error String) Settings)
  | GetChangeMessageSettings (Result (Detailed.Error String) ChangeMessageSettings)
  | CloseModal
  | OpenModal ModalSettings
  | UpdateChangeMessage String
  | StartSaving (Maybe Reason)
  | SelectMode PolicyMode
  | SelectOverrideMode Bool
  | SaveChanges (Result (Detailed.Error String) Settings)

