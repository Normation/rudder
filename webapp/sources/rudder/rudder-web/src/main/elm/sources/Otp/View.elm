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
        , viewModeHeader model.mode
        , viewErrorMsg model.errorMsg
        , div [ class "d-flex flex-column align-items-center" ]
            (viewMode model.mode model.code model.isLoading)
        ]


viewModeHeader : Mode -> Html Msg
viewModeHeader mode =
    case mode of
        CodeVerification ->
            div [ class "alert alert-info d-flex justify-content-center" ]
                [ text "TOTP code verification required for login" ]

        _ ->
            div [ class "alert alert-warning mt-3" ]
                [ i [ class "fa fa-warning me-2" ] []
                , text "You need to enable a two-factor authentication. Generate a TOTP secret for your authenticator app below."
                ]


viewErrorMsg errorMsg =
    case errorMsg of
        Just msg ->
            div [ class "alert alert-danger" ]
                [ text msg ]

        Nothing ->
            text ""


viewMode : Mode -> String -> Bool -> List (Html Msg)
viewMode mode code loading =
    case mode of
        SecretGeneration ->
            [ div [ class "d-flex justify-content-center" ]
                [ button
                    [ class "btn btn-primary my-3"
                    , disabled loading
                    , onClick GenerateOtp
                    ]
                    [ if loading then
                        text "Generating..."

                      else
                        text "Generate TOTP"
                    ]
                ]
            ]

        SecretEnrollment secret ->
            [ div [ class "mt-3 d-flex flex-column align-items-center" ]
                [ h2 [ class "fs-5" ] [ text "Scan QR Code with your authenticator app" ]
                , qrCodeView secret.uri
                , div [ class "mt-2 d-flex flex-column align-items-center" ]
                    [ label [] [ text "Or enter this key manually:" ]
                    , pre [] [ text secret.value ]
                    ]
                ]
            , viewCode code loading
            ]

        CodeVerification ->
            [ viewCode code loading ]


viewCode code loading =
    div [ class "mt-3 d-flex flex-column align-items-center" ]
        [ input
            [ class "form-control mb-2"
            , type_ "text"
            , placeholder "Enter 6-digit code"
            , value code
            , size 12
            , onInput SetCode
            , onEnter (VerifyOtp code)
            ]
            []
        , button
            [ class "my-3 px-4 btn btn-success"
            , disabled (code == "" || loading)
            , onClick (VerifyOtp code)
            ]
            [ if loading then
                text "Verifying..."

              else
                text "Verify"
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
