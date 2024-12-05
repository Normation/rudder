module Nodes.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)

import Nodes.DataTypes exposing (..)
import Nodes.JsonDecoder exposing (..)
import Nodes.JsonEncoder exposing (..)


getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: url ) p

getNodes : Model -> Cmd Msg
getNodes model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "nodes" , "details"] []
        , body    = emptyBody
        , expect  = expectJson GetNodes decodeGetNodes
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

getNodeDetails : Model ->  Cmd Msg
getNodeDetails model =
  let
    changeAction = "Disable "
    req =
      request
        { method  = "POST"
        , headers = []
        , url     = getUrl model [ "nodes" , "details"] []
        , body    = encodeDetails model |> jsonBody
        , expect  = expectJson GetNodes decodeGetNodeDetails
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req