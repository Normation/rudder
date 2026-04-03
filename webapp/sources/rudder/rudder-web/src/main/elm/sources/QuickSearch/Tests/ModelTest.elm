module QuickSearch.Tests.ModelTest exposing (suite)

import Expect
import Fuzz
import QuickSearch.Model exposing (Kind(..), State(..), allKinds, close, initModel, removeSelectedFilters, setResults, setSearch, toggleSelectedFilter)
import Test exposing (describe, fuzz, fuzz2, test)

model = initModel { contextPath = "" }

anyState = Fuzz.oneOfValues [ Opened, Closed, Searching]

anyKind : Fuzz.Fuzzer Kind
anyKind = Fuzz.oneOfValues allKinds

anySearchResultHeader
    = Fuzz.constant QuickSearch.Model.SearchResultHeader
        |> Fuzz.andMap anyKind
        |> Fuzz.andMap Fuzz.string
        |> Fuzz.andMap Fuzz.int

anySearchResultItem
    = Fuzz.constant QuickSearch.Model.SearchResultItem
        |> Fuzz.andMap anyKind
        |> Fuzz.andMap Fuzz.string
        |> Fuzz.andMap Fuzz.string
        |> Fuzz.andMap Fuzz.string
        |> Fuzz.andMap Fuzz.string
        |> Fuzz.andMap Fuzz.string

anySearchResult
    = Fuzz.constant QuickSearch.Model.SearchResult
        |> Fuzz.andMap anySearchResultHeader
        |> Fuzz.andMap (Fuzz.constant [])

suite = describe "QuickSearch.Model"
    [ test "removeSelectedFilters should remove all selected filter" <|
        \_ ->
            { model | selectedFilter = [ Parameter ] }
            |> removeSelectedFilters
            |> .selectedFilter
            |> Expect.equalLists []
    , test "toggleSelectedFilter should add filter when no previous filter" <|
        \_ ->
          model
            |> toggleSelectedFilter Parameter
            |> .selectedFilter
            |> Expect.equalLists [ Parameter ]
    , test "toggleSelectedFilter should add filter when not already member of the list" <|
        \_ ->
          { model | selectedFilter = [ Directive ] }
            |> toggleSelectedFilter Parameter
            |> .selectedFilter
            |> Expect.equalLists [ Parameter, Directive ]
    , test "toggleSelectedFilter should not add filter when already member of the list" <|
        \_ ->
          { model | selectedFilter = [ Directive, Parameter ] }
            |> toggleSelectedFilter Directive
            |> .selectedFilter
            |> Expect.equalLists [ Parameter ]
     , fuzz Fuzz.string "setSearch should change the search" <|
            \search ->
              model
                |> setSearch search
                |> .search
                |> Expect.equal search
    , fuzz anyState "setSearch with an empty search should change the state to close" <|
        \state ->
          { model | state = state }
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
    , fuzz2 anyState (Fuzz.list anySearchResult) "setResult should set the state as Opened" <|
        \state results ->
          { model | state = state }
            |> setResults results
            |> .state
            |> Expect.equal Opened
    , fuzz2 anyState (Fuzz.list anySearchResult) "setResult should set the results" <|
        \state results ->
          { model | state = state }
            |> setResults results
            |> .results
            |> Expect.equal results
     , fuzz anyState "close should change the state to Closed" <|
        \state ->
          { model | state = state }
            |> close
            |> .state
            |> Expect.equal Closed
    ]
