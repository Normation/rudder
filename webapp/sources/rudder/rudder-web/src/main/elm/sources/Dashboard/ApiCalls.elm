module Dashboard.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)
import Http.Detailed as Detailed
import Dashboard.DataTypes exposing (..)
import Dashboard.JsonDecoder exposing (..)


getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api" :: url) p

getActivities : Model -> Cmd Msg
getActivities model =
  let
    req =
      request
        { method  = "GET"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "apiaccounts" ] []
        , body    = emptyBody
        , expect  = Detailed.expectJson GetActivities decodeGetActivities
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req