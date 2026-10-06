module Editor.Tests.CategoryTreeTest exposing (..)

import Editor.DataTypes exposing (SubCategories(..), TechniqueCategory, TechniqueId, TreeFiltering(..), TreeTechnique, creationCategory, visibleCategories)
import Expect
import Test exposing (..)


category : String -> List TechniqueCategory -> TechniqueCategory
category path subs =
    TechniqueCategory path path "" path (SubCategories subs)


technique : String -> TreeTechnique
technique inCategory =
    TreeTechnique (TechniqueId "a_technique") "1.0" "A technique" inCategory []


{-| The library as Rudder ships it, cut down to what the rules need: a provided category with a
sub-category, and the user one.
-}
library : TechniqueCategory
library =
    TechniqueCategory "/"
        "/"
        ""
        "/"
        (SubCategories
            [ category "applications" []
            , category "systemSettings" [ category "systemSettings/misc" [] ]
            , category "ncf_techniques" [ category "ncf_techniques/my_category" [] ]
            ]
        )


{-| The paths of the tree, depth first, so that a test says what the user sees.
-}
paths : Maybe TechniqueCategory -> List String
paths tree =
    case tree of
        Nothing ->
            []

        Just c ->
            case c.subCategories of
                SubCategories subs ->
                    c.path :: List.concatMap (\s -> paths (Just s)) subs


suite : Test
suite =
    describe "Which technique categories the editor tree shows"
        [ test "hides the provided categories that hold no technique" <|
            \_ ->
                visibleCategories Unfiltered [] library
                    |> paths
                    |> Expect.equal [ "/", "ncf_techniques", "ncf_techniques/my_category" ]
        , test "keeps a provided category holding a technique, and its parents" <|
            \_ ->
                visibleCategories Unfiltered [ technique "systemSettings/misc" ] library
                    |> paths
                    |> Expect.equal [ "/", "systemSettings", "systemSettings/misc", "ncf_techniques", "ncf_techniques/my_category" ]
        , test "keeps the empty user categories, which are there to be selected and filled" <|
            \_ ->
                visibleCategories Unfiltered [ technique "ncf_techniques" ] library
                    |> paths
                    |> Expect.equal [ "/", "ncf_techniques", "ncf_techniques/my_category" ]
        , test "hides the empty user categories while searching, where only matches are of interest" <|
            \_ ->
                visibleCategories Filtered [ technique "ncf_techniques" ] library
                    |> paths
                    |> Expect.equal [ "/", "ncf_techniques" ]
        , test "shows nothing when a search matches no technique at all" <|
            \_ ->
                visibleCategories Filtered [] library
                    |> Expect.equal Nothing
        , test "ignores a technique whose category is not in the library" <|
            \_ ->
                visibleCategories Filtered [ technique "deleted_category" ] library
                    |> Expect.equal Nothing
        , describe "Where a technique being created lands"
            [ test "keeps a category the user owns" <|
                \_ -> creationCategory "ncf_techniques/my_category" |> Expect.equal "ncf_techniques/my_category"
            , test "keeps the user root itself" <|
                \_ -> creationCategory "ncf_techniques" |> Expect.equal "ncf_techniques"
            , test "falls back to the user root for a provided category" <|
                \_ -> creationCategory "systemSettings/misc" |> Expect.equal "ncf_techniques"
            , test "is not fooled by a category whose name starts like the user one" <|
                \_ -> creationCategory "ncf_techniques_of_mine" |> Expect.equal "ncf_techniques"
            ]
        ]
