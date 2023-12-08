module NodeProperties.DataTypes exposing (..)

import Http exposing (Error)
import Dict exposing (Dict)
import Json.Encode exposing (Value)
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
  , errorFormat : Bool
  }

type ValueFormat = JsonFormat | StringFormat

type alias Property =
  { name      : String
  , value     : Value
  , provider  : Maybe String
  , hierarchy : Maybe String
  , origval   : Maybe Value
  }


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
  { hasNodeWrite     : Bool
  , hasNodeRead      : Bool
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
  | SaveProperty String (Result Error (List Property))
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
