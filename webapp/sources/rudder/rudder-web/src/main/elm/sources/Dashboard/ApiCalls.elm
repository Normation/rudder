module Dashboard.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)
import Http.Detailed as Detailed
import Dashboard.DataTypes exposing (..)
import Dashboard.JsonDecoder exposing (..)
import Dashboard.JsonEncoder exposing (..)


getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api" :: url) p

getActivities : Model -> Cmd Msg
getActivities model =
  let
    req =
      request
        { method  = "POST"
        , headers = [header "X-Requested-With" "XMLHttpRequest"]
        , url     = getUrl model [ "eventlog" ] []
        , body    = encodeRestEventLogFilter |> jsonBody
        , expect  = Detailed.expectJson GetActivities decodeGetActivities
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req