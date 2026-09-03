module Tags.DataTypes exposing (..)

import Http exposing (Error)
import Json.Decode as D exposing (..)
import Tags.Model exposing (CompletionValue, Model, Tag)



--
-- All our data types
--


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
