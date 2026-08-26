module Activity.DataTypes exposing (Activity, ActivityMsg(..), ContextPath(..), EventLogFilterOrder, FilterTypes, Id, Search, id2String, listString2FilterTypes, search2String, string2Id)

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


type alias Id =
    Maybe String


id2String : Id -> String
id2String s =
    Maybe.withDefault "" s


search2String : Id -> String
search2String s =
    Maybe.withDefault "" s


string2Id : String -> Id
string2Id s =
    Just s


type alias FilterTypes =
    List String


listString2FilterTypes : List String -> FilterTypes
listString2FilterTypes lstring =
    lstring


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
