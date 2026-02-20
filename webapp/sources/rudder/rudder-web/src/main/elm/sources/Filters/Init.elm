port module Filters.Init exposing (..)

import Json.Decode exposing (..)

import Filters.DataTypes exposing (..)
import Tags.DataTypes exposing (Tag, Action, CompletionValue)
import Tags.JsonDecoder exposing (decodeTag)

-- PORTS / SUBSCRIPTIONS
port toggleTree  : String -> Cmd msg
port searchTree  : Value  -> Cmd msg
port addToFilter : (Value -> msg) -> Sub msg
port sendFilterTags  : Value  -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions model =
  addToFilter (AddToFilter << decodeValue decodeTag)

init : { contextPath : String, objectType : String } -> ( Model, Cmd Msg )
init flags =
  let
    initModel = Model flags.contextPath flags.objectType (Tag "" "") [] "" [] [] True False
  in
    ( initModel
    , Cmd.none
    )