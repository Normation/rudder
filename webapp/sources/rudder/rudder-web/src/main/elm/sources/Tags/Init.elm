port module Tags.Init exposing (..)

import Json.Decode exposing (..)

import Tags.DataTypes exposing (..)
import Tags.JsonEncoder exposing (..)


-- PORTS / SUBSCRIPTIONS
port updateResult : String -> Cmd msg
port addToFilters : Value  -> Cmd msg

subscriptions : Model -> Sub Msg
subscriptions model =
  Sub.none

init : { contextPath : String, hasWriteRights : Bool, tags : List Tag, filterId : String, isEditForm : Bool, objectType : String, objectId : String } -> ( Model, Cmd Msg )
init flags =
  let
    initTag   = Tag "" ""
    initUi    = UI flags.hasWriteRights flags.isEditForm flags.objectType [] []
    initModel = Model flags.contextPath initUi initTag flags.tags
  in
    ( initModel , updateResult (encodeTags flags.tags) )