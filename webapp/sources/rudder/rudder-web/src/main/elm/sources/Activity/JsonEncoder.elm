module Activity.JsonEncoder exposing (..)

import Activity.DataTypes exposing (..)
import Json.Encode exposing (Value, int, list, object, string)


encodeRestEventLogFilter : Search -> Int -> Value
encodeRestEventLogFilter search nbEventLogs =
    let
        isSearchEmpty =
            search2String search == ""

        byDate =
            1
    in
    object
        (List.filterMap identity
            [ Just ( "draw", int 1 )
            , Just ( "start", int 0 )
            , Just ( "length", int nbEventLogs )
            , Just ( "order", list encodeEventLogFilterOrder [ EventLogFilterOrder byDate "desc" "" ] )
            , if isSearchEmpty then
                Nothing

              else
                Just ( "search", object [ ( "value", string (search2String search) ) ] )
            ]
        )


encodeEventLogFilterOrder : EventLogFilterOrder -> Value
encodeEventLogFilterOrder order =
    object
        [ ( "column", int order.column )
        , ( "dir", string order.dir )
        , ( "name", string order.name )
        ]
