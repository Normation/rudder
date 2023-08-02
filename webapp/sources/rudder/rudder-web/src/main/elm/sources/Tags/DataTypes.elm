module Tags.DataTypes exposing (..)

import Http exposing (Error)

--
-- All our data types
--

type alias UI =
  { hasWriteRights   : Bool
  , isEditForm       : Bool
  , objectType       : String
  , completionKeys   : List CompletionValue
  , completionValues : List CompletionValue
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
  | AddToFilter Tag
  | GetCompletionTags Completion (Result Error (List CompletionValue))