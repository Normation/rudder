module Node exposing (..)

import Browser
import Http exposing (..)
import Result

import Node.DataTypes exposing (..)
import Node.Init exposing (init, subscriptions, errorNotification)
import Node.View exposing (view)

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
    GetScoreDetails res ->
      case res of
        Ok scoreDetails ->
          ( { model | scoreDetails = Just scoreDetails }
          , Cmd.none
          )
        Err err ->
          processApiError "Getting compliance score details" err model

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
    (model, errorNotification ("Error when "++ apiName ++", details: \n" ++ message ) )