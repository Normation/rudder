module Activity.DataTypes exposing (Activity, ActivityMsg(..), ContextPath(..), EventLogFilterOrder, FilterTypes, ObjectId, Search, listString2FilterTypes, objectId2String, search2String, string2ObjectId)

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



-- TODO delete ?


type alias ObjectId =
    Maybe String


objectId2String : ObjectId -> String
objectId2String s =
    Maybe.withDefault "" s


search2String : ObjectId -> String
search2String s =
    Maybe.withDefault "" s


string2ObjectId : String -> ObjectId
string2ObjectId s =
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
