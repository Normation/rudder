module About.ApiCalls exposing (..)

import Http exposing (emptyBody, expectJson, header, request)
import Http.Detailed as Detailed

import About.DataTypes exposing (..)
import About.JsonDecoder exposing (decodeApiGetAboutInfo)


getUrl: Model -> String -> String
getUrl m url =
  m.contextPath ++ "/secure/api" ++ url

apiGetAboutInfo : Model -> Cmd Msg
apiGetAboutInfo model =
  request
    { method          = "GET"
    , headers         = [header "X-Requested-With" "XMLHttpRequest"]
    , url             = getUrl model "/system/info"
    , body            = emptyBody
    , expect          = Detailed.expectJson ApiGetAboutInfo decodeApiGetAboutInfo
    , timeout         = Nothing
    , tracker         = Nothing
    }
--}
