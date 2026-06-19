module Otp.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed as Detailed



-- Secret data from the generate endpoint


type alias TotpSecretData =
    { value : String
    , uri : String
    }



-- Container wrapping TotpSecretData


type alias TotpSecretContainer =
    { secret : TotpSecretData }



-- Response from the generate endpoint


type alias GenerateResponse =
    TotpSecretContainer



-- Request to verify OTP code


type alias Model =
    { contextPath : String
    , needEnrollment : Maybe Bool
    , generatedSecret : Maybe TotpSecretData
    , code : String
    , errorMsg : Maybe String
    , isLoading : Bool
    , redirectUrl : Maybe String
    }


type Msg
    = SetCode String
    | GenerateOtp
    | VerifyOtp String
    | OtpStatusResponse (Result (Detailed.Error String) ( Http.Metadata, Bool ))
    | GenerateResponse (Result (Detailed.Error String) ( Http.Metadata, GenerateResponse ))
    | VerifyResponse (Result (Detailed.Error String) ())
    | UrlChanged String
