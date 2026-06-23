module Otp exposing (..)

import Browser
import Html exposing (..)
import Html.Attributes exposing (..)
import Http.Detailed as Detailed
import Json.Decode exposing (decodeString, string)
import List
import Maybe.Extra
import Otp.ApiCalls exposing (..)
import Otp.DataTypes exposing (..)
import Otp.Init exposing (..)
import Otp.JsonDecoder exposing (..)
import Otp.View exposing (..)
import Result
import String
import Url exposing (Url)
import Url.Parser as Parser exposing ((<?>), Parser, parse)
import Url.Parser.Query as Query



-- MAIN


main : Program Flags Model Msg
main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }


subscriptions : Model -> Sub Msg
subscriptions _ =
    Sub.batch
        [ readUrl UrlChanged ]



-- UPDATE


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        SetCode code ->
            ( { model | code = code }, Cmd.none )

        GenerateOtp ->
            ( { model | isLoading = True }, generateOtp model )

        VerifyOtp code ->
            ( { model | isLoading = True }, verifyOtp model code )

        OtpStatusResponse result ->
            case result of
                Ok ( _, status ) ->
                    ( { model | needEnrollment = Just status, isLoading = False }, Cmd.none )

                Err err ->
                    processApiError "Failed to fetch OTP status" err model

        GenerateResponse result ->
            case result of
                Ok ( _, resp ) ->
                    ( { model | isLoading = False, needEnrollment = Just True, generatedSecret = Just resp.secret }, Cmd.none )

                Err err ->
                    processApiError "Failed to generate OTP secret" err model

        VerifyResponse result ->
            case result of
                Ok _ ->
                    case model.redirectUrl of
                        Just url ->
                            ( { model | isLoading = False }, pushUrl url )

                        Nothing ->
                            ( { model | isLoading = False }, pushUrl "/secure/index.html" )

                Err (Detailed.BadStatus metadata body) ->
                    case metadata.statusCode of
                        -- Handled verification error case on server
                        403 ->
                            ( { model | isLoading = False }, errorNotification body )

                        code ->
                            ( { model | isLoading = False }, errorNotification <| "Error when verifying OTP code: unexpected http code " ++ String.fromInt code )

                Err _ ->
                    ( { model | isLoading = False }, errorNotification "Error when verifying OTP code" )

        UrlChanged url ->
            case Url.fromString url of
                Just parsedUrl ->
                    case parseRedirect parsedUrl of
                        Just redirect ->
                            ( { model | redirectUrl = Just redirect }, Cmd.none )

                        Nothing ->
                            ( { model | redirectUrl = Just <| Url.toString { parsedUrl | path = model.contextPath ++ "/secure/index.html" } }, Cmd.none )

                Nothing ->
                    ( model, Cmd.none )


parseRedirect : Url -> Maybe String
parseRedirect =
    parse (Parser.map (\_ r -> r) (anyPath <?> Query.string "redirect")) >> Maybe.Extra.join



{- Consumes all path segments, to ignore them -}


anyPath =
    Parser.custom "ANY_PATH" <| \a -> Just [ a ]



-- Generic API error handler


processApiError : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiError msg err model =
    let
        message =
            case err of
                Detailed.BadUrl url ->
                    "The URL " ++ url ++ " was invalid"

                Detailed.Timeout ->
                    "Unable to reach the server, try again"

                Detailed.NetworkError ->
                    "Unable to reach the server, check your network connection"

                Detailed.BadStatus metadata body ->
                    let
                        ( title, errors ) =
                            decodeErrorDetails body
                    in
                    title ++ "\n" ++ errors

                Detailed.BadBody metadata body m ->
                    m
    in
    ( model, errorNotification (msg ++ ", details: \n" ++ message) )


decodeErrorDetails : String -> ( String, String )
decodeErrorDetails json =
    let
        errorMsg =
            decodeString (Json.Decode.at [ "errorDetails" ] string) json

        msg =
            case errorMsg of
                Ok s ->
                    s

                Err e ->
                    "fail to process errorDetails"

        errors =
            String.split "<-" msg

        title =
            List.head errors
    in
    case title of
        Nothing ->
            ( "", "" )

        Just s ->
            ( s, String.join " \n " (List.drop 1 (List.map (\err -> "\t ‣ " ++ err) errors)) )
