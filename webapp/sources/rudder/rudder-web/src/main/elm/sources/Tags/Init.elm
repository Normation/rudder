module Tags.Init exposing (..)

import Tags.JsonEncoder exposing (..)
import Tags.Model exposing (Model, Tag, initModel)
import Tags.Update exposing (Msg(..), updateResult)


init : { contextPath : String, hasWriteRights : Bool, tags : List Tag, filterId : String, isEditForm : Bool, objectType : String, objectId : String } -> ( Model, Cmd Msg )
init flags =
    ( initModel flags, updateResult (encodeTags flags.tags) )
