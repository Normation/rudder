module QuickSearch.Tests.ModelTest exposing (suite)

import Expect
import QuickSearch.Model exposing (Kind(..), toggleSelectedFilter, initModel, removeSelectedFilters)
import Test exposing (describe, test)

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
    ]
