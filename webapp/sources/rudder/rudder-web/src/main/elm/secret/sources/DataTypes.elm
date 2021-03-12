module DataTypes exposing(..)

import Http exposing (Error)
import Toasty
import Toasty.Defaults

type alias Secret =
  { name  : String
  , value : String
  }

type alias Model =
  { contextPath    : String
  , secrets        : List Secret
  , focusOn : Maybe Secret
  , newSecretInput : Maybe Secret
  , isOpenCreateModal : Bool
  , isOpenEditModal : Bool
  , stateInputs : List StateInput
  , openedDescription : List String
  }

type WriteAction
  = Edit
  | Add

type StateInput
    = InvalidName
    | InvalidValue
    | InvalidDescription

type Msg
  = GetAllSecrets (Result Error (List Secret))
  | GetSecret (Result Error Secret)
  | AddSecret (Result Error Secret)
  | DeleteSecret (Result Error String)
  | UpdateSecret (Result Error Secret)
  | CallApi (Model -> Cmd Msg)
  | OpenCreateModal
  | OpenEditModal Secret
  | CloseModal
  | InputName String
  | InputValue String
  | InputDescription String
  | SubmitSecret WriteAction
  | OpenDescription Secret
    --
    --  -- NOTIFICATIONS
    --| ToastyMsg (Toasty.Msg Toasty.Defaults.Toast)
    --| Notification (Toasty.Msg Toasty.Defaults.Toast)