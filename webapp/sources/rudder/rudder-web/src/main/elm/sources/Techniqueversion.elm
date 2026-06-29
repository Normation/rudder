module Techniqueversion exposing (..)

import Browser
import Dict
import Dict.Extra
import Http exposing (..)
import Http.Detailed as Detailed
import Json.Encode exposing (..)
import List.Extra
import Random
import Result
import Task
import TechniqueVersion.DataTypes exposing (..)
import TechniqueVersion.Init exposing (..)
import TechniqueVersion.View exposing (view)
import UUID


main =
    Browser.element
        { init = init
        , view = view
        , update = update
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
            ( model, call model )

        Ignore txt ->
            ( model, errorNotification txt )

        Create version ->
            ( model, createDirective version )

        ToggleDeprecated check ->
            let
                ui =
                    model.ui

                newModel =
                    { model | ui = { ui | displayDeprecated = check } }
            in
            ( newModel, initTooltips "" )
