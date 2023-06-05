module Hooks.ApiCalls exposing (..)

import Http exposing (..)
import Url.Builder exposing (QueryParameter)
import Http.Detailed as Detailed

import Hooks.DataTypes exposing (..)
import Hooks.JsonDecoder exposing (..)


--
-- This files contains all API calls for the Rules UI
-- Summary:
-- GET    /hooks: get the hooks list

getUrl: Model -> List String -> List QueryParameter -> String
getUrl m url p=
  Url.Builder.relative (m.contextPath :: "secure" :: "api" :: "hooks" :: url) p

getHooks : Model -> Cmd Msg
getHooks model =
  let
    req =
      request
        { method  = "GET"
        , headers = []
        , url     = getUrl model [] []
        , body    = emptyBody
        , expect  = Detailed.expectJson GetHooksResult decodeGetHooks
        , timeout = Nothing
        , tracker = Nothing
        }
  in
    req