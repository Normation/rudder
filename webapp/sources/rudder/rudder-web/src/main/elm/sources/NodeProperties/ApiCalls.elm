module NodeProperties.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)
import Json.Encode exposing (Value)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.JsonDecoder exposing (..)
import NodeProperties.JsonEncoder exposing (..)


getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: (m.objectType ++ "s") :: m.nodeId :: url) p

getNodeProperties : Model -> Cmd Msg
getNodeProperties model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "displayInheritedProperties" ] []
        , body    = emptyBody
        , expect  = expectJson GetNodeProperties decodeGetProperties
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveProperty : EditProperty -> Model -> Cmd Msg
saveProperty property model =
  let
    req =
      request
        { method  = "POST"
        , headers = []
        , url     = getUrl model [] []
        , body    = encodeProperty model property "Add" |> jsonBody
        , expect  = expectJson SaveProperty decodeSaveProperties
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

deleteProperty : EditProperty -> Model -> Cmd Msg
deleteProperty property model =
  let
    req =
      request
        { method  = "POST"
        , headers = []
        , url     = getUrl model [] []
        , body    = encodeProperty model property "Delete" |> jsonBody
        , expect  = expectJson SaveProperty decodeSaveProperties
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req