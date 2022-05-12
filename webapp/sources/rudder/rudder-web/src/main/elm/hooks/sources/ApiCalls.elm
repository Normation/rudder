module ApiCalls exposing (..)

import DataTypes exposing (..)
import Http exposing (..)
import JsonDecoder exposing (..)
import Url.Builder exposing (QueryParameter)
import Http.Detailed as Detailed


--
-- This files contains all API calls for the Rules UI
-- Summary:
-- GET    /hooks: get the hooks list

getUrl: DataTypes.Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api" :: "system" :: url) p

getHooks : Model -> Cmd Msg
getHooks model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [ "hooks" ] []
        , body    = emptyBody
        , expect  = Detailed.expectJson GetHooksResult decodeGetHooks
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req