module UserManagement.DataTypes exposing (..)

import Dict exposing (Dict)
import Dict.Extra
import Http exposing (Error)
import Json.Decode as Decode
import Json.Decode exposing (decodeValue)
import Set exposing (Set)

type alias Roles = Dict String (List String)
type alias Users = Dict Username User
type alias Provider = String
type alias Username = String
type alias Password = String
type alias RoleConf = List Role
type alias ProvidersInfo = Dict Provider ProviderInfo

type alias AddUserForm =
    { user : NewUser
    , password : String
    , isPreHashed : Bool
    }

type alias UserInfoForm =
    { name : String
    , email : String
    , info : Dict String String
    -- values that are not editable
    , otherInfo : Dict String Decode.Value
    }

type alias Role =
    { id: String
    , rights: List String
    }

type alias UserAuth = 
    { login : String
    , password : String
    , permissions : List String
    , isPreHashed : Bool
    }

type alias User =
    { login : String
    , name : String
    , email : String
    , otherInfo : Dict String Decode.Value
    , status : UserStatus
    , authz : List String
    , roles : List String
    , rolesCoverage : List String
    , customRights : List String
    , providers : List String
    , providersInfo : ProvidersInfo
    , tenants : String
    , lastLogin : Maybe String
    , previousLogin : Maybe String
    }

type UserStatus = 
    Active
    | Disabled
    | Deleted

-- Payload to create a new user or update an existing one
type alias NewUser = 
    { login : String
    , authz : List String
    , roles : List String
    , name : String
    , email : String
    , otherInfo : Dict String String
    }

type alias ProviderProperties = 
    { roleListOverride : RoleListOverride
    }

type alias ProviderInfo = 
    { provider : String
    , authz : List String
    , roles : List String
    , customRights : List String
    }

-- does the configured list of provider allows to change the list of user roles, and how (extend only, or override?)
-- Note: until rudder core is updated to give us the info, we can only guess based on provider list
type RoleListOverride
  = None
  | Extend
  | Override

-- we want to filter out the "rootadmin" account (and perhaps other in the future). All back-end provided
-- provider list should be filtered by that method

userProviders: List String -> List String
userProviders providers =
  List.filter (\p -> p /= "rootAdmin") providers

filterExternalProviders: List String -> List String
filterExternalProviders providers =
  List.filter (\p -> p /= "file" && p /= "rootAdmin") providers

filterUserProviderEnablingRoles: Model -> User -> List ProviderInfo
filterUserProviderEnablingRoles model user = 
    user.providers
    |> filterExternalProviders 
    |> List.filter (\p -> 
        Dict.get p model.providersProperties
        |> Maybe.map (\pp -> pp.roleListOverride /= None)
        |> Maybe.withDefault False
    )
    |> List.filterMap (\p -> 
        Dict.get p user.providersInfo
    )


filterUserProviderByRoleListOverride: RoleListOverride -> Model -> User -> List ProviderInfo
filterUserProviderByRoleListOverride value model user = 
    user.providers
    |> filterExternalProviders 
    |> List.filter (\p -> 
        Dict.get p model.providersProperties
        |> Maybe.map (\pp -> pp.roleListOverride == value)
        |> Maybe.withDefault False
    )
    |> List.filterMap (\p -> 
        Dict.get p user.providersInfo
    )

getFileProviderInfo: User -> Maybe ProviderInfo
getFileProviderInfo user = 
    Dict.get "file" user.providersInfo

takeFirstExtProvider: List String -> Maybe String
takeFirstExtProvider = List.head << filterExternalProviders

takeFirstOverrideProviderInfo : Model -> User -> Maybe ProviderInfo
takeFirstOverrideProviderInfo model user = 
    filterUserProviderByRoleListOverride Override model user
    |> List.head

takeFirstExtendProviderInfo : Model -> User -> Maybe ProviderInfo
takeFirstExtendProviderInfo model user = 
    filterUserProviderByRoleListOverride Extend model user
    |> List.head

providerCanEditRoles : Model -> Provider -> Bool
providerCanEditRoles model provider =
    provider == "file" || (
      Dict.get provider model.providersProperties
      |> Maybe.map (\p -> p.roleListOverride /= Override)
      |> Maybe.withDefault False
    )

type alias UsersConf =
    { digest : String
    , roleListOverride: RoleListOverride
    , authenticationBackends: List String
    , providersProperties : Dict String ProviderProperties
    , users : List User
    , tenantsEnabled : Bool
    }

type PanelMode
  = AddMode
  | EditMode User
  | Closed

type StateInput
    = InvalidUsername
    | InvalidNewUserInfoField
    | ValidInputs

type alias UserForm =
    { login : String
    , password : String
    , isHashedPasswd : Bool
    , userForcePasswdInput : Bool
    , rolesToAddOnSave : List String
    , userInfoForm : UserInfoForm
    , newUserInfoFields : List (String, String)
    , isValidInput : StateInput
    }

type alias Model =
    { contextPath : String
    , userId : String
    , digest: String
    , safeHashes : Bool
    , tenantsEnabled : Bool
    , users: Users
    , roles: Roles
    , rolesConf : RoleConf  -- from API
    , roleListOverride: RoleListOverride
    , userForm : UserForm
    , ui : UI
    , providers : List String
    , providersProperties: Dict String ProviderProperties
    }

type SortOrder
    = Asc
    | Desc


type SortBy
    = UserLogin
    | Name
    | Rights
    | Providers
    | Tenants
    | PreviousLogin


type alias TableFilters =
    { sortBy : SortBy
    , sortOrder : SortOrder
    , filter : String
    }


type alias UI = 
    { panelMode : PanelMode
    , openDeleteModal : Bool
    , tableFilters : TableFilters
    }


type Msg
    = GetUserInfo (Result Error UsersConf)
    | GetRoleConf (Result Error RoleConf)
    | GetSafeHashes (Result Error Bool)
    | PostReloadUserInfo (Result Error String) -- also returns the updated list
    | SendReload -- ask for API call to reload user list
    | AddUser (Result Error String)
    | DeleteUser (Result Error String)
    | UpdateUser (Result Error String)
    | UpdateUserInfo (Result Error ())
    | UpdateUserStatus (Result Error Username)
    | CallApi (Model -> Cmd Msg)
    | ActivePanelSettings User
    | ActivePanelAddUser
    | DeactivatePanel
    | Password String
    | Login String
    | AddRole String
    | RemoveRole User Provider String
    | NewUserInfoFieldKey String Int
    | NewUserInfoFieldValue String Int
    | AddUserInfoField
    | ModifyUserInfoField String String
    | RemoveNewUserInfoField Int
    | RemoveUserInfoField String
    | RemoveUserOtherInfoField String
    | UserInfoName String
    | UserInfoEmail String
    | ActivateUser Username
    | DisableUser Username
    | SubmitUpdateUser UserAuth
    | SubmitUserInfo
    | SubmitNewUser NewUser
    | PreHashedPasswd Bool
    | AddPasswdAnyway
    | OpenDeleteModal String
    | CloseDeleteModal
    | UpdateTableFilters TableFilters


mergeUserNewInfo : UserForm -> UserInfoForm
mergeUserNewInfo userForm =
    let
        userInfo = userForm.userInfoForm
    in
        { name = userInfo.name
        , email = userInfo.email
        , info = 
            Dict.fromList (Dict.toList userForm.userInfoForm.info ++ userForm.newUserInfoFields)
            |> Dict.remove "" -- empty fields are invalid, we remove them for now but they may be used for explicit errors later
        , otherInfo = userInfo.otherInfo
        }

userToUserInfoForm : User -> UserInfoForm
userToUserInfoForm { name, email, otherInfo } =
    let
        -- gather string information : we only know how to edit strings
        info =
            otherInfo
                |> Dict.Extra.filterMap ( \_ -> \v -> Result.toMaybe (decodeValue Decode.string v) )

        infoKeys =
            info
                |> Dict.keys 
                |> Set.fromList

        jsonInfo =
            otherInfo
                |> Dict.Extra.removeMany infoKeys
    in
    { name = name
    , email = email
    , info = info
    , otherInfo = jsonInfo
    }


userFormToNewUser : UserForm -> NewUser
userFormToNewUser userForm =
    let
        userInfo = mergeUserNewInfo userForm
    in
        { login = userForm.login
        , authz = []
        , roles = userForm.rolesToAddOnSave
        , name = userInfo.name
        , email = userInfo.email
        , otherInfo = userInfo.info
        }