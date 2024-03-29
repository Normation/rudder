module NodeProperties.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.JsonDecoder exposing (..)
import NodeProperties.JsonEncoder exposing (..)


getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: (m.objectType ++ "s") :: m.nodeId :: url) p

getNodeProperties : Model -> Cmd Msg
getNodeProperties model =
  let
    decoder = if model.objectType == "node" then decodeGetProperties else decodeGetGroupProperties
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "displayInheritedProperties" ] []
        , body    = emptyBody
        , expect  = expectJson GetNodeProperties decoder
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

saveProperty : List EditProperty -> Model -> String -> Cmd Msg
saveProperty properties model successMsg =
  let
    decoder = if model.objectType == "node" then decodeSaveProperties else decodeSaveGroupProperties
    req =
      request
        { method  = "POST"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [] []
        , body    = encodeProperty model properties "Add" |> jsonBody
        , expect  = expectJson (SaveProperty successMsg) decoder
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

deleteProperty : EditProperty -> Model -> Cmd Msg
deleteProperty property model =
  let
    successMsg = "property '" ++ property.name ++ "' has been removed"
    decoder = if model.objectType == "node" then decodeSaveProperties else decodeSaveGroupProperties
    req =
      request
        { method  = "POST"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [] []
        , body    = encodeProperty model [property] "Delete" |> jsonBody
        , expect  = expectJson (SaveProperty successMsg) decoder
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req

findPropertyUsage : String -> Model -> Cmd Msg
findPropertyUsage propertyName model =
  let
    -- TODO: change when the URL path is defined
    urlTest = Url.Builder.relative (model.contextPath :: "secure" :: "api"  :: "nodes" :: "details" :: "property" :: "usage" :: propertyName :: []) []
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = urlTest
        , body    = emptyBody
        , expect  = expectJson (FindPropertyUsage propertyName) decodeFindPropertyUsage
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req
