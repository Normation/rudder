module Otp.JsonDecoder exposing (..)

import Http.Detailed as Detailed
import Json.Decode as D
import Json.Decode.Pipeline exposing (required)
import Otp.DataTypes exposing (..)



-- Decode status response: { action, result, data: { needEnrollment: Bool } }


decodeStatus : D.Decoder Bool
decodeStatus =
    D.at [ "data", "needEnrollment" ] D.bool



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



-- Decode verify response: { action, result, data: "ok" }


decodeVerify : D.Decoder String
decodeVerify =
    D.at [ "data" ] D.string



-- Decode verify error response: { action, result, data: { message: String } }


decodeVerifyErrorResponse : D.Decoder String
decodeVerifyErrorResponse =
    D.at [ "data", "message" ] D.string
