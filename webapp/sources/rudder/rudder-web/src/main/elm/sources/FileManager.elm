module FileManager exposing (..)

import Browser
import FileManager.Model exposing (..)
import FileManager.Port exposing (..)
import FileManager.Update exposing (..)
import FileManager.View exposing (..)
import Http


main : Program Flags Model Msg
main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }


subscriptions : Model -> Sub Msg
subscriptions _ =
    Sub.batch
        [ onOpen (EnvMsg << Open)
        , Http.track "upload" Progress
        , updateApiPAth UpdateApiPath
        ]
