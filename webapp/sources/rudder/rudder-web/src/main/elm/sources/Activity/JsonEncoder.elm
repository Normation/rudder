module Activity.JsonEncoder exposing (..)

import Activity.DataTypes exposing (..)
import Json.Encode exposing (Value, int, list, object, string)


encodeRestEventLogFilter : ObjectId -> Value
encodeRestEventLogFilter objectId =
    let
        isIdEmpty =
            objectId2String objectId == ""

        encodeObjectId =
            if isIdEmpty then
                Nothing

            else
                Just ( "objectId", object [ ( "value", string (objectId2String objectId) ) ] )
    in
    object
        (List.filterMap identity
            [ Just ( "draw", int 1 )
            , Just ( "start", int 0 )
            , Just ( "length", int 20 )
            , Just ( "order", list encodeEventLogFilterOrder [ EventLogFilterOrder 0 "desc" "" ] )
            , encodeObjectId
            ]
        )


encodeEventLogFilterOrder : EventLogFilterOrder -> Value
encodeEventLogFilterOrder order =
    object
        [ ( "column", int order.column )
        , ( "dir", string order.dir )
        , ( "name", string order.name )
        ]
