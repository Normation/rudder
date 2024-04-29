module UserManagement.ApiCalls exposing (..)

------------------------------
-- API --
------------------------------
-- API call to get the category tree

import UserManagement.DataTypes exposing (AddUserForm, Model, Msg(..), UserAuth, UserInfoForm, Username)
import Http exposing (emptyBody, expectJson, get, jsonBody, post, request)
import Json.Decode as Decode
import UserManagement.JsonDecoder exposing (decodeApiAddUserResult, decodeApiCurrentUsersConf, decodeApiDeleteUserResult, decodeApiReloadResult, decodeApiStatusResult, decodeApiUpdateUserInfoResult, decodeApiUpdateUserResult, decodeGetRoleApiResult)
import UserManagement.JsonEncoder exposing (encodeAddUser, encodeUserAuth, encodeUserInfo)


getUrl : Model -> String -> String
getUrl m url =
    m.contextPath ++ "/secure/api" ++ url


getUsersConf : Model -> Cmd Msg
getUsersConf model =
    let
        req =
            get
                { url = getUrl model "/usermanagement/users"
                , expect = expectJson GetUserInfo decodeApiCurrentUsersConf
                }
    in
    req


postReloadConf : Model -> Cmd Msg
postReloadConf model =
    let
        req =
            post
                { url = getUrl model "/usermanagement/users/reload"
                , body = emptyBody
                , expect = expectJson PostReloadUserInfo decodeApiReloadResult
                }
    in
    req


addUser : Model -> AddUserForm -> Cmd Msg
addUser model userForm =
    let
        req =
            post
                { url = getUrl model "/usermanagement"
                , body = jsonBody (encodeAddUser userForm)
                , expect = expectJson AddUser decodeApiAddUserResult
                }
    in
    req


deleteUser : String -> Model -> Cmd Msg
deleteUser username model =
    let
        req =
            request
                { method = "DELETE"
                , headers = []
                , url = getUrl model ("/usermanagement/" ++ username)
                , body = emptyBody
                , expect = expectJson DeleteUser decodeApiDeleteUserResult
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


updateUser : Model -> String -> UserAuth -> Cmd Msg
updateUser model toUpdate userForm =
    let
        req =
            post
                { url = getUrl model ("/usermanagement/update/" ++ toUpdate)
                , body = jsonBody (encodeUserAuth userForm)
                , expect = expectJson UpdateUser decodeApiUpdateUserResult
                }
    in
    req


updateUserInfo : Model -> String -> UserInfoForm -> Cmd Msg
updateUserInfo model toUpdate userForm =
    let
        req =
            post
                { url = getUrl model ("/usermanagement/update/info/" ++ toUpdate)
                , body = jsonBody (encodeUserInfo userForm)
                , expect = expectJson UpdateUserInfo decodeApiUpdateUserInfoResult
                }
    in
    req


activateUser : Model -> Username -> Cmd Msg
activateUser model username =
    let
        req =
            request
                { method = "PUT"
                , headers = []
                , url = getUrl model ("/usermanagement/status/activate/" ++ username)
                , body = emptyBody
                , expect = expectJson UpdateUserStatus (Decode.map (\_ -> username) decodeApiStatusResult)
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


disableUser : Model -> Username -> Cmd Msg
disableUser model username =
    let
        req =
            request
                { method = "PUT"
                , headers = []
                , url = getUrl model ("/usermanagement/status/disable/" ++ username)
                , body = emptyBody
                , expect = expectJson UpdateUserStatus (Decode.map (\_ -> username) decodeApiStatusResult)
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


getRoleConf : Model -> Cmd Msg
getRoleConf model =
    let
        req =
            get
                { url = getUrl model "/usermanagement/roles"
                , expect = expectJson GetRoleConf decodeGetRoleApiResult
                }
    in
    req
