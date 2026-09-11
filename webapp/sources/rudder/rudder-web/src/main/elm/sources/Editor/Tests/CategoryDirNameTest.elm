module Editor.Tests.CategoryDirNameTest exposing (..)

import Editor.DataTypes exposing (categoryDirName)
import Expect
import Test exposing (..)



-- The server derives the same name from the same input, see `TechniqueCategoryDirName` on the
-- Scala side: these cases are the ones of `TestTechniqueCategoryDirName`.


suite : Test
suite =
    describe "Deriving the directory name of a technique category"
        [ test "keeps a name that is already safe" <|
            \_ -> categoryDirName "my-category_1" |> Expect.equal "my-category_1"
        , test "replaces spaces and non-ASCII characters" <|
            \_ -> categoryDirName "Mon Câtégorie" |> Expect.equal "mon_c_t_gorie"
        , test "collapses the runs of underscores it creates" <|
            \_ -> categoryDirName "Mon Câtégorie / test" |> Expect.equal "mon_c_t_gorie_test"
        , test "does not let a name escape its parent directory" <|
            \_ -> categoryDirName "../../etc" |> Expect.equal "etc"
        , test "replaces a dot, so that no refactoring can turn a name into a traversal" <|
            \_ -> categoryDirName "a/../b" |> Expect.equal "a_b"
        , test "drops control characters" <|
            \_ -> categoryDirName "a b\tc\nd" |> Expect.equal "a_b_c_d"
        , test "trims the leading and trailing dots and underscores" <|
            \_ -> categoryDirName "  .hidden.  " |> Expect.equal "hidden"
        , test "gives nothing for a name with no usable character" <|
            \_ -> categoryDirName "é" |> Expect.equal ""
        , test "folds the case, since LDAP compares category ids without it" <|
            \_ -> categoryDirName "Foo" |> Expect.equal "foo"
        , test "truncates to what a file system accepts for one path segment" <|
            \_ -> categoryDirName (String.repeat 300 "a") |> String.length |> Expect.equal 255
        ]
