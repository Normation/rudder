module Dashboard.DataTypes exposing (..)

import Http exposing (Error)
import Http.Detailed

--
-- All our data types
--

type alias Activity =
    { id : String
    , name : String
    }

type alias UI =
    { loadingActivities : Bool
    }

type alias Model =
    { contextPath : String
    , activities : List Activity
    , ui : UI
    }

type alias ApiResult =
    { activities : List Activity
    }

type Msg
    = CallApi (Model -> Cmd Msg)
    | GetActivities (Result (Http.Detailed.Error String) ( Http.Metadata, ApiResult ))