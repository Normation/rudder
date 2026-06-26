module NodeDescription.ApiCalls exposing (getNodeDescription, getUrl, saveNodeDescription)

import Http exposing (..)
import NodeDescription.DataTypes exposing (..)
import NodeDescription.JsonDecoder exposing (..)
import NodeDescription.JsonEncoder exposing (..)
import Url.Builder exposing (QueryParameter)


getUrl : Model -> List String -> List QueryParameter -> String
getUrl model url p =
    Url.Builder.relative (model.contextPath :: "secure" :: "api" :: url) p


getNodeDescription : Model -> Cmd Msg
getNodeDescription model =
    request
        { method = "GET"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model [ "nodes", model.nodeId.value ] []
        , body = emptyBody
        , expect = expectJson GetNodeDescription decodeGetDescription
        , timeout = Nothing
        , tracker = Nothing
        }


saveNodeDescription : Model -> Cmd Msg
saveNodeDescription model =
    request
        { method = "POST"
        , headers = [ header "X-Requested-With" "XMLHttpRequest" ]
        , url = getUrl model [ "nodes", model.nodeId.value ] []
        , body = encodeNodeDescription model |> jsonBody
        , expect = expectJson SetNodeDescription decodeSetDescription
        , timeout = Nothing
        , tracker = Nothing
        }
