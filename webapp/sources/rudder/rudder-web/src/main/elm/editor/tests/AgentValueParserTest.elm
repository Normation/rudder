module AgentValueParserTest exposing (..)

import Expect exposing (Expectation)
import Fuzz exposing (Fuzzer, int, list, string)
import Test exposing (..)
import DataTypes exposing (..)
import AgentValueParser exposing (..)
import  Parser exposing (run)


suite : Test
suite =
  describe "Parsing agent values" [
    test "When parsing a simple string should produce a value" <|
      \_ -> "Hello" |> getAgentValue |> Expect.equal [Value "Hello"]
  , test "When parsing a variable should produce a variable" <|
      \_ -> "${hello}" |> getAgentValue |> Expect.equal [Variable [Value  "hello"] ]
  , test "When parsing a mixed value with variables should produce a the correct list of values" <|
      \_ -> "hello${hello}hello${hello}hello" |> getAgentValue |> Expect.equal [Value  "hello", Variable [Value  "hello"], Value  "hello", Variable [Value  "hello"], Value  "hello" ]
  , test "When parsing inner variable" <|
      \_ -> "${hello${hello}}" |> getAgentValue |> Expect.equal [Variable [Value  "hello", Variable [Value  "hello"]] ]
  , test "parse dollar" <|
      \_ -> "$" |> run valueLoop |> Expect.equal (Ok [Value  "$"] )
  , test "parse dollar and value" <|
      \_ -> "$hello" |> run valueLoop |> Expect.equal (Ok [Value  "$hello"] )
  , test "parse as a value a variable with missing closing brace" <|
      \_ -> "${hello" |> run valueLoop |> Expect.equal (Ok [Value  "${hello"] )
  , test "When parsing invalid inner variable" <|
      \_ -> "${hello${hello}" |> run valueLoop |> Expect.equal (Ok [Value  "${hello", Variable [Value  "hello"]] )
  , test " should not parse variable a variable when then there is spaced within" <|
      \_ -> "${hel lo}" |> run valueLoop |> Expect.equal (Ok [Value  "${hel lo}"])
  , test " should not parse variable a variable when then there is spaced within but still parse a valid variable after" <|
      \_ -> "${hel lo}${hello}" |> run valueLoop |> Expect.equal (Ok [Value  "${hel lo}", Variable [Value  "hello"]])
  ]