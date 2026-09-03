module Tags exposing (..)

import Browser
import Json.Encode exposing (..)
import List.Extra
import Result
import Tags.ApiCalls exposing (getCompletionTags)
import Tags.DataTypes exposing (..)
import Tags.Init exposing (..)
import Tags.JsonEncoder exposing (..)
import Tags.Model exposing (Model)
import Tags.Update exposing (update)
import Tags.View exposing (view)


main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }
