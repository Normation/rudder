module Filters.Tests.ModelTest exposing (..)

import Expect
import Filters.DataTypes exposing (addTag)
import Filters.Init as Model exposing (initModel)
import Test exposing (describe, test)


emptyModel =
    initModel { contextPath = "", objectType = "" }


tags =
    { ab = { key = "a", value = "b" }
    , cd = { key = "c", value = "d" }
    }


suite =
    describe "Filters.Model"
        [ describe "addTag"
            [ test "should update model with empty tags list tag property with new tag" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> .tags
                        |> Expect.equal [ tags.ab ]
            , test "should update model with non-empty tags list tag property with prepending tag" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> addTag tags.cd
                        |> .tags
                        |> Expect.equal [ tags.cd, tags.ab ]
            , test "should not update model with already existing tag" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> addTag tags.cd
                        |> addTag tags.cd
                        |> .tags
                        |> Expect.equal [ tags.cd, tags.ab ]
            ]
        ]
