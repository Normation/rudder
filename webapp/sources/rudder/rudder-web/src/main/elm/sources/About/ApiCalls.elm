module About.ApiCalls exposing (..)

import Http exposing (emptyBody, expectJson, header, request)

import About.DataTypes exposing (..)
import About.JsonDecoder exposing (decodeGetAboutInfo)


getUrl: Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api" ++ url

getAboutInfo : Model -> Cmd Msg
getAboutInfo model =
  request
    { method          = "GET"
    , headers         = [header "X-Requested-With" "XMLHttpRequest"]
    , url             = getUrl model "/plugins/about"
    , body            = emptyBody
    , expect          = expectJson GetAboutInfo decodeGetAboutInfo
    , timeout         = Nothing
    , tracker         = Nothing
    }
--}
