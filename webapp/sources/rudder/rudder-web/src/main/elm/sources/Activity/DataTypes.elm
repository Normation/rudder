module Activity.DataTypes exposing (Activity, ActivityMsg(..), BodyParameters, ContextPath(..), EventLogFilterOrder, FilterTypes, Search, listString2FilterTypes, search2String, string2Search)

import Html.Parser exposing (Node)
import Http exposing (Error)
import Http.Detailed
import Time exposing (Posix, Zone)



--
-- All our data types
--


type ContextPath
    = ContextPath String


type alias Search =
    Maybe String


search2String : Search -> String
search2String s =
    Maybe.withDefault "" s


string2Search : String -> Search
string2Search s =
    Just s


type alias FilterTypes =
    List String


listString2FilterTypes : List String -> FilterTypes
listString2FilterTypes lstring =
    lstring


type alias BodyParameters =
    { search : Search
    , filterTypes : FilterTypes
    }


type alias Activity =
    { id : Int
    , actor : String
    , description : List Node
    , date : Posix
    }


type alias EventLogFilterOrder =
    { column : Int
    , dir : String
    , name : String
    }


type ActivityMsg
    = GetActivities (Result (Http.Detailed.Error String) ( Http.Metadata, List Activity ))
    | CopyToClipboard String
