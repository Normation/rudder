module Activity.JsonEncoder exposing (..)

import Activity.DataTypes exposing (EventLogFilterOrder, FilterType, Search(..))
import Json.Encode exposing (Value, int, list, object, string)


encodeRestEventLogFilter : Search -> List String -> Value
encodeRestEventLogFilter (Search search) filterType =
    let
        eventLogFilterOrder =
            EventLogFilterOrder 0 "desc" ""

        data =
            object
                [ ( "draw", int 1 )
                , ( "start", int 0 )
                , ( "length", int 20 )
                , ( "order", list encodeEventLogFilterOrder [ eventLogFilterOrder ] )
                , ( "search", object [ ( "value", string search ) ] )
                , ( "typeFilter", object [ ( "include", list string filterType ) ] )
                ]
    in
    data


encodeEventLogFilterOrder : EventLogFilterOrder -> Value
encodeEventLogFilterOrder order =
    object
        [ ( "column", int order.column )
        , ( "dir", string order.dir )
        , ( "name", string order.name )
        ]
