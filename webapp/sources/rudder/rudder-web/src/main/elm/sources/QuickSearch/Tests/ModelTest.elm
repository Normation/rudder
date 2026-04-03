module QuickSearch.Tests.ModelTest exposing (suite)

import Expect
import Fuzz
import QuickSearch.Model exposing (Kind(..), State(..), initModel, removeSelectedFilters, setSearch, toggleSelectedFilter)
import Test exposing (describe, fuzz, test)

model = initModel { contextPath = "" }

suite = describe "QuickSearch.Model"
    [ test "removeSelectedFilters should remove all selected filter" <|
        \_ ->
            model
            |> toggleSelectedFilter Parameter
            |> removeSelectedFilters
            |> .selectedFilter
            |> Expect.equalLists []
    , test "toggleSelectedFilter should add filter when no previous filter" <|
        \_ ->
          model
            |> toggleSelectedFilter Parameter
            |> .selectedFilter
            |> Expect.equalLists [Parameter]
    , test "toggleSelectedFilter should add filter when not already member of the list" <|
        \_ ->
          model
            |> toggleSelectedFilter Directive
            |> toggleSelectedFilter Parameter
            |> .selectedFilter
            |> Expect.equalLists [Parameter, Directive]
    , test "toggleSelectedFilter should not add filter when already member of the list" <|
        \_ ->
          model
            |> toggleSelectedFilter Directive
            |> toggleSelectedFilter Parameter
            |> toggleSelectedFilter Directive
            |> .selectedFilter
            |> Expect.equalLists [ Parameter ]
    , test "setSearch with an empty search should change the state to close" <|
        \_ ->
          model
            |> setSearch ""
            |> .state
            |> Expect.equal Closed
    , fuzz (Fuzz.stringOfLengthBetween 1 3) "setSearch with less than 3 characters should change the state to Opened" <|
        \search ->
          model
            |> setSearch search
            |> .state
            |> Expect.equal Opened
    , fuzz (Fuzz.stringOfLengthBetween 4 100) "setSearch with more than 3 characters should change the state to Searching" <|
        \search ->
          model
            |> setSearch search
            |> .state
            |> Expect.equal Searching
    ]
