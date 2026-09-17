module Filters.Tests.UpdateTest exposing (..)

import Expect
import Filters.DataTypes exposing (addTag, removeTag, setCurrentTag)
import Filters.Init as Model exposing (initModel)
import Test exposing (describe, test)


emptyModel =
    initModel { contextPath = "", objectType = "" }


tags =
    { ab = { key = "a", value = "b" }
    , cd = { key = "c", value = "d" }
    , ad = { key = "a", value = "d" }
    }


suite =
    describe "Filters.Update"
        [ describe "Add"
            [ test "should add tag to model and replace current tag with empty tag" <| \_ -> Expect.fail "passe pas"
            ]
        ]
