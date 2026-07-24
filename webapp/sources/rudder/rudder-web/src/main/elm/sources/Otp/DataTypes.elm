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


type Mode
    = CodeVerification
    | SecretGeneration
    | SecretEnrollment TotpSecretData


type Enrollment
    = Enrolled
    | EnrollmentNeeded


type alias Model =
    { contextPath : String
    , mode : Mode
    , code : String
    , errorMsg : Maybe String
    , isLoading : Bool
    , redirectUrl : Maybe String
    }


type Msg
    = SetCode String
    | GenerateOtp
    | VerifyOtp String
    | OtpStatusResponse (Result (Detailed.Error String) ( Http.Metadata, Enrollment ))
    | GenerateResponse (Result (Detailed.Error String) ( Http.Metadata, GenerateResponse ))
    | VerifyResponse (Result (Detailed.Error String) ())
    | UrlChanged String


enrollmentMode : Enrollment -> Mode
enrollmentMode e =
    case e of
        EnrollmentNeeded ->
            SecretGeneration

        Enrolled ->
            CodeVerification
