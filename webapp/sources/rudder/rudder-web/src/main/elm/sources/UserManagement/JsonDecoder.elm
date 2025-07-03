module UserManagement.JsonDecoder exposing (..)

import UserManagement.DataTypes exposing (Role, RoleConf, RoleListOverride(..), User, UserStatus(..), UsersConf, ProviderInfo, ProvidersInfo, ProviderProperties, UserInfoForm)
import Json.Decode as D exposing (Decoder)
import Json.Decode.Pipeline exposing (optional, required)
import Dict

decodeApiReloadResult : Decoder String
decodeApiReloadResult =
    D.at [ "data" ] decodeReload

decodeReload : Decoder String
decodeReload =
    D.at [ "reload" ] (D.at [ "status" ] D.string)

-- decode the JSON answer from a "get" API call - only "data" field content is interesting

decodeApiCurrentUsersConf : Decoder UsersConf
decodeApiCurrentUsersConf =
    D.at [ "data" ] decodeCurrentUsersConf

decodeCurrentUsersConf : Decoder UsersConf
decodeCurrentUsersConf =
    D.succeed UsersConf
        |> required "digest" D.string
        |> required "roleListOverride" decodeRoleListOverride
        |> required "authenticationBackends" (D.list <| D.string)
        |> required "providerProperties" (D.dict decodeProviderProperties)
        |> required "users" (D.list <| decodeUser)
        |> required "tenantsEnabled" D.bool

decodeProviderProperties : Decoder ProviderProperties
decodeProviderProperties =
    D.succeed ProviderProperties
        |> required "roleListOverride" decodeRoleListOverride

decodeRoleListOverride : Decoder RoleListOverride
decodeRoleListOverride =
  D.string |> D.andThen
    (\str ->
      case str of
        "no-override"   -> D.succeed Extend
        "override" -> D.succeed Override
        _          -> D.succeed None
    )

decodeProviderInfo : Decoder ProviderInfo
decodeProviderInfo =
    D.succeed ProviderInfo
        |> required "provider" D.string
        |> required "authz" (D.list <| D.string)
        |> required "roles" (D.list <| D.string)
        |> required "customRights" (D.list <| D.string)

-- Decode dict of providers info, the key is the provider
decodeProvidersInfo : Decoder ProvidersInfo
decodeProvidersInfo =
    D.dict decodeProviderInfo

decodeUser : Decoder User
decodeUser =
    D.succeed User
        |> required "login" D.string
        |> optional "name" D.string ""
        |> optional "email" D.string ""
        |> required "otherInfo" (D.dict D.value)
        |> required "status" decodeUserStatus
        |> required "authz" (D.list <| D.string)
        |> required "permissions" (D.list <| D.string)
        |> required "rolesCoverage" (D.list <| D.string)
        |> required "customRights" (D.list <| D.string)
        |> required "providers" (D.list <| D.string)
        |> required "providersInfo" decodeProvidersInfo
        |> required "tenants" D.string
        |> optional "lastLogin" (D.maybe D.string) Nothing
        |> optional "previousLogin" (D.maybe D.string) Nothing

decodeUserStatus : Decoder UserStatus
decodeUserStatus =
    D.string |> D.andThen
    (\str ->
      case str of
        "active"   -> D.succeed Active
        "disabled" -> D.succeed Disabled
        _          -> D.succeed Deleted
    )

decodeApiAddUserResult : Decoder String
decodeApiAddUserResult =
    D.at [ "data" ] decodeAddUser

decodeAddUser : Decoder String
decodeAddUser =
    D.at [ "addedUser" ] (D.at [ "username" ] D.string)

decodeApiUpdateUserResult : Decoder String
decodeApiUpdateUserResult =
    D.at [ "data" ] decodeUpdateUser

decodeUpdateUser : Decoder String
decodeUpdateUser =
    D.at [ "updatedUser" ] (D.at [ "username" ] D.string)

decodeApiDeleteUserResult : Decoder String
decodeApiDeleteUserResult =
    D.at [ "data" ] decodeDeletedUser

decodeDeletedUser : Decoder String
decodeDeletedUser =
    D.at [ "deletedUser" ] (D.at [ "username" ] D.string)

decodeApiStatusResult : Decoder String
decodeApiStatusResult =
    D.at [ "data" ] decodeStatus

decodeStatus : Decoder String
decodeStatus =
    D.at [ "status" ] D.string

decodeRole : Decoder Role
decodeRole =
    D.succeed Role
        |> required "id" D.string
        |> required "rights" (D.list <| D.string)

decodeGetRoleApiResult : Decoder RoleConf
decodeGetRoleApiResult =
    D.at [ "data" ] (D.list <| decodeRole)