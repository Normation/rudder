port module Otp.Init exposing (..)

import Otp.ApiCalls exposing (fetchOtpStatus)
import Otp.DataTypes exposing (..)



-- PORT


port errorNotification : String -> Cmd msg


port pushUrl : String -> Cmd msg


port readUrl : (String -> msg) -> Sub msg


type alias Flags =
    { contextPath : String }


init : Flags -> ( Model, Cmd Msg )
init flags =
    let
        initModel =
            { contextPath = flags.contextPath
            , needEnrollment = Nothing
            , generatedSecret = Nothing
            , code = ""
            , isLoading = False
            , redirectUrl = Nothing
            }

        initActions =
            fetchOtpStatus initModel
    in
    ( initModel, initActions )
