module Tags.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)
import Json.Encode exposing (Value)

import Tags.DataTypes exposing (..)
import Tags.JsonDecoder exposing (..)
import Tags.JsonEncoder exposing (..)


getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api" :: "completion" :: "tags" :: m.ui.objectType :: url) p

-- "{{contextPath}}/secure/api/completion/tags/{{kind}}/key/"
-- "{{contextPath}}/secure/api/completion/tags/{{kind}}/value/{{newTag.key}}/"
getCompletionTags : Model -> Completion -> Cmd Msg
getCompletionTags model completion =
  let
    param = case completion of
     Key -> [ "key", model.newTag.key ]
     Val -> [ "value", model.newTag.key , model.newTag.value]
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model param []
        , body    = emptyBody
        , expect  = expectJson (GetCompletionTags completion) decodeCompletionTags
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req