module UserManagement.Init exposing (..)

import UserManagement.ApiCalls exposing (getUsersConf)
import UserManagement.DataTypes exposing (..)
import Dict exposing (fromList)
import Html.Attributes exposing (style)
import Http

subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.none

init : { contextPath : String, userId : String } -> ( Model, Cmd Msg )
init flags =
    let
        initUi = UI Closed False (TableFilters UserLogin Asc "")
        initUserInfoForm = UserInfoForm "" "" Dict.empty Dict.empty
        initUserForm = UserForm "" "" True False [] initUserInfoForm [] ValidInputs 
        initModel = Model flags.contextPath flags.userId "" False (fromList []) (fromList []) [] None initUserForm initUi [] Dict.empty
    in
    ( initModel
    , getUsersConf initModel
    )

------------------------------
-- NOTIFICATIONS --
------------------------------

getErrorMessage : Http.Error -> String
getErrorMessage e =
    let
        errMessage =
            case e of
                Http.BadStatus status ->
                    "Code " ++ String.fromInt status

                Http.BadUrl str -> "Invalid API url"

                Http.Timeout ->
                    "It took too long to get a response"

                Http.NetworkError ->
                    "Network error"

                Http.BadBody str ->
                    str
    in
    errMessage
