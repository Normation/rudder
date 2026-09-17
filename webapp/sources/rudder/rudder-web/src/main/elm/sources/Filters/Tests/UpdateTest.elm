module Filters.Tests.UpdateTest exposing (..)

import Expect
import Filters exposing (applyActionOnModel)
import Filters.DataTypes exposing (addTag, removeTag, setCurrentTag)
import Filters.Init as Model exposing (initModel)
import Tags.Model exposing (emptyTag)
import Tags.Update
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
            [ test "should add tag to model and replace current tag with empty tag" <|
                \_ ->
                    emptyModel
                        |> applyActionOnModel (Tags.Update.Add tags.ab)
                        |> (\m -> { tags = m.tags, current = m.newTag })
                        |> Expect.equal { tags = [ tags.ab ], current = emptyTag }
            , test "should add tag to model and replace current tag with empty tag when tags list is not empty" <|
                \_ ->
                    emptyModel
                        |> addTag tags.cd
                        |> applyActionOnModel (Tags.Update.Add tags.ab)
                        |> (\m -> { tags = m.tags, current = m.newTag })
                        |> Expect.equal { tags = [ tags.ab, tags.cd ], current = emptyTag }
            ]
        , describe "Remove"
            [ test "should remove tag from model when tag list is not empty and keep current tag" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> addTag tags.cd
                        |> setCurrentTag tags.ad
                        |> applyActionOnModel (Tags.Update.Remove tags.cd)
                        |> (\m -> { tags = m.tags, current = m.newTag })
                        |> Expect.equal { tags = [ tags.ab ], current = tags.ad }
            , test "should not update model when tag list is empty and keep current tag" <|
                \_ ->
                    emptyModel
                        |> setCurrentTag tags.ad
                        |> applyActionOnModel (Tags.Update.Remove tags.cd)
                        |> (\m -> { tags = m.tags, current = m.newTag })
                        |> Expect.equal { tags = [], current = tags.ad }
            ]
        , describe "Clear"
            [ test "should empty tag list into model when tag list is not empty and keep current tag" <|
                \_ ->
                    emptyModel
                        |> addTag tags.ab
                        |> addTag tags.cd
                        |> setCurrentTag tags.ad
                        |> applyActionOnModel Tags.Update.Clear
                        |> (\m -> { tags = m.tags, current = m.newTag })
                        |> Expect.equal { tags = [], current = tags.ad }
            , test "should not update model when tag list is empty and keep current tag" <|
                \_ ->
                    emptyModel
                        |> setCurrentTag tags.ad
                        |> applyActionOnModel Tags.Update.Clear
                        |> (\m -> { tags = m.tags, current = m.newTag })
                        |> Expect.equal { tags = [], current = tags.ad }
            ]
        ]
