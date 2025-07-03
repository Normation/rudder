module UserManagement.ApiCalls exposing (..)

------------------------------
-- API --
------------------------------
-- API call to get the category tree

import Http exposing (emptyBody, expectJson, expectWhatever, header, jsonBody, request)
import Json.Decode as Decode
import UserManagement.DataTypes exposing (AddUserForm, Model, Msg(..), UserAuth, UserInfoForm, Username)
import UserManagement.JsonDecoder exposing (decodeApiAddUserResult, decodeApiCurrentUsersConf, decodeApiDeleteUserResult, decodeApiReloadResult, decodeApiStatusResult, decodeApiUpdateUserResult, decodeGetRoleApiResult)
import UserManagement.JsonEncoder exposing (encodeAddUser, encodeUserAuth, encodeUserInfo)


getUrl : Model -> String -> String
getUrl m url =
    m.contextPath ++ "/secure/api" ++ url


getUsersConf : Model -> Cmd Msg
getUsersConf model =
    let
        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model "/usermanagement/users"
                , body = emptyBody
                , expect = expectJson GetUserInfo decodeApiCurrentUsersConf
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


postReloadConf : Model -> Cmd Msg
postReloadConf model =
    let
        req =
            request
                { method = "POST"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model "/usermanagement/users/reload"
                , body = emptyBody
                , expect = expectJson PostReloadUserInfo decodeApiReloadResult
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


addUser : Model -> AddUserForm -> Cmd Msg
addUser model userForm =
    let
        req =
            request
                { method = "POST"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model "/usermanagement"
                , body = jsonBody (encodeAddUser userForm)
                , expect = expectJson AddUser decodeApiAddUserResult
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


deleteUser : String -> Model -> Cmd Msg
deleteUser username model =
    let
        req =
            request
                { method = "DELETE"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
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
            request
                { method = "POST"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model ("/usermanagement/update/" ++ toUpdate)
                , body = jsonBody (encodeUserAuth userForm)
                , expect = expectJson UpdateUser decodeApiUpdateUserResult
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


updateUserInfo : Model -> String -> UserInfoForm -> Cmd Msg
updateUserInfo model toUpdate userForm =
    let
        req =
            request
                { method = "POST"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model ("/usermanagement/update/info/" ++ toUpdate)
                , body = jsonBody (encodeUserInfo userForm)
                , expect = expectWhatever UpdateUserInfo
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


activateUser : Model -> Username -> Cmd Msg
activateUser model username =
    let
        req =
            request
                { method = "PUT"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
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
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
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
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model "/usermanagement/roles"
                , body = emptyBody
                , expect = expectJson GetRoleConf decodeGetRoleApiResult
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req


getSafeHashes : Model -> Cmd Msg
getSafeHashes model =
    let
        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model "/usermanagementinternal/safeHashes"
                , body = emptyBody
                , expect = expectJson GetSafeHashes Decode.bool
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req
