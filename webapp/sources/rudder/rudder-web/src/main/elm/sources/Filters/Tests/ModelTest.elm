module Filters.Tests.ModelTest exposing (..)

import Expect
import Filters.DataTypes exposing (addTag)
import Filters.Init as Model exposing (initModel)
import Test exposing (describe, test)

suite = describe "Filters.Model"
    [ test "should update model with empty tags list tag property with new tag" <|
        \_ ->
            let
                model =
                    initModel { contextPath = "", objectType = "" }
                tag =
                    { key = "a", value = "b" }
            in
            addTag tag model
                |> .tags
                |> Expect.equal [ tag ]
    , test "should update model with non-empty tags list tag property with new tag" <|
        \_ ->
            Debug.todo ""
    ]
