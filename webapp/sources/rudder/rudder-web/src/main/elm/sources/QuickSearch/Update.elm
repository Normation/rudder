module QuickSearch.Update exposing (update, Msg(..))

import Debounce
import Http
import Http.Detailed as Detailed
import QuickSearch.ApiCalls exposing (getSearchResult)
import QuickSearch.Model exposing (..)
import QuickSearch.JsonDecoder exposing (decodeErrorDetails)
import QuickSearch.Port exposing (errorNotification)

debounceConfig : Debounce.Config Msg
debounceConfig =
  { strategy = Debounce.later 500
  , transform = DebounceMsg
  }

type Msg = UpdateFilter Filter
  | UpdateSearch String
  | GetResults (Result (Detailed.Error String) (Http.Metadata, List SearchResult))
  | DebounceMsg Debounce.Msg
  | Close
  | Open

update : Msg -> Model -> ( Model, Cmd Msg )
update  msg model =
  case msg of
      UpdateFilter filter ->
          case filter of
              All -> (model |> removeSelectedFilters, Cmd.none)
              FilterKind k -> (model |> toggleSelectedFilter k, Cmd.none)
      UpdateSearch search ->
        let
          (debounce, cmd) = Debounce.push debounceConfig search model.debounceSearch
        in
          ( model |> setDebounce debounce |> setSearch search
          , cmd
          )

      GetResults (Ok (_, r)) ->
        (model |> setResults r, Cmd.none)
      GetResults (Err e) ->
        (model |> setResults [], processApiError "getting search results" e)
      Close ->
        (model |> close , Cmd.none)
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
                    \s ->  if String.length s > 2  then getSearchResult model s GetResults else Cmd.none
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
