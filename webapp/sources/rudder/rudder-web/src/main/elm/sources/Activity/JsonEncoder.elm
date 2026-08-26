module Activity.JsonEncoder exposing (..)

import Activity.DataTypes exposing (..)
import Json.Encode exposing (Value, int, list, object, string)


encodeRestEventLogFilter : Id -> Value
encodeRestEventLogFilter id =
    let
        isIdEmpty =
            id2String id == ""
    in
    object
        (List.filterMap identity
            [ Just ( "draw", int 1 )
            , Just ( "start", int 0 )
            , Just ( "length", int 20 )
            , Just ( "order", list encodeEventLogFilterOrder [ EventLogFilterOrder 0 "desc" "" ] )
            , if isIdEmpty then
                Nothing

              else
                Just ( "id", object [ ( "value", string (id2String id) ) ] )
            ]
        )


encodeEventLogFilterOrder : EventLogFilterOrder -> Value
encodeEventLogFilterOrder order =
    object
        [ ( "column", int order.column )
        , ( "dir", string order.dir )
        , ( "name", string order.name )
        ]
