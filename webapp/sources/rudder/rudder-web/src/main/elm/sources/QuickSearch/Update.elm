module QuickSearch.Update exposing (..)

import Debounce
import List.Extra
import QuickSearch.ApiCalls exposing (getSearchResult)
import QuickSearch.Datatypes exposing (..)
import QuickSearch.Init exposing (debounceConfig)

update : Msg -> Model -> ( Model, Cmd Msg )
update  msg model =
  case msg of
      UpdateFilter filter->
          case filter of
              All ->
                ({model| selectedFilter = []},Cmd.none)
              FilterKind k ->
                  let
                   updatedFilter = if List.member k model.selectedFilter then List.Extra.remove k model.selectedFilter else k :: model.selectedFilter
                  in
                    ({model| selectedFilter = updatedFilter },Cmd.none)
      UpdateSearch search->
        let
          state = if String.isEmpty search then Closed
                  else if String.length search <= 3 then Opened
                  else Searching
          (debounce, cmd) =
            Debounce.push debounceConfig search model.debounceSearch


          newModel = {model| search = search, state = state, debounceSearch = debounce}
        in
          (newModel,cmd)
      GetResults (Ok r) ->
        ({model| results = r, state = Opened},Cmd.none)
      GetResults (Err e) ->
        (model, Cmd.none)
      Close ->
        ({model | state = Closed }, Cmd.none)
      Open ->
        let
          newState =
            if (String.isEmpty model.search) then Closed
            else
              case model.state of
                Searching -> Searching
                _ -> Opened
        in
          ({model | state = newState }, Cmd.none)
      DebounceMsg debMsg ->
          let
            ( debounce, cmd ) =
              Debounce.update
                  debounceConfig
                  (Debounce.takeLast (
                    \s ->  if String.length s > 2  then getSearchResult model s else Cmd.none
                  ))
                  debMsg
                  model.debounceSearch
          in
            ({model | debounceSearch = debounce}, cmd)