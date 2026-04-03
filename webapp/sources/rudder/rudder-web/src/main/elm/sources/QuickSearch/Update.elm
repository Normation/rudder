module QuickSearch.Update exposing (update, Msg(..), update_, Effect(..))

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

type Effect
    = ErrorNotification String
    | DebouncePush String
    | InternalDebounceMsg Debounce.Msg
    | NoEffect

update : Msg -> Model -> ( Model, Cmd Msg )
update  msg model =
    let (updatedModel, effect) = update_ msg model
    in case effect of
            ErrorNotification message ->
                (updatedModel, errorNotification message)

            NoEffect ->
                (updatedModel, Cmd.none)

            DebouncePush search ->
                let (debounce, cmd) = Debounce.push debounceConfig search updatedModel.debounceSearch
                in (updatedModel |> setDebounce debounce, cmd)

            InternalDebounceMsg debMsg ->
                 let
                    ( debounce, cmd ) =
                        Debounce.update
                            debounceConfig
                            (Debounce.takeLast (
                              \s ->  if String.length s > 2 then getSearchResult model s GetResults else Cmd.none
                            ))
                            debMsg
                            model.debounceSearch

                 in (model |> setDebounce debounce, cmd)


withEffect : Effect -> Model -> ( Model , Effect )
withEffect effect model =
    ( model , effect )

withNoEffect = withEffect NoEffect

update_ : Msg -> Model -> ( Model, Effect )
update_  msg model =
  case msg of
      UpdateFilter filter ->
          case filter of
              All ->
                model
                |> removeSelectedFilters
                |> withNoEffect

              FilterKind k ->
                model
                |> toggleSelectedFilter k
                |> withNoEffect

      UpdateSearch search ->
        model
        |> setSearch search
        |> withEffect (DebouncePush search)

      GetResults (Ok (_, r)) ->
        model
        |> setResults r
        |> withNoEffect

      GetResults (Err e) ->
        model
        |> setResults []
        |> withEffect (processApiError "getting search results" e)

      Close ->
        model
        |> close
        |> withNoEffect

      Open ->
        model
        |> open
        |> withNoEffect

      DebounceMsg debMsg ->
        model
        |> withEffect (InternalDebounceMsg debMsg)


processApiError : String -> Detailed.Error String -> Effect
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
    ErrorNotification message
