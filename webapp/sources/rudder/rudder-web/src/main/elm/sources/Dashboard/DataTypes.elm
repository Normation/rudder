module Dashboard.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed
import Time exposing (Posix, Zone)

--
-- All our data types
--

type alias Activity =
    { id : Int
    , actor : String
    , description : String
    , actType : String
    , date : Maybe Posix
    }

type alias RestEventLogFilter =
    { draw: Int
    , start: Int
    , length: Int
    }

type alias EventLogFilterOrder =
    { column : Int
    , dir : String
    , name : String
    }

type alias Model =
    { contextPath : String
    , activities : List Activity
    , currentTime : Posix
    , zone : Zone
    }

type Msg
    = CallApi (Model -> Cmd Msg)
    | GetActivities (Result (Http.Detailed.Error String) ( Http.Metadata, (List Activity) ))
    | Tick Posix
    | Copy String