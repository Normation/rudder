port module Node exposing (..)

import Browser
import Dict
import Html.Parser exposing (Node(..))
import Html.Parser.Util
import Http exposing (..)
import Json.Decode exposing (Value)
import Result

import Node.DataTypes exposing (..)
import Node.Init exposing (init, errorNotification)
import Node.View exposing (view)
import Score.DataTypes exposing (DetailedScore, ScoreValue(..))


port receiveDetails : ( { name : String, html: String} -> msg )-> Sub msg

port getDetails : { name : String, details: Value } -> Cmd msg

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

subscriptions : Model -> Sub Msg
subscriptions _ = receiveDetails (\d -> ReceiveDetails d.name d.html )

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    GetScoreDetails res ->
      case res of
        Ok scoreDetails ->
          ( { model | details = scoreDetails }
          , Cmd.batch (List.map (\d -> getDetails {name = d.scoreId, details = d.details }) scoreDetails)
          )
        Err err ->
          processApiError "Getting score details" err model
    GetScoreInfo res ->
      case res of
        Ok scoreInfo ->
          ( { model | scoreInfo = scoreInfo }
          , Cmd.none
          )
        Err err ->
          processApiError "Getting score list" err model
    ReceiveDetails name value ->
        case Html.Parser.run value of
          Ok htmlString ->
            let
               filterScript elem =
                   case elem of
                       Element "script" _ _ -> Text ""
                       Element tag attributes children -> Element tag attributes (children |> List.map filterScript)
                       s-> s
               html = Html.Parser.Util.toVirtualDom (htmlString |> List.map filterScript)
            in
              ( { model | detailsHtml = Dict.update name (always (Just html)) model.detailsHtml }
            , Cmd.none
            )
          Err _ ->
            (model, errorNotification ("Error when getting "++ name ++" score display") )


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