module QuickSearch.Model exposing (Model, SearchResult, SearchResultHeader, SearchResultItem, Filter(..), allKinds, allFilters, State(..), removeSelectedFilters, Kind(..), initModel, toggleSelectedFilter, setSearch, setDebounce, setResults, close, open)

import Debounce exposing (Debounce)

type alias Model =
  { search : String,
    results : List SearchResult,
    selectedFilter : List Kind,
    contextPath : String,
    state : State,
    debounceSearch : Debounce String
  }

type alias SearchResult =
  { header : SearchResultHeader
  , items : List SearchResultItem
  }
type alias SearchResultHeader =
  { type_ : Kind
  , summary : String
  , numbers : Int
  }
type alias SearchResultItem =
  { type_ : Kind
  , name : String
  , id : String
  , value : String
  , desc : String
  , url : String
  }

type Filter = All | FilterKind Kind
type Kind = Node | Group | Parameter | Directive | Rule | Technique

allKinds : List Kind
allKinds = [Node, Group, Parameter, Directive, Rule, Technique]

allFilters : List Filter
allFilters = List.map FilterKind allKinds

type State = Opened | Searching | Closed

initModel: { contextPath : String } -> Model
initModel { contextPath } =
    { search = ""
    , results = []
    , selectedFilter = []
    , contextPath = contextPath
    , state = Closed
    , debounceSearch = Debounce.init
    }

removeSelectedFilters: Model -> Model
removeSelectedFilters model =
    { model
        | selectedFilter = []
    }

toggleSelectedFilter: Kind -> Model -> Model
toggleSelectedFilter kind model =
    { model
        | selectedFilter =
            if List.member kind model.selectedFilter
            then List.filter ((/=) kind) model.selectedFilter
            else kind :: model.selectedFilter
    }

setSearch : String -> Model -> Model
setSearch search model =
    {model
        | search = search
        , state = if String.isEmpty search then Closed
                  else if String.length search <= 3 then Opened
                  else Searching
    }

setDebounce : Debounce String -> Model -> Model
setDebounce debounce model =
    { model
        | debounceSearch = debounce
    }

setResults : List SearchResult -> Model -> Model
setResults results model =
    { model
        | results = results
        , state = Opened
    }

close : Model -> Model
close model =
    { model
        | state = Closed
    }


open : Model -> Model
open model =
    { model
        | state = if (String.isEmpty model.search)
                  then Closed
                  else
                    case model.state of
                      Searching -> Searching
                      _ -> Opened
    }