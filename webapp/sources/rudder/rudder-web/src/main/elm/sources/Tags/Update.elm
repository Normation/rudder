port module Tags.Update exposing (..)

import Http
import Json.Decode as D exposing (Value, decodeValue, list)
import List.Extra
import Tags.ApiCalls exposing (getCompletionTags)
import Tags.JsonDecoder exposing (decodeTag)
import Tags.JsonEncoder exposing (encodeTag, encodeTags)
import Tags.Model exposing (Completion(..), CompletionValue, Model, Tag, emptyTag, setNewTag, updateTags)



-- PORTS / SUBSCRIPTIONS


port updateResult : String -> Cmd msg


port addToFilters : Value -> Cmd msg


port getFilterTags : (Value -> msg) -> Sub msg


subscriptions : Model -> Sub Msg
subscriptions model =
    getFilterTags (GetFilterTags << decodeValue (list decodeTag))


type Action
    = Add Tag
    | Remove Tag
    | Clear


type Msg
    = Ignore
    | CallApi (Model -> Cmd Msg)
    | UpdateTag Completion Tag
    | UpdateTags Action
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
            ( model |> setNewTag tag
            , getCompletionTags model completion (GetCompletionTags completion)
            )

        UpdateTags action ->
            let
                (tags, newTag) =
                    case action of
                        Add tag ->
                            (tag :: model.tags, emptyTag)

                        Remove tag ->
                            (List.Extra.remove tag model.tags, model.newTag)

                        Clear ->
                            ([], model.newTag)

                cmd =
                    updateResult (encodeTags tags)

            in
            ( model |> updateTags tags newTag, cmd )

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
