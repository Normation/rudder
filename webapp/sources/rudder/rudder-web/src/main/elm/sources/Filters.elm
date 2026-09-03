module Filters exposing (..)

import Browser
import Filters.ApiCalls exposing (..)
import Filters.DataTypes exposing (..)
import Filters.Init exposing (..)
import Filters.JsonEncoder exposing (..)
import Filters.View exposing (view)
import Json.Encode exposing (..)
import List.Extra
import Tags.JsonEncoder exposing (..)
import Tags.Model exposing (Completion, Tag, emptyTag)
import Tags.Update exposing (Action)


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

        IgnoreAdd t ->
            ( model, Cmd.none )

        ToggleTree ->
            ( model, toggleTree "" )

        UpdateFilter str ->
            let
                newModel =
                    { model | filter = str }

                encodedFilters =
                    encodeFilters newModel
            in
            ( newModel, searchTree encodedFilters )

        ShowMore ->
            ( { model | showMore = not model.showMore }, Cmd.none )

        AddToFilter res ->
            case res of
                Ok tag ->
                    let
                        newModel =
                            { model | tags = tag :: model.tags }

                        encodedFilters =
                            encodeFilters newModel
                    in
                    ( newModel, searchTree encodedFilters )

                Err _ ->
                    ( model, Cmd.none )

        UpdateTag completion tag ->
            ( { model | newTag = tag }, getCompletionTags model completion )

        UpdateTags action ->
            let
                (tags, newTag) =
                    case action of
                        Tags.Update.Add tag ->
                            (tag :: model.tags, emptyTag)

                        Tags.Update.Remove tag ->
                            (List.Extra.remove tag model.tags, model.newTag)

                        Tags.Update.Clear ->
                            ([], model.newTag)

                newModel =
                    { model | tags = tags, newTag = newTag }

                encodedFilters =
                    encodeFilters newModel

                encodedTags =
                    list encodeTag newModel.tags
            in
            ( newModel, Cmd.batch [ searchTree encodedFilters, sendFilterTags encodedTags ] )

        GetCompletionTags completion res ->
            case res of
                Ok l ->
                    let
                        newModel =
                            case completion of
                                Tags.Model.Key ->
                                    { model | completionKeys = l }

                                Tags.Model.Val ->
                                    { model | completionValues = l }
                    in
                    ( newModel, Cmd.none )

                Err err ->
                    ( model, Cmd.none )

        ToggleHideUnusedTechniques newHideUnusedTechniques ->
            let
                newModel =
                    { model | hideUnusedTechniques = newHideUnusedTechniques }

                encodedFilters =
                    encodeFilters newModel
            in
            ( newModel, searchTree encodedFilters )

        ResetFilters ->
            let
                ( prevModel, updateTags ) =
                    update (UpdateTags Tags.Update.Clear) model

                ( newModel, updateFilter ) =
                    update (UpdateFilter "") prevModel
            in
            ( newModel, Cmd.batch [ updateTags, updateFilter ] )
