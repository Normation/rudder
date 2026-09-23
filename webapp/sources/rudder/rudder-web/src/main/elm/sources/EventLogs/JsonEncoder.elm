module EventLogs.JsonEncoder exposing (..)

import EventLogs.DataTypes exposing (..)
import Json.Encode exposing (Value, int, list, object, string)


encodeRestEventLogFilter : ObjectId -> Int -> Value
encodeRestEventLogFilter objectId nbEventLogs =
    let
        isObjectIdEmpty =
            objectId2String objectId == ""

        byDate =
            1
    in
    object
        (List.filterMap identity
            [ Just ( "draw", int 1 )
            , Just ( "start", int 0 )
            , Just ( "length", int nbEventLogs )
            , Just ( "order", list encodeEventLogFilterOrder [ EventLogFilterOrder byDate "desc" "" ] )
            , if isObjectIdEmpty then
                Nothing

              else
                Just ( "objectId", object [ ( "value", string (objectId2String objectId) ) ] )
            ]
        )


encodeEventLogFilterOrder : EventLogFilterOrder -> Value
encodeEventLogFilterOrder order =
    object
        [ ( "column", int order.column )
        , ( "dir", string order.dir )
        , ( "name", string order.name )
        ]
