module Otp.View exposing (..)

import Browser.Events exposing (onKeyDown)
import Html exposing (..)
import Html.Attributes exposing (class, disabled, placeholder, size, type_, value)
import Html.Events exposing (onClick, onInput)
import Html.Events.Extra exposing (onEnter)
import Maybe.Extra
import Otp.DataTypes exposing (..)
import QRCode
import Svg
import Svg.Attributes as SvgA



-- View


view : Model -> Html Msg
view model =
    div [ class "otp-container" ]
        [ h1 [ class "fs-3" ] [ text "Two-factor authentication" ]
        , hr [] []
        , enrollmentBanner model
        , actions model
        ]


enrollmentBanner : Model -> Html Msg
enrollmentBanner model =
    case model.needEnrollment of
        Just True ->
            div [ class "alert alert-warning mt-3" ]
                [ i [ class "fa fa-warning me-2" ] []
                , text "You need to enable a two-factor authentication. Generate an OTP secret for your authenticator app below."
                ]

        _ ->
            div [ class "alert alert-info d-flex justify-content-center" ]
                [ text "OTP code verification required for login" ]


actions : Model -> Html Msg
actions model =
    let
        showEnrollment =
            Maybe.Extra.isNothing model.generatedSecret
                && (model.needEnrollment
                        |> Maybe.withDefault False
                   )
    in
    div [ class "d-flex flex-column align-items-center" ]
        [ case model.errorMsg of
            Just msg ->
                div [ class "alert alert-danger" ]
                    [ text msg ]

            Nothing ->
                text ""
        , if showEnrollment then
            div [ class "d-flex justify-content-center" ]
                [ button
                    [ class "btn btn-primary my-3"
                    , disabled model.isLoading
                    , onClick GenerateOtp
                    ]
                    [ if model.isLoading then
                        text "Generating..."

                      else
                        text "Generate OTP"
                    ]
                ]

          else
            text ""
        , case model.generatedSecret of
            Just secret ->
                div [ class "mt-3 d-flex flex-column align-items-center" ]
                    [ h2 [ class "fs-5" ] [ text "Scan QR Code with your authenticator app" ]
                    , qrCodeView secret.uri
                    , div [ class "mt-2 d-flex flex-column align-items-center" ]
                        [ label [] [ text "Or enter this key manually:" ]
                        , pre [] [ text secret.value ]
                        ]
                    ]

            Nothing ->
                text ""
        , if showEnrollment then
            text ""

          else
            div [ class "mt-3 d-flex flex-column align-items-center" ]
                [ input
                    [ class "form-control mb-2"
                    , type_ "text"
                    , placeholder "Enter 6-digit code"
                    , value model.code
                    , size 12
                    , onInput SetCode
                    , onEnter (VerifyOtp model.code)
                    ]
                    []
                , button
                    [ class "my-3 px-4 btn btn-success"
                    , disabled (model.code == "" || model.isLoading)
                    , onClick (VerifyOtp model.code)
                    ]
                    [ if model.isLoading then
                        text "Verifying..."

                      else
                        text "Verify"
                    ]
                ]
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
