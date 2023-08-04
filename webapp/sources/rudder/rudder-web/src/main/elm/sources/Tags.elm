module Tags exposing (..)

import Browser
import Result
import List.Extra
import Json.Encode exposing (..)


import Tags.DataTypes exposing (..)
import Tags.Init exposing (..)
import Tags.View exposing (view)
import Tags.JsonEncoder exposing (..)
import Tags.ApiCalls exposing (getCompletionTags)

main = Browser.element
  { init          = init
  , view          = view
  , update        = update
  , subscriptions = subscriptions
  }

--
-- update loop --
--
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    -- Do an API call
    CallApi call ->
      (model, call model)

    -- neutral element
    Ignore ->
      (model , Cmd.none)

    UpdateTag completion tag ->
      ({model | newTag = tag}, (getCompletionTags model completion))

    UpdateTags action tags ->
      let
        cmd = (updateResult (encodeTags tags))
        newTag = case action of
          Add -> Tag "" ""
          _   -> model.newTag
      in
        ({model | tags = tags, newTag = newTag}, cmd)

    GetCompletionTags completion res ->
      case res of
        Ok l ->
          let
            ui = model.ui
            newUi = case completion of
              Key -> { ui | completionKeys   = l }
              Val -> { ui | completionValues = l }
            newModel = { model | ui = newUi }
          in
            (newModel, Cmd.none)
        Err err ->
          (model, Cmd.none)

    AddToFilter c tag ->
      (model, addToFilters (encodeTag tag))

    GetFilterTags res ->
       case res of
         Ok l ->
           let
             ui = model.ui
             newUi = { ui | filterTags = l }
             newModel = { model | ui = newUi }
           in
             (newModel, Cmd.none)
         Err err ->
           (model, Cmd.none)

