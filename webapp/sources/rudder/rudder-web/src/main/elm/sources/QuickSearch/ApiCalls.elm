module QuickSearch.ApiCalls exposing (..)


import Http exposing (expectJson, get, request)
import Json.Decode exposing (at, list)
import QuickSearch.Datatypes exposing (..)
import QuickSearch.JsonDecoder exposing (decoderResult)
import QuickSearch.View exposing (kindName)
import Url.Builder exposing (QueryParameter, string)



getUrl: Model -> String -> String
getUrl m search =
  let param = string "value" search
  -- to filter request on filter, but i don't think we want it, just a front  side filter
  -- ++ (List.map (kindName >> (++)" is:") m.selectedFilter |> String.join "" ))
  in
  Url.Builder.relative (m.contextPath :: "secure" :: "api"  :: "quicksearch" :: []) [ param ]

getSearchResult : Model -> String -> Cmd Msg
getSearchResult model search =
  let
    req =
      get
        { url     = getUrl model search
        , expect  = expectJson GetResults (at ["data"] (list decoderResult))
        }
  in
    req