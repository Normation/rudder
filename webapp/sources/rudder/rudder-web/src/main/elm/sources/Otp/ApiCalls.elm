module Otp.ApiCalls exposing (..)

import Http exposing (header)
import Http.Detailed as Detailed
import Json.Encode as J
import Otp.DataTypes exposing (..)
import Otp.JsonDecoder exposing (..)
import Url.Builder


apiBaseUrl : Model -> String
apiBaseUrl m =
    Url.Builder.relative (m.contextPath :: "secure" :: "api" :: []) []


fetchOtpStatus : Model -> Cmd Msg
fetchOtpStatus model =
    Http.request
        { method = "GET"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = Url.Builder.relative (model.contextPath :: "secure" :: "api" :: "otp" :: "status" :: []) []
        , body = Http.emptyBody
        , expect = Detailed.expectJson OtpStatusResponse decodeStatus
        , timeout = Nothing
        , tracker = Nothing
        }


generateOtp : Model -> Cmd Msg
generateOtp model =
    Http.request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = Url.Builder.relative (model.contextPath :: "secure" :: "api" :: "otp" :: "generate" :: []) []
        , body = Http.emptyBody
        , expect = Detailed.expectJson GenerateResponse decodeGenerate
        , timeout = Nothing
        , tracker = Nothing
        }


verifyOtp : Model -> String -> Cmd Msg
verifyOtp model code =
    let
        body =
            J.string code
    in
    Http.request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = Url.Builder.relative (model.contextPath :: "secure" :: "api" :: "otp" :: "verify" :: []) []
        , body = Http.jsonBody body
        , expect = Detailed.expectJson VerifyResponse decodeVerify
        , timeout = Nothing
        , tracker = Nothing
        }

