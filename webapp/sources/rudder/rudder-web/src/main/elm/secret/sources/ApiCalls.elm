module ApiCalls exposing (..)

import DataTypes exposing (Model, Msg(..), Secret)
import Http exposing (emptyBody, expectJson, jsonBody, request, send)
import JsonDecoder exposing (decodeDeleteSecret, decodeGetAllSecrets, decodeOneSecret)
import JsonEncoder exposing (encodeSecret)

getUrl: DataTypes.Model -> String -> String
getUrl m parameter =
  m.contextPath ++ "/secure/api/latest/secret" ++ parameter

getSecret : Model -> String -> Cmd Msg
getSecret model secretName =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model secretName
        , body            = emptyBody
        , expect          = expectJson decodeOneSecret
        , timeout         = Nothing
        , withCredentials = False
        }
 in
 send GetSecret req

getAllSecrets : Model -> Cmd Msg
getAllSecrets model =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model ""
        , body            = emptyBody
        , expect          = expectJson decodeGetAllSecrets
        , timeout         = Nothing
        , withCredentials = False
        }
  in
  send GetAllSecrets req

deleteSecret : String -> Model -> Cmd Msg
deleteSecret secretName model =
  let
    req =
      request
        { method          = "DELETE"
        , headers         = []
        , url             = getUrl model secretName
        , body            = emptyBody
        , expect          = expectJson decodeDeleteSecret
        , timeout         = Nothing
        , withCredentials = False
        }
  in
  send DeleteSecret req

addSecret : Model -> Secret -> Cmd Msg
addSecret model secret =
  let
    req =
      request
        { method          = "PUT"
        , headers         = []
        , url             = getUrl model ""
        , body            = jsonBody (encodeSecret secret)
        , expect          = expectJson decodeOneSecret
        , timeout         = Nothing
        , withCredentials = False
        }
  in
  send AddSecret req

updateSecret : Model -> Secret -> Cmd Msg
updateSecret model secret =
  let
    req =
      request
        { method          = "POST"
        , headers         = []
        , url             = getUrl model ""
        , body            = jsonBody (encodeSecret secret)
        , expect          = expectJson decodeOneSecret
        , timeout         = Nothing
        , withCredentials = False
        }
  in
  send UpdateSecret req