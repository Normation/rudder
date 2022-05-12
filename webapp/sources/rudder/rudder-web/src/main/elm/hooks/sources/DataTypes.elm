module DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed

--
-- All our data types
--

type SystemFilter = All | System  | Custom
type StateFilter  = Any | Enabled | Disabled

type alias UI =
  { filters         : Filters
  , hasWriteRights  : Bool
  , loading         : Bool
  }

type alias Filters =
  { filter  : String
  , system  : SystemFilter
  , state   : StateFilter
  }

type alias ApiResult =
  { root       : String
  , categories : List Category
  }

type Kind = Node | Policy | Other

type alias Category =
  { name  : String
  , kind  : Kind
  , hooks : List Hook
  }

type alias Hook =
  { name : String
  }

type alias Model =
  { contextPath : String
  , ui          : UI
  , root        : String
  , categories  : List Category
  }

type Msg
  = Copy String
  | CallApi (Model -> Cmd Msg)
  | GetHooksResult (Result (Http.Detailed.Error String) ( Http.Metadata, ApiResult))
  | Ignore
  | UpdateFilters Filters