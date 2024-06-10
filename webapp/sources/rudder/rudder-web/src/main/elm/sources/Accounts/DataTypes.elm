module Accounts.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed
import Json.Decode as D exposing (..)
import SingleDatePicker exposing (DatePicker, Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings)
import Time exposing (Posix, Zone)

import Ui.Datatable exposing (TableFilters)


--
-- All our data types
--


type ModalState
    = NoModal
    | NewAccount
    | EditAccount Account
    | Confirm ConfirmModalType String Msg
    | CopyToken String


type ConfirmModalType
    = Delete
    | Regenerate


type SortBy
    = Name
    | Id
    | ExpDate
    | CreDate

type TenantMode
    = AllAccess -- special "*" permission giving access to objects in any/no tenants
    | NoAccess -- special "-" permission giving access to no object, whatever the tenant or its absence
    | ByTenants --give access to object in any of the listed tenants


type alias DatePickerInfo =
    { currentTime : Posix
    , zone : Zone
    , pickedTime : Maybe Posix
    , picker : DatePicker Msg
    }

type alias Filters =
    { tableFilters : TableFilters SortBy
    , authType : String
    }

type alias UI =
    { filters : Filters
    , modalState : ModalState
    , hasWriteRights : Bool
    , loadingAccounts : Bool
    , datePickerInfo : DatePickerInfo
    , pluginAclInit : Bool
    , pluginTenantsInit : Bool
    }

type alias Account =
    { id : String
    , name : String
    , description : String
    , authorisationType : String
    , kind : String
    , enabled : Bool
    , creationDate : String
    , token : String
    , tokenGenerationDate : String
    , expirationDateDefined : Bool
    , expirationDate : Maybe Posix
    , acl : Maybe (List AccessControl)
    , tenantMode : TenantMode
    , selectedTenants : Maybe (List String) -- non empty list only
    }


type alias AccessControl =
    { path : String
    , verb : String
    }


type alias ApiResult =
    { aclPluginEnabled : Bool
    , tenantsPluginEnabled : Bool
    , accounts : List Account
    }


type alias Model =
    { contextPath : String
    , ui : UI
    , accounts : List Account
    , aclPluginEnabled : Bool
    , tenantsPluginEnabled : Bool
    , editAccount : Maybe Account
    }


type Msg
    = Copy String
    | GenerateId (String -> Msg)
    | CallApi (Model -> Cmd Msg)
    | GetCheckedAcl (Result D.Error (List AccessControl))
    | GetCheckedTenants (Result D.Error (List String))
    | ToggleEditPopup ModalState
    | GetAccountsResult (Result (Http.Detailed.Error String) ( Http.Metadata, ApiResult ))
    | Ignore
    | UpdateFilters Filters
    | UpdateAccountForm Account
    | SaveAccount (Result (Http.Detailed.Error String) ( Http.Metadata, Account ))
    | ConfirmActionAccount ConfirmModalType (Result (Http.Detailed.Error String) ( Http.Metadata, Account ))
      -- DATEPICKER
    | OpenPicker Posix
    | UpdatePicker SingleDatePicker.Msg
    | AdjustTimeZone Zone
    | Tick Posix
