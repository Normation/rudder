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
  , format    : Format
  , pristineName  : Bool
  , pristineValue : Bool
  }

type Format = JsonFormat | StringFormat

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

type alias UI =
  { hasWriteRights   : Bool
  , hasReadRights    : Bool
  , loading          : Bool
  , modalState       : ModalState
  , editedProperties : Dict String EditProperty
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
  | CallApi (Model -> Cmd Msg)
  | SaveProperty (Result Error (List Property))
  | GetNodeProperties (Result Error (List Property))
  | SaveChanges
  | UpdateNewProperty EditProperty
  | UpdateProperty String EditProperty
  | AddProperty
  | DeleteProperty String
  | ToggleEditPopup ModalState
  | ToggleEditProperty String EditProperty Bool
