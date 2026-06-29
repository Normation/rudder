module Filters.ApiCalls exposing (..)

import Filters.DataTypes exposing (..)
import Http exposing (..)
import Json.Encode exposing (Value)
import Tags.DataTypes exposing (Completion, Tag)
import Tags.JsonDecoder exposing (..)
import Tags.JsonEncoder exposing (..)
import Url.Builder exposing (QueryParameter)


getUrl : Model -> List String -> List QueryParameter -> String
getUrl m url p =
    Url.Builder.relative (m.contextPath :: "secure" :: "api" :: "completion" :: "tags" :: m.objectType :: url) p


getCompletionTags : Model -> Completion -> Cmd Msg
getCompletionTags model completion =
    let
        param =
            case completion of
                Tags.DataTypes.Key ->
                    [ "key", model.newTag.key ]

                Tags.DataTypes.Val ->
                    [ "value", model.newTag.key, model.newTag.value ]

        req =
            request
                { method = "GET"
                , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
                , url = getUrl model param []
                , body = emptyBody
                , expect = expectJson (GetCompletionTags completion) decodeCompletionTags
                , timeout = Nothing
                , tracker = Nothing
                }
    in
    req
