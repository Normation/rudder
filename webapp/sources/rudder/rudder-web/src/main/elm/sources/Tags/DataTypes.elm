module Tags.DataTypes exposing (..)

import Http exposing (Error)
import Json.Decode as D exposing (..)

--
-- All our data types
--

type alias UI =
  { hasWriteRights   : Bool
  , isEditForm       : Bool
  , objectType       : String
  , completionKeys   : List CompletionValue
  , completionValues : List CompletionValue
  , filterTags       : List Tag
  }

type alias Tag =
  { key   : String
  , value : String
  }

type alias Model =
  { contextPath : String
  , ui          : UI
  , newTag      : Tag
  , tags        : List Tag
  }

type Action = Add | Remove

type Completion = Key | Val

type alias CompletionValue =
  { value : String
  }

type Msg
  = Ignore
  | CallApi (Model -> Cmd Msg)
  | UpdateTag Completion Tag
  | UpdateTags Action (List Tag)
  | AddToFilter Completion Tag
  | GetCompletionTags Completion (Result Http.Error (List CompletionValue))
  | GetFilterTags (Result D.Error (List Tag))