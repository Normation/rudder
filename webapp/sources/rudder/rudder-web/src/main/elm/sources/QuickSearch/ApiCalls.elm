module QuickSearch.ApiCalls exposing (..)


import Http exposing (request, header, emptyBody)
import Http.Detailed as Detailed
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
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model search
        , body    = emptyBody
        , expect  = Detailed.expectJson GetResults (at ["data"] (list decoderResult))
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req
