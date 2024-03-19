module Accounts.DataTypes exposing (..)

import Html exposing (Html)
import Http exposing (Error)
import Http.Detailed
import Json.Decode as D exposing (..)
import SingleDatePicker exposing (DatePicker, Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings)
import Time exposing (Posix, Zone)



--
-- All our data types
--


type ModalState
    = NoModal
    | NewAccount
    | EditAccount Account
    | Confirm ConfirmModalType String Msg


type CopyState
    = NoCopy
    | Token String


type ConfirmModalType
    = Delete
    | Regenerate


type SortOrder
    = Asc
    | Desc


type SortBy
    = Name
    | Id
    | ExpDate
    | CreDate


type TenantMode
    = AllAccess -- special "*" permission giving access to objects in any/no tenants
    | NoAccess -- special "-" permission giving access to no object, whatever the tenant or its absence
    | ByTenants --give access to object in any of the listed tenants


type alias TableFilters =
    { sortBy : SortBy
    , sortOrder : SortOrder
    , filter : String
    , authType : String
    }


type alias DatePickerInfo =
    { currentTime : Posix
    , zone : Zone
    , pickedTime : Maybe Posix
    , picker : DatePicker Msg
    }


type alias UI =
    { tableFilters : TableFilters
    , modalState : ModalState
    , copyState : CopyState
    , hasWriteRights : Bool
    , loadingAccounts : Bool
    , datePickerInfo : DatePickerInfo
    , pluginAclInit : Bool
    , pluginTenantsInit : Bool
    }


type alias ModalUI msg =
    { displayAcl : Bool
    , displayTenants : Bool
    , saveAction : Msg
    , body : Html msg
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
    | CloseCopyPopup
    | GetAccountsResult (Result (Http.Detailed.Error String) ( Http.Metadata, ApiResult ))
    | Ignore
    | UpdateTableFilters TableFilters
    | UpdateAccountForm Account
    | SaveAccount (Result (Http.Detailed.Error String) ( Http.Metadata, Account ))
    | ConfirmActionAccount ConfirmModalType (Result (Http.Detailed.Error String) ( Http.Metadata, Account ))
      -- DATEPICKER
    | OpenPicker Posix
    | UpdatePicker SingleDatePicker.Msg
    | AdjustTimeZone Zone
    | Tick Posix
