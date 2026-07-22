module Activity.JsonEncoder exposing (..)

import Activity.DataTypes exposing (..)
import Json.Encode exposing (Value, int, list, object, string)


encodeRestEventLogFilter : BodyParameters -> Value
encodeRestEventLogFilter bodyParameters =
    let
        isSearchEmpty =
            search2String bodyParameters.search == ""

        isFilterTypesEmpty =
            List.isEmpty bodyParameters.filterTypes
    in
    object
        (List.filterMap identity
            [ Just ( "draw", int 1 )
            , Just ( "start", int 0 )
            , Just ( "length", int 20 )
            , Just ( "order", list encodeEventLogFilterOrder [ EventLogFilterOrder 0 "desc" "" ] )
            , if isSearchEmpty then
                Nothing

              else
                Just ( "search", object [ ( "value", string (search2String bodyParameters.search) ) ] )
            , if isFilterTypesEmpty then
                Nothing

              else
                Just ( "typeFilter", object [ ( "include", list string bodyParameters.filterTypes ) ] )
            ]
        )


encodeEventLogFilterOrder : EventLogFilterOrder -> Value
encodeEventLogFilterOrder order =
    object
        [ ( "column", int order.column )
        , ( "dir", string order.dir )
        , ( "name", string order.name )
        ]
