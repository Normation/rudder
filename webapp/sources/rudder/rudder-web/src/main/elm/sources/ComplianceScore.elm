port module ComplianceScore exposing (..)

import Browser
import ComplianceScore.DataTypes exposing (Msg(..))
import ComplianceScore.JsonDecoder exposing (decodeComplianceScore)
import ComplianceScore.View exposing (buildScoreDetails)
import Html exposing (text)
import Html.String
import Json.Decode exposing (Value, errorToString)

port sendHtml : String -> Cmd msg
port getValue : (Value -> msg) -> Sub msg

main =
  Browser.element
    { init = init
    , view = always (text "")
    , update = update
    , subscriptions = subscriptions
    }


-- PORTS / SUBSCRIPTIONS
port errorNotification   : String -> Cmd msg
subscriptions :  () -> Sub Msg
subscriptions _ = getValue (NewScore)

init : () -> ( (), Cmd Msg )
init _ = ( (), Cmd.none )

update :  Msg -> () -> ( () , Cmd Msg)
update msg model =
    case msg of
        NewScore value ->
          case (Json.Decode.decodeValue decodeComplianceScore value) of
            Ok compliance ->
              let
                cmd = buildScoreDetails compliance |> Html.String.toString 0 |> sendHtml
              in
                (model, cmd)
            Err err ->
               (model, errorNotification(("Error while reading compliance score details, error is:" ++ (errorToString err))))