module Otp.JsonDecoder exposing (..)

import Json.Decode as D
import Json.Decode.Pipeline exposing (required)
import Otp.DataTypes exposing (..)


decodeStatus : D.Decoder Enrollment
decodeStatus =
    D.at [ "data" ] decodeEnrollment


decodeEnrollment : D.Decoder Enrollment
decodeEnrollment =
    D.string
        |> D.andThen
            (\s ->
                case s of
                    "enrolled" ->
                        D.succeed Enrolled

                    "enrollmentNeeded" ->
                        D.succeed EnrollmentNeeded

                    _ ->
                        D.fail <| "Could not decode enrollment value '" ++ s ++ "', must be enrolled/enrollmentNeeded"
            )



-- Decode secret data: { value, uri }


decodeTotpSecretData : D.Decoder TotpSecretData
decodeTotpSecretData =
    D.succeed TotpSecretData
        |> required "value" D.string
        |> required "uri" D.string



-- Decode secret container: { secret: { value, uri } }


decodeTotpSecretContainer : D.Decoder TotpSecretContainer
decodeTotpSecretContainer =
    D.succeed TotpSecretContainer
        |> required "secret" decodeTotpSecretData



-- Decode generate response: { action, result, data: { secret: { value, uri } } }


decodeGenerate : D.Decoder GenerateResponse
decodeGenerate =
    D.at [ "data" ] decodeTotpSecretContainer
