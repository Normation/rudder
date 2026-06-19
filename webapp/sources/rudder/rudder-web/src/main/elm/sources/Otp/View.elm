module Otp.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (class, disabled, placeholder, type_, value)
import Html.Events exposing (onClick, onInput)
import Otp.DataTypes exposing (..)
import QRCode
import Svg
import Svg.Attributes as SvgA



-- View


view : Model -> Html Msg
view model =
    div [ class "container-fluid otp-container" ]
        [ h1 [] [ text "Two-Factor Authentication" ]
        , hr [] []
        , div [ class "alert alert-info" ]
            [ p [] [ text "Verify an OTP code to complete Rudder login" ] ]
        , enrollmentBanner model
        , div [ class "row" ]
            [ div [ class "col-md-6" ]
                [ h3 [] [ text "Actions" ]
                , actions model
                ]
            ]
        ]


enrollmentBanner : Model -> Html Msg
enrollmentBanner model =
    case model.needEnrollment of
        Just True ->
            div [ class "alert alert-warning mt-3" ]
                [ text "You need to generate an OTP secret to enable two-factor authentication." ]

        _ ->
            div [] []


actions : Model -> Html Msg
actions model =
    div []
        [ case model.needEnrollment of
            Just True ->
                button
                    [ class "btn btn-primary me-2"
                    , disabled model.isLoading
                    , onClick GenerateOtp
                    ]
                    [ if model.isLoading then
                        text "Generating..."

                      else
                        text "Generate OTP"
                    ]

            _ ->
                div [] []
        , div [ class "mt-3" ]
            [ label [] [ text "Verification Code:" ]
            , input
                [ class "form-control mb-2"
                , type_ "text"
                , placeholder "Enter 6-digit code"
                , value model.code
                , onInput SetCode
                ]
                []
            , button
                [ class "btn btn-success"
                , disabled (model.code == "" || model.isLoading)
                , onClick (VerifyOtp model.code)
                ]
                [ if model.isLoading then
                    text "Verifying..."

                  else
                    text "Verify"
                ]
            ]
        , case model.generatedSecret of
            Just secret ->
                div [ class "mt-3" ]
                    [ h4 [] [ text "Scan QR Code with your authenticator app" ]
                    , qrCodeView secret.uri
                    , div [ class "mt-2" ]
                        [ label [] [ text "Or enter this key manually:" ]
                        , pre [] [ text secret.value ]
                        ]
                    ]

            Nothing ->
                div [] []
        ]


qrCodeView : String -> Html Msg
qrCodeView input =
    QRCode.fromString input
        |> Result.map
            (QRCode.toSvg
                [ SvgA.width "200px"
                , SvgA.height "200px"
                ]
            )
        |> Result.withDefault
            (Html.text "")
