module QuickSearch.Datatypes exposing (..)



import Debounce exposing (Debounce)
import Http exposing (Error)
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
type Kind = Node | Group | Parameter | Directive | Rule

allKinds : List Kind
allKinds = [Node, Group, Parameter, Directive, Rule]

allFilters : List Filter
allFilters = List.map FilterKind allKinds

type State = Opened | Searching | Closed

type Msg = UpdateFilter Filter
  | UpdateSearch String
  | GetResults (Result Error (List SearchResult))
  | DebounceMsg Debounce.Msg
  | Close
  | Open