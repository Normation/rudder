module Filters.DataTypes exposing (..)

import Tags.DataTypes exposing (..)

import Http exposing (Error)
import Json.Decode as D exposing (..)
--
-- All our data types
--

type alias Model =
  { contextPath : String
  , objectType  : String
  , newTag      : Tag
  , tags        : List Tag
  , filter      : String
  , completionKeys   : List CompletionValue
  , completionValues : List CompletionValue
  , showMore    : Bool
  , hideUnusedTechniques : Bool
  }

type Msg
  = IgnoreAdd Tag
  | CallApi (Model -> Cmd Msg)
  | ToggleTree
  | UpdateFilter String
  | ShowMore
  | AddToFilter (Result D.Error Tag)
  | UpdateTag Completion Tag
  | UpdateTags Action (List Tag)
  | GetCompletionTags Completion (Result Http.Error (List CompletionValue))
  | ToggleHideUnusedTechniques Bool
