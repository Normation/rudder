module NodeProperties.DataTypes exposing (..)

import Http exposing (Error)
import Dict exposing (Dict)
--
-- All our data types
--

type ModalState = NoModal | Deletion String

type alias EditProperty =
  { name      : String
  , value     : String
  , format    : ValueFormat
  , pristineName  : Bool
  , pristineValue : Bool
  }

type ValueFormat = JsonFormat | StringFormat

type alias Property =
  { name      : String
  , value     : JsonValue
  , provider  : Maybe String
  , hierarchy : Maybe String
  , origval   : Maybe JsonValue
  }

type JsonValue
  = JsonString String
  | JsonInt Int
  | JsonFloat Float
  | JsonBoolean Bool
  | JsonArray (List JsonValue)
  | JsonObject (Dict String JsonValue)
  | JsonNull

type SortOrder = Asc | Desc

type SortBy
  = Name
  | Format
  | Value

type alias TableFilters =
  { sortBy    : SortBy
  , sortOrder : SortOrder
  , filter    : String
  }

type alias UI =
  { hasWriteRights   : Bool
  , hasReadRights    : Bool
  , loading          : Bool
  , modalState       : ModalState
  , editedProperties : Dict String EditProperty
  , showMore         : List String
  , filters          : TableFilters
  }

type alias Model =
  { contextPath      : String
  , nodeId           : String
  , objectType       : String
  , properties       : List Property
  , newProperty      : EditProperty
  , ui               : UI
  }

type Msg
  = Ignore
  | Copy String
  | CallApi (Model -> Cmd Msg)
  | SaveProperty (Result Error (List Property))
  | GetNodeProperties (Result Error (List Property))
  | UpdateNewProperty EditProperty
  | UpdateProperty String EditProperty
  | AddProperty
  | DeleteProperty String
  | ToggleEditPopup ModalState
  | ClosePopup Msg
  | ToggleEditProperty String EditProperty Bool
  | UpdateTableFilters TableFilters
  | ShowMore String
