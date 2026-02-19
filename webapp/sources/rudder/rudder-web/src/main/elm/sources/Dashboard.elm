module Dashboard exposing (..)

import Dashboard.DataTypes exposing (..)
import Dashboard.Init exposing (..)
import Dashboard.JsonDecoder exposing (..)
import Dashboard.View exposing (view)

import Browser
import Http.Detailed as Detailed
import Result

{--
This application manage the list of API Accounts and their token properties.
The general behavior is:
- there is a list of API Accounts with action buttons for editing, deleting, token generation, etc
- new one can be created
- action button create a modal window
- there is a main data type about current state of modal (none, new account, editing, etc)
--}


main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }


--
-- update loop --
--


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        -- Do an API call
        CallApi call ->
            ( model, call model )

        GetActivities res ->
            case res of
                Ok ( metadata, activities ) ->
                    ( { model | activities = activities }
                    , initTooltips ""
                    )

                Err err ->
                    processApiError "Getting activities list" err model

        Tick newTime ->
            ( { model | currentTime = newTime }, Cmd.none )

        Copy s ->
            ( model, copy s )


processApiError : String -> Detailed.Error String -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
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

                Detailed.BadBody metadata body msg ->
                    msg
    in
    ( model, errorNotification ("Error when " ++ apiName ++ ", details: \n" ++ message) )
