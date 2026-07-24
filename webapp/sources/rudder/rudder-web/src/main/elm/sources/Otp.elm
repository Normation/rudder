module Otp exposing (..)

import Browser
import Http.Detailed as Detailed
import Json.Decode exposing (decodeString, string)
import List
import Otp.ApiCalls exposing (..)
import Otp.DataTypes exposing (..)
import Otp.Init exposing (..)
import Otp.View exposing (..)
import Result
import String
import Url exposing (Url)
import Url.Parser as Parser exposing ((</>), (<?>), Parser)
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
                    ( { model | mode = enrollmentMode status, isLoading = False }, Cmd.none )

                Err err ->
                    processApiError "Failed to fetch OTP status" err model

        GenerateResponse result ->
            case result of
                Ok ( _, resp ) ->
                    -- even if we enter a generation, we should be able to skip, if NotEnrolled we shouldn't
                    ( { model | isLoading = False, mode = SecretEnrollment resp.secret }, Cmd.none )

                Err err ->
                    processApiError "Failed to generate OTP secret" err model

        VerifyResponse result ->
            case result of
                Ok _ ->
                    case model.redirectUrl of
                        Just url ->
                            ( model, pushUrl url )

                        Nothing ->
                            ( model, pushUrl <| model.contextPath ++ "/secure/index.html" )

                Err (Detailed.BadStatus metadata errMsg) ->
                    case metadata.statusCode of
                        -- Handled verification error case on server
                        403 ->
                            ( { model | isLoading = False, errorMsg = Just errMsg }, Cmd.none )

                        code ->
                            ( { model | isLoading = False, errorMsg = Just <| "Error when verifying OTP code: unexpected server response " ++ String.fromInt code }, Cmd.none )

                Err _ ->
                    ( { model | isLoading = False, errorMsg = Just "Error when verifying OTP code" }, Cmd.none )

        UrlChanged url ->
            case Url.fromString url of
                Just parsedUrl ->
                    let
                        validated =
                            parseRedirect parsedUrl
                                |> Maybe.andThen (validateRedirect parsedUrl)
                    in
                    case validated of
                        Just redirect ->
                            ( { model | redirectUrl = Just redirect }, Cmd.none )

                        Nothing ->
                            ( { model | redirectUrl = Just <| Url.toString { parsedUrl | path = model.contextPath ++ "/secure/index.html" } }, Cmd.none )

                Nothing ->
                    ( model, Cmd.none )


parseRedirect : Url -> Maybe String
parseRedirect url =
    let
        -- 1. Force the path to "/" so it matches Parser.top, ignoring the 3 segments
        normalizedUrl =
            { url | path = "/" }

        -- 2. Create a parser that targets the "redirect" query parameter
        redirectParser =
            Parser.map identity (Parser.top <?> Query.string "redirect")
    in
    -- 3. Run the parser and flatten the resulting Maybe (Maybe String) into Maybe String
    Parser.parse redirectParser normalizedUrl
        |> Maybe.andThen identity


validateRedirect : Url -> String -> Maybe String
validateRedirect originalUrl redirectStr =
    case Url.fromString redirectStr of
        Just redirectUrl ->
            let
                isSameProtocol =
                    redirectUrl.protocol == originalUrl.protocol

                isSameHost =
                    redirectUrl.host == originalUrl.host

                isSamePort =
                    redirectUrl.port_ == originalUrl.port_
            in
            if isSameProtocol && isSameHost && isSamePort then
                Just redirectStr

            else
                Nothing

        Nothing ->
            Nothing



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
