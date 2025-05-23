module UserManagement.JsonEncoder exposing (..)

import UserManagement.DataTypes exposing (AddUserForm, UserAuth, UserInfoForm)
import Json.Encode exposing (Value, bool, list, object, string)
import Dict

encodeUserAuth: UserAuth -> Value
encodeUserAuth user =
    object
    [ ("username", string user.login)
    , ("password", string user.password)
    , ("permissions", list (\s -> string s) user.permissions)
    , ("isPreHashed", bool user.isPreHashed)
    ]

encodeAddUser: AddUserForm -> Value
encodeAddUser userForm =
    object
    [ ("username", string userForm.user.login)
    , ("password", string userForm.password)
    , ("permissions", list (\s -> string s) (userForm.user.authz ++  userForm.user.roles))
    , ("isPreHashed", bool userForm.isPreHashed)
    , ("name", string userForm.user.name)
    , ("email", string userForm.user.email)
    , ("otherInfo", object (List.map (\(k, v) -> (k, string v)) (Dict.toList userForm.user.otherInfo)))
    ]

encodeUserInfo: UserInfoForm -> Value
encodeUserInfo user =
    object
    [ ("name", string user.name)
    , ("email", string user.email)
    , ("otherInfo",  object (user.info |> Dict.toList |> List.map (\(k, v) -> (k, string v)) |> List.append (Dict.toList user.otherInfo)))
    ]
