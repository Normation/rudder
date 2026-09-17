module Filters.Tests.ModelTest exposing (..)

import Expect
import Filters.DataTypes exposing (addTag, clearTags, removeTag, setCurrentTag)
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
            , test "should not change tags ordering when adding already existing tags" <|
                \_ ->
                    emptyModel
                        |> addTag tags.cd
                        |> addTag tags.ab
                        |> addTag tags.cd
                        |> .tags
                        |> Expect.equal [ tags.ab, tags.cd ]
            , test "should update model when adding tag with existing key" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> addTag tags.ad
                        |> addTag tags.cd
                        |> .tags
                        |> Expect.equal [ tags.cd, tags.ad, tags.ab ]
            ]
        , describe "removeTag"
            [ test "should not update model on empty tag list" <|
                \_ ->
                    emptyModel
                        |> removeTag tags.ab
                        |> .tags
                        |> Expect.equal []
            , test "should update model when removing existing tag" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> addTag tags.cd
                        |> removeTag tags.ab
                        |> .tags
                        |> Expect.equal [ tags.cd ]
            , test "should not update model when removing non-existing tag" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> removeTag tags.cd
                        |> .tags
                        |> Expect.equal [ tags.ab ]
            , test "should not update model when removing tag with existing key" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> removeTag tags.ad
                        |> .tags
                        |> Expect.equal [ tags.ab ]
            ]
        ,describe "clearTags"
            [ test "should not update model on empty tag list" <|
                \_ ->
                    emptyModel
                        |> clearTags
                        |> .tags
                        |> Expect.equal []
            , test "should update model when list of tags is not empty" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> addTag tags.cd
                        |> clearTags
                        |> .tags
                        |> Expect.equal []
            ]
        , describe "setCurrentTag"
            [ test "should update current tag on empty model" <|
                \_ ->
                    emptyModel
                        |> setCurrentTag tags.ab
                        |> .newTag
                        |> Expect.equal tags.ab
            , test "should replace current tag" <|
                \_ ->
                    emptyModel
                        |> setCurrentTag tags.ab
                        |> setCurrentTag tags.cd
                        |> .newTag
                        |> Expect.equal tags.cd
            ]
        ]
