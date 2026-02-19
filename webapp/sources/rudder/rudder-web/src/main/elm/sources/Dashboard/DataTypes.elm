module Dashboard.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed

--
-- All our data types
--

type alias Activity =
    { id : String
    , description : String
    , date : String
    }

type alias UI =
    { loadingActivities : Bool
    }

{--
final case class RestEventLogFilter(
    draw:      Int,
    start:     Int,
    length:    Int,
    search:    Option[EventLogRequest.Search],
    startDate: Option[LocalDateTime],
    endDate:   Option[LocalDateTime],
    principal: Option[EventLogRequest.PrincipalFilter],
    order:     Chunk[EventLogRequest.Order]
)
--}
type alias RestEventLogFilter =
    { draw: Int
    , start: Int
    , length: Int
    }

type alias Model =
    { contextPath : String
    , activities : List Activity
    , ui : UI
    }

type Msg
    = CallApi (Model -> Cmd Msg)
    | GetActivities (Result (Http.Detailed.Error String) ( Http.Metadata, (List Activity) ))