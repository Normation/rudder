module ComplianceScore.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)

import ComplianceScore.DataTypes exposing (..)
import ComplianceScore.JsonDecoder exposing (decodeGetComplianceScore)

--
-- This files contains all API calls for the Directive compliance UI
--

getUrl: Model -> List String -> List QueryParameter -> String
getUrl model url p=
  Url.Builder.relative (model.contextPath :: "secure" :: "api"  :: url) p

getItemScoreUrl : ItemType -> List String
getItemScoreUrl item =
  case item of
    Node id -> ["nodes" , id.value, "score"]
    Rule id -> ["rules" , id.value, "score"]

getComplianceScore : Model -> ItemType -> Cmd Msg
getComplianceScore model itemType =
  let
    url = (getItemScoreUrl itemType)
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model url []
        , body    = emptyBody
        , expect  = expectJson GetComplianceScore decodeGetComplianceScore
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req