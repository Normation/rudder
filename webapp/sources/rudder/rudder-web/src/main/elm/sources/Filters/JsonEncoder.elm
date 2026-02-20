module Filters.JsonEncoder exposing (..)

import Json.Encode exposing (..)

import Filters.DataTypes exposing (..)
import Tags.JsonEncoder exposing (encodeTag)

encodeFilters : Model -> Value
encodeFilters model =
  object (
    [ ( "filter" , string (String.trim model.filter) )
    , ( "tags"   , list encodeTag model.tags )
    , ( "hideUnusedTechniques", bool model.hideUnusedTechniques)
    ]
  )