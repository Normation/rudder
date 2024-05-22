module Score.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)

import Score.DataTypes exposing (..)
import Score.JsonDecoder exposing (decodeGetInfo, decodeGetScore)

--
-- This files contains all API calls for the Directive compliance UI
--

getUrl: Model -> List String -> List QueryParameter -> String
getUrl model url p=
  Url.Builder.relative (model.contextPath :: "secure" :: "api"  :: url) p


getScore : Model -> Cmd Msg
getScore model =
  let
    url = ["nodes" , model.nodeId.value, "score"]
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model url []
        , body    = emptyBody
        , expect  = expectJson GetScore decodeGetScore
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req



getScoreDetails : Model -> Cmd Msg
getScoreDetails model =
  let
    url = ["nodes" , model.nodeId.value, "score", "details"]
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model url []
        , body    = emptyBody
        , expect  = expectJson GetScore decodeGetScore
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getScoreInfo : Model -> Cmd Msg
getScoreInfo model =
  let
    url = ["scores" , "list"]
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model url []
        , body    = emptyBody
        , expect  = expectJson GetScoreInfo decodeGetInfo
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req
