module QuickSearch.Tests.UpdateTest exposing (suite)

import Debounce exposing (manual, soon)
import Expect
import Http.Detailed as Detailed
import QuickSearch.Model exposing (Filter(..), Kind(..), initModel)
import QuickSearch.Update exposing (Effect(..), Msg(..), update, update_)
import Test exposing (describe, test)

model = initModel { contextPath = "" }

config = { strategy = manual
         , transform = identity
         }

suite = describe "QuickSearch.Update"
    [ test "should toggle filter when UpdateFilter with FilterKind" <|
        \_ ->
             model
                |> update_ (UpdateFilter <| FilterKind <| Parameter)
                |> Tuple.first
                |> .selectedFilter
                |> Expect.equalLists [ Parameter ]
    , test "should reset filters when UpdateFilter All" <|
        \_ ->
             { model | selectedFilter = [ Parameter, Directive ] }
                |> update_ (UpdateFilter <| All)
                |> Tuple.first
                |> .selectedFilter
                |> Expect.equalLists []
    , test "should emit an error command on GetResults error" <|
        \_ ->
             model
                |> update_ (GetResults <| Err <| Detailed.Timeout)
                |> Tuple.second
                |> Expect.equal (ErrorNotification "Error when getting search results : \nUnable to reach the server, try again")
    , test "should push a search to the debouncer on UpdateSearch" <|
        \_ ->
            model
                |> update_ (UpdateSearch "foo")
                |> Tuple.second
                |> Expect.equal (DebouncePush "foo")
    ]