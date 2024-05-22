module Node.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)

import Node.DataTypes exposing (..)

import Score.JsonDecoder exposing (decodeGetDetails, decodeGetInfo)


--
-- This files contains all API calls for the Directive compliance UI
--

getUrl: Model -> List String -> List QueryParameter -> String
getUrl model url p=
  Url.Builder.relative (model.contextPath :: "secure" :: "api"  :: url) p

getScoreDetails : Model -> Cmd Msg
getScoreDetails model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model ["nodes" , model.nodeId.value, "score" , "details"] []
        , body    = emptyBody
        , expect  = expectJson GetScoreDetails decodeGetDetails
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
