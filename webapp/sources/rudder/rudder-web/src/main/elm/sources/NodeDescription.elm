port module NodeDescription exposing (..)

import Browser
import Http
import NodeDescription.ApiCalls exposing (saveNodeDescription)
import NodeDescription.DataTypes exposing (..)
import NodeDescription.Init exposing (..)
import NodeDescription.View exposing (..)


port successNotification : String -> Cmd msg


port errorNotification : String -> Cmd msg


subscriptions : Model -> Sub Msg
subscriptions _ =
    Sub.none


main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        GetNodeDescription result ->
            case result of
                Ok description ->
                    ( { model | currentDescription = description, newDescription = description }, Cmd.none )

                Err error ->
                    processApiError "fetching node description" error model

        SetNodeDescription result ->
            case result of
                Ok description ->
                    ( { model | currentDescription = description, newDescription = description }, successNotification "Node description modified successfully" )

                Err error ->
                    processApiError "modifying node description" error model

        EditNodeDescription newDescription ->
            ( { model | newDescription = newDescription }, Cmd.none )

        ToggleMode ->
            case model.editMode of
                NoNodeWriteAuth ->
                    ( model, Cmd.none )

                HasNodeWriteAuth Read ->
                    ( { model | editMode = HasNodeWriteAuth Write }, Cmd.none )

                HasNodeWriteAuth Write ->
                    update SubmitDescription { model | editMode = HasNodeWriteAuth Read }

        SubmitDescription ->
            if model.currentDescription == model.newDescription then
                ( model, Cmd.none )

            else
                ( model, saveNodeDescription model )


processApiError : String -> Http.Error -> Model -> ( Model, Cmd Msg )
processApiError apiName err model =
    let
        message =
            case err of
                Http.BadUrl url ->
                    "The URL " ++ url ++ " was invalid"

                Http.Timeout ->
                    "Unable to reach the server, try again"

                Http.NetworkError ->
                    "Unable to reach the server, check your network connection"

                Http.BadStatus 500 ->
                    "The server had a problem, try again later"

                Http.BadStatus 400 ->
                    "Verify your information and try again"

                Http.BadStatus _ ->
                    "Unknown error"

                Http.BadBody errorMessage ->
                    errorMessage
    in
    ( model, errorNotification ("Error when " ++ apiName ++ ", details: \n" ++ message) )
