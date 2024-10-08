module NodeProperties.ApiCalls exposing (..)

import Http exposing (..)
import Json.Decode exposing (at, list)
import QuickSearch.JsonDecoder exposing (decoderResult)
import Url.Builder exposing (QueryParameter, int, string)

import NodeProperties.DataTypes exposing (..)
import NodeProperties.JsonDecoder exposing (..)
import NodeProperties.JsonEncoder exposing (..)


getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: (m.objectType ++ "s") :: m.nodeId :: url) p

getInheritedProperties : Model -> Cmd Msg
getInheritedProperties model =
  let 
    decoder = if model.objectType == "node" then decodeGetInheritedProperties else decodeGetGroupInheritedProperties
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "displayInheritedProperties" ] []
        , body    = emptyBody
        , expect  = expectJson GetInheritedProperties decoder
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
    queryFilter = "is:Directive,Technique in:dir_param_value,technique_method_value "
    property = "${node.properties[" ++ propertyName ++ "]"
    param = string "value" (queryFilter ++ property)
    paramLimit = int "limit" 1000
    urlTest = Url.Builder.relative (model.contextPath :: "secure" :: "api"  :: "quicksearch" :: []) [ param, paramLimit ]
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = urlTest
        , body    = emptyBody
        , expect  = expectJson (FindPropertyUsage propertyName) (at ["data"] (list decoderResult))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req