port module Tags.Init exposing (..)

import Json.Decode exposing (..)
import Tags.JsonDecoder exposing (..)
import Tags.JsonEncoder exposing (..)
import Tags.Model exposing (Model, Tag, initModel)
import Tags.Update exposing (Msg(..))



-- PORTS / SUBSCRIPTIONS


port updateResult : String -> Cmd msg


port addToFilters : Value -> Cmd msg


port getFilterTags : (Value -> msg) -> Sub msg


subscriptions : Model -> Sub Msg
subscriptions model =
    getFilterTags (GetFilterTags << decodeValue (list decodeTag))


init : { contextPath : String, hasWriteRights : Bool, tags : List Tag, filterId : String, isEditForm : Bool, objectType : String, objectId : String } -> ( Model, Cmd Msg )
init flags =
    ( initModel flags, updateResult (encodeTags flags.tags) )
