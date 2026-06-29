module Dashboard.JsonEncoder exposing (..)

import Dashboard.DataTypes exposing (EventLogFilterOrder)
import Json.Encode exposing (Value, int, list, object, string)


encodeRestEventLogFilter : Value
encodeRestEventLogFilter =
    let
        eventLogFilterOrder =
            EventLogFilterOrder 0 "desc" ""

        data =
            object
                [ ( "draw", int 1 )
                , ( "start", int 0 )
                , ( "length", int 20 )
                , ( "order", list encodeEventLogFilterOrder [ eventLogFilterOrder ] )
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
