module Node.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)

import Node.DataTypes exposing (..)

import Score.JsonDecoder exposing (decodeGetDetails)


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
        , headers = []
        , url     = getUrl model ["nodes" , model.nodeId.value, "score" , "details"] []
        , body    = emptyBody
        , expect  = expectJson GetScoreDetails decodeGetDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req