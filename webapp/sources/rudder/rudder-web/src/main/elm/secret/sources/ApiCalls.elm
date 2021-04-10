module ApiCalls exposing (..)

import DataTypes exposing (Model, Msg(..), Secret)
import Http exposing (emptyBody, expectJson, jsonBody, request, send)
import JsonDecoder exposing (decodeSecretsApi)
import JsonEncoder exposing (encodeSecret)

getUrl: DataTypes.Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api" ++ url

getSecret : Model -> String -> Cmd Msg
getSecret model secretName =
  let
    req =
      request
        { method          = "GET"
        , headers         = []
        , url             = getUrl model ("/secret/" ++ secretName)
        , body            = emptyBody
        , expect          = expectJson decodeSecretsApi
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
        , url             = getUrl model "/secret"
        , body            = emptyBody
        , expect          = expectJson decodeSecretsApi
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
        , url             = getUrl model ("/secret/" ++ secretName)
        , body            = emptyBody
        , expect          = expectJson decodeSecretsApi
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
        , url             = getUrl model "/secret"
        , body            = jsonBody (encodeSecret secret)
        , expect          = expectJson decodeSecretsApi
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
        , url             = getUrl model "/secret"
        , body            = jsonBody (encodeSecret secret)
        , expect          = expectJson decodeSecretsApi
        , timeout         = Nothing
        , withCredentials = False
        }
  in
  send UpdateSecret req