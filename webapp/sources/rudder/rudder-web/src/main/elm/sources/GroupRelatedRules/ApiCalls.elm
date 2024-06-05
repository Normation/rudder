module GroupRelatedRules.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter, string)

import GroupRelatedRules.DataTypes exposing (..)
import GroupRelatedRules.JsonDecoder exposing (..)

getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: url) p

getRulesTree : Model -> RelatedRules -> Cmd Msg
getRulesTree model relatedRules =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "rulesinternal", "relatedtree" ] [string "rules" (String.join "," (List.map (.value) relatedRules.value))]
        , body    = emptyBody
        , expect  = expectJson (GetRulesResult relatedRules) decodeGetRulesTree
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req
