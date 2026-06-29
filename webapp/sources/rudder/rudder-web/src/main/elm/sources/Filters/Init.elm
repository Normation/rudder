port module Filters.Init exposing (..)

import Filters.DataTypes exposing (..)
import Json.Decode exposing (..)
import Tags.DataTypes exposing (Action, CompletionValue, Tag)
import Tags.JsonDecoder exposing (decodeTag)



-- PORTS / SUBSCRIPTIONS


port toggleTree : String -> Cmd msg


port searchTree : Value -> Cmd msg


port addToFilter : (Value -> msg) -> Sub msg


port resetFilters : (() -> msg) -> Sub msg


port sendFilterTags : Value -> Cmd msg


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.batch
        [ addToFilter (AddToFilter << decodeValue decodeTag)
        , resetFilters (\_ -> ResetFilters)
        ]


init : { contextPath : String, objectType : String } -> ( Model, Cmd Msg )
init flags =
    let
        initModel =
            Model flags.contextPath flags.objectType (Tag "" "") [] "" [] [] True False
    in
    ( initModel
    , Cmd.none
    )
