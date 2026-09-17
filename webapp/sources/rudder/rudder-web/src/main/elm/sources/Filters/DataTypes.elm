module Filters.DataTypes exposing (..)

import Http exposing (Error)
import Json.Decode as D exposing (..)
import List.Extra
import Tags.Model exposing (..)
import Tags.Update exposing (Action)



--
-- All our data types
--


type alias Model =
    { contextPath : String
    , objectType : String
    , newTag : Tag
    , tags : List Tag
    , filter : String
    , completionKeys : List CompletionValue
    , completionValues : List CompletionValue
    , showMore : Bool
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
    | UpdateTags Action
    | GetCompletionTags Completion (Result Http.Error (List CompletionValue))
    | ToggleHideUnusedTechniques Bool
    | ResetFilters



-- setTags tag model
-- asTagsIn model tag


addTag : Tag -> Model -> Model
addTag tag model =
    if List.member tag model.tags then
        model

    else
        { model | tags = tag :: model.tags }


removeTag : Tag -> Model -> Model
removeTag tag model =
    { model | tags = List.Extra.remove tag model.tags }

clearTags : Model -> Model
clearTags model =
    { model | tags = [] }

setCurrentTag : Tag -> Model -> Model
setCurrentTag tag model =
    { model | newTag = tag }
