module QuickSearch.Model exposing (Model, SearchResult, SearchResultHeader, SearchResultItem, Filter(..), allKinds, allFilters, State(..), removeSelectedFilters, Kind(..), initModel, toggleSelectedFilter)

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
