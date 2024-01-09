module QuickSearch.Update exposing (..)

import Debounce
import Http.Detailed as Detailed
import List.Extra
import QuickSearch.ApiCalls exposing (getSearchResult)
import QuickSearch.Datatypes exposing (..)
import QuickSearch.Init exposing (debounceConfig)
import QuickSearch.JsonDecoder exposing (decodeErrorDetails)
import QuickSearch.Port exposing (errorNotification)

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
      GetResults (Ok (_, r)) ->
        ({model| results = r, state = Opened},Cmd.none)
      GetResults (Err e) ->
        ({model | results = [] }, processApiError "getting search results" e)
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


processApiError : String -> Detailed.Error String -> Cmd Msg
processApiError apiName err =
  let
    formatApiNameMessage msg = "Error when "++ apiName ++ " : \n" ++ msg
    message =
      case err of
        Detailed.BadUrl url ->
          formatApiNameMessage ("The URL " ++ url ++ " was invalid")
        Detailed.Timeout ->
          formatApiNameMessage ("Unable to reach the server, try again")
        Detailed.NetworkError ->
          formatApiNameMessage ("Unable to reach the server, check your network connection")
        Detailed.BadStatus metadata body ->
          let
            (title, errors) = decodeErrorDetails body
          in
            title ++ "\n" ++ errors
        Detailed.BadBody metadata body msg ->
          formatApiNameMessage msg
  in
    errorNotification message
