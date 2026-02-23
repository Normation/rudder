module Accounts.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed
import Json.Decode as D exposing (..)
import SingleDatePicker exposing (DatePicker, Settings, TimePickerVisibility(..))
import Time exposing (Posix, Zone)
import Ui.Datatable exposing (TableFilters)
import Utils.DateUtils exposing (isBefore)


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
    | Create


type SortBy
    = Name
    | Id
    | ExpDate
    | CreDate
    | LstDate

type TenantMode
    = AllAccess -- special "*" permission giving access to objects in any/no tenants
    | NoAccess -- special "-" permission giving access to no object, whatever the tenant or its absence
    | ByTenants --give access to object in any of the listed tenants

type TokenState
    = Undef
    | GeneratedV1
    | GeneratedV2

type Token
    = New String
    | Hashed
    | ClearText

type alias DatePickerInfo =
    { currentTime : Posix
    , zone : Zone
    , pickedTime : Maybe Posix
    , picker : DatePicker Msg
    }

type alias Filters =
    { tableFilters : TableFilters SortBy
    , authType : Maybe AuthorizationType
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
    , authorizationType : AuthorizationType
    , kind : String
    , status : AccountStatus
    , creationDate : Posix
    , tokenState: TokenState
    , token : Maybe Token
    , tokenGenerationDate : Maybe Posix
    , expirationPolicy: ExpirationPolicy
    , lastAuthenticationDate : Maybe Posix
    , acl : Maybe (List AccessControl)
    , tenantMode : TenantMode
    , selectedTenants : Maybe (List String) -- non empty list only
    }


type AuthorizationType
    = None
    | RO
    | RW
    | ACL


type AccountStatus
    = Enabled
    | Disabled


type ExpirationPolicy
    = NeverExpire
    | ExpireAtDate Posix


type alias AccessControl =
    { path : String
    , verb : String
    }


type alias ApiResult =
    { accounts : List Account
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
    | Tick Posix


accountStatusText : AccountStatus -> String
accountStatusText status =
    case status of
        Enabled ->
            "enabled"

        Disabled ->
            "disabled"


authorizationTypeText : AuthorizationType -> String
authorizationTypeText arg =
    case arg of
        None ->
            "none"

        RO ->
            "ro"

        RW ->
            "rw"

        ACL ->
            "acl"


authorizationTypeFromText : String -> Maybe AuthorizationType
authorizationTypeFromText arg =
    case arg of
        "none" ->
            Just None

        "ro" ->
            Just RO

        "rw" ->
            Just RW

        "acl" ->
            Just ACL

        _ ->
            Nothing


expirationDate : ExpirationPolicy -> Maybe Posix
expirationDate policy =
    case policy of
        ExpireAtDate d ->
            Just d

        NeverExpire ->
            Nothing


{-| Sets the expiration value of ExpireAtDate only when policy is to never expire
-}
setIfExpireAtDate : Posix -> ExpirationPolicy -> ExpirationPolicy
setIfExpireAtDate date policy =
    case policy of
        ExpireAtDate _ ->
            ExpireAtDate date

        NeverExpire ->
            NeverExpire



setExpirationPolicy : ExpirationPolicy -> Account -> Account
setExpirationPolicy policy account =
    { account
        | expirationPolicy = policy
    }



updateExpirationPolicy : (ExpirationPolicy -> ExpirationPolicy) -> Account -> Account
updateExpirationPolicy f account =
    setExpirationPolicy (f account.expirationPolicy) account



checkIfExpired : DatePickerInfo -> Account -> Bool
checkIfExpired datePickerInfo account =
  case account.expirationPolicy of
    ExpireAtDate p -> isBefore { date = p, reference = datePickerInfo.currentTime }
    NeverExpire -> False

checkIfTokenV1 : Account -> Bool
checkIfTokenV1 a =
  case a.tokenState of
    GeneratedV1 -> True
    GeneratedV2 -> False
    Undef       -> False
