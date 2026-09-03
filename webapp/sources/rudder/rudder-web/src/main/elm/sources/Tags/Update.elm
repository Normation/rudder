module Tags.Update exposing (..)

import Http
import Json.Decode as D
import Tags.ApiCalls exposing (getCompletionTags)
import Tags.Init exposing (addToFilters, updateResult)
import Tags.JsonEncoder exposing (encodeTag, encodeTags)
import Tags.Model exposing (CompletionValue, Model, Tag)


type Action
    = Add
    | Remove


type Completion
    = Key
    | Val


type Msg
    = Ignore
    | CallApi (Model -> Cmd Msg)
    | UpdateTag Completion Tag
    | UpdateTags Action (List Tag)
    | AddToFilter Completion Tag
    | GetCompletionTags Completion (Result Http.Error (List CompletionValue))
    | GetFilterTags (Result D.Error (List Tag))


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        -- Do an API call
        CallApi call ->
            ( model, call model )

        -- neutral element
        Ignore ->
            ( model, Cmd.none )

        UpdateTag completion tag ->
            ( { model | newTag = tag }, getCompletionTags model completion )

        UpdateTags action tags ->
            let
                cmd =
                    updateResult (encodeTags tags)

                newTag =
                    case action of
                        Add ->
                            Tags.Model.Tag "" ""

                        _ ->
                            model.newTag
            in
            ( { model | tags = tags, newTag = newTag }, cmd )

        GetCompletionTags completion res ->
            case res of
                Ok l ->
                    let
                        ui =
                            model.ui

                        newUi =
                            case completion of
                                Key ->
                                    { ui | completionKeys = l }

                                Val ->
                                    { ui | completionValues = l }

                        newModel =
                            { model | ui = newUi }
                    in
                    ( newModel, Cmd.none )

                Err err ->
                    ( model, Cmd.none )

        AddToFilter c tag ->
            ( model, addToFilters (encodeTag tag) )

        GetFilterTags res ->
            case res of
                Ok l ->
                    let
                        ui =
                            model.ui

                        newUi =
                            { ui | filterTags = l }

                        newModel =
                            { model | ui = newUi }
                    in
                    ( newModel, Cmd.none )

                Err err ->
                    ( model, Cmd.none )
