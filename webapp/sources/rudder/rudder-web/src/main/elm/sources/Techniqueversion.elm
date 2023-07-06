module Techniqueversion exposing (..)

import Browser
import Dict
import Dict.Extra
import Http exposing (..)
import Http.Detailed as Detailed
import Result
import List.Extra
import Random
import UUID
import Json.Encode exposing (..)
import Task


import TechniqueVersion.DataTypes exposing (..)
import TechniqueVersion.Init exposing (..)
import TechniqueVersion.View exposing (view)

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

    Ignore txt ->
      (model, errorNotification txt)

    Create version ->
      (model , (createDirective version))

    ToggleDeprecated check ->
      let
        ui = model.ui
        newModel = { model | ui = { ui | displayDeprecated = check } }
      in
        (newModel, initTooltips "")