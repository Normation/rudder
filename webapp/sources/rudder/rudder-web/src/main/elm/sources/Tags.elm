module Tags exposing (..)

import Browser
import Tags.Init exposing (..)
import Tags.Update exposing (update)
import Tags.View exposing (view)


main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }
