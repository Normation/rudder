module Tags.ApiCalls exposing (..)

import Http exposing (..)
import Json.Encode exposing (Value)
import Tags.JsonDecoder exposing (..)
import Tags.JsonEncoder exposing (..)
import Tags.Model exposing (Completion(..), CompletionValue, Model)
import Url.Builder exposing (QueryParameter)


getUrl : Model -> List String -> List QueryParameter -> String
getUrl m url p =
    Url.Builder.relative (m.contextPath :: "secure" :: "api" :: "completion" :: "tags" :: m.ui.objectType :: url) p



-- "{{contextPath}}/secure/api/completion/tags/{{kind}}/key/"
-- "{{contextPath}}/secure/api/completion/tags/{{kind}}/value/{{newTag.key}}/"


getCompletionTags : Model -> Completion -> (Result Http.Error (List CompletionValue) -> msg) -> Cmd msg
getCompletionTags model completion toMsg =
    let
        param =
            case completion of
                Key ->
                    [ "key", model.newTag.key ]

                Val ->
                    [ "value", model.newTag.key, model.newTag.value ]

        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model param []
                , body = emptyBody
                , expect = expectJson toMsg decodeCompletionTags
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req
