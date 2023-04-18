module Accounts.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed
import Json.Decode as D exposing (..)
import SingleDatePicker exposing (Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings, DatePicker)
import Time exposing (Posix, Zone)

--
-- All our data types
--

type ModalState = NoModal | NewAccount | EditAccount Account | Confirm ConfirmModalType String Msg

type ConfirmModalType
  = Delete
  | Regenerate

type SortOrder = Asc | Desc

type SortBy
  = Name
  | Token
  | ExpDate

type alias TableFilters =
  { sortBy    : SortBy
  , sortOrder : SortOrder
  , filter    : String
  , authType  : String
  }

type alias DatePickerInfo =
  { currentTime : Posix
  , zone        : Zone
  , pickedTime  : Maybe Posix
  , picker      : DatePicker
  }

type alias UI =
  { tableFilters    : TableFilters
  , modalState      : ModalState
  , hasWriteRights  : Bool
  , loadingAccounts : Bool
  , datePickerInfo  : DatePickerInfo
  , pluginAclInit   : Bool
  }

type alias Account =
  { id                    : String
  , name                  : String
  , description           : String
  , authorisationType     : String
  , kind                  : String
  , enabled               : Bool
  , creationDate          : String
  , token                 : String
  , tokenGenerationDate   : String
  , expirationDateDefined : Bool
  , expirationDate        : Maybe Posix
  , acl                   : Maybe (List AccessControl)
  }

type alias AccessControl =
  { path : String
  , verb : String
  }

type alias ApiResult =
  { aclPluginEnabled : Bool
  , accounts         : List Account
  }

type alias Model =
  { contextPath      : String
  , ui               : UI
  , accounts         : List Account
  , aclPluginEnabled : Bool
  , editAccount      : Maybe Account
  }

type Msg
  = Copy String
  | GenerateId (String -> Msg)
  | CallApi (Model -> Cmd Msg)
  | GetCheckedAcl (Result D.Error (List AccessControl))
  | ToggleEditPopup ModalState
  | GetAccountsResult (Result (Http.Detailed.Error String) ( Http.Metadata, ApiResult))
  | Ignore
  | UpdateTableFilters TableFilters
  | UpdateAccountForm Account
  | SaveAccount (Result (Http.Detailed.Error String) ( Http.Metadata, Account))
  | ConfirmActionAccount ConfirmModalType (Result (Http.Detailed.Error String) ( Http.Metadata, Account))
  -- DATEPICKER
  | OpenPicker Posix
  | UpdatePicker ( DatePicker, Maybe Posix )
  | AdjustTimeZone Zone
  | Tick Posix