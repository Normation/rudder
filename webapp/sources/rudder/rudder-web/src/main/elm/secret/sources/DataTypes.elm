module DataTypes exposing(..)

import Http exposing (Error)

type alias Secret =
  { info  : SecretInfo
  , value : String
  }

type alias SecretInfo =
  { name        : String
  , description : String
  }


type alias Model =
  { contextPath       : String
  , secrets           : List SecretInfo
  , focusOn           : Maybe SecretInfo
  , newSecretInput    : Maybe Secret
  , openModalMode     : Mode
  , stateInputs       : List StateInput
  , openedDescription : List String
  , sortOn            : (Sorting, Column)
  , filteredSecrets   : Maybe (List SecretInfo)
  }

type Mode
  = Edit
  | Add
  | Delete
  | Read

type StateInput
  = EmptyInputName
  | EmptyInputValue
  | EmptyInputDescription

type Sorting
  = ASC
  | DESC
  | NONE

type Column
  = Name
  | Description

type Msg
  = GetAllSecrets (Result Error (List SecretInfo))
  | GetSecret (Result Error (List SecretInfo))
  | AddSecret (Result Error (List SecretInfo))
  | DeleteSecret (Result Error (List SecretInfo))
  | UpdateSecret (Result Error (List SecretInfo))
  | CallApi (Model -> Cmd Msg)
  | OpenModal Mode (Maybe SecretInfo)
  | CloseModal
  | InputName String
  | InputValue String
  | InputDescription String
  | SubmitSecret Mode
  | OpenDescription SecretInfo
  | ChangeSorting Column
  | FilterSecrets String
