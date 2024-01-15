module Score exposing (..)

import Browser
import Browser.Navigation as Nav
import Http exposing (..)
import Result

import Score.ApiCalls exposing (getScoreDetails)
import Score.DataTypes exposing (..)
import Score.Init exposing (init, subscriptions, errorNotification)
import Score.View exposing (view)
import NodeCompliance.DataTypes exposing (NodeId)



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
    GetScore res ->
      case res of
        Ok complianceScore ->
          ( { model | complianceScore = Just complianceScore }
          , Cmd.none
          )
        Err err ->
          processApiError "Getting global score" err model

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