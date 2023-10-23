module ViewRepairedReportsTest exposing (..)

import DataTypes exposing (Changes)
import Expect exposing (Expectation)
import Time.DateTime exposing (epoch)
import Time.ZonedDateTime exposing (fromDateTime, ZonedDateTime)
import Time.Iso8601 exposing (toZonedDateTime)
import Time.TimeZones exposing (utc, europe_paris)
import Test exposing (..)

import ViewRepairedReports exposing (showChanges)

parseDate : String -> ZonedDateTime
parseDate = Result.withDefault (fromDateTime utc epoch) << toZonedDateTime europe_paris

changes : Float -> Changes
changes n = {
  start = parseDate "2023-10-12T02:00:00+00:00"
  , end = parseDate "2023-10-13T20:00:00+00:00"
  , changes = n }

suite : Test
suite =
  describe "ViewRepairedReports module"
    [ describe "showChange util"
      [ test "should display 0 change" <|
        \_ ->
          changes 0
            |> showChanges
            |> Expect.equal "Between 2023-10-12 04:00 UTC+02 and 2023-10-13 22:00 UTC+02 (0 change)"
      , test "should display 1 change" <|
        \_ ->
          changes 1
            |> showChanges
            |> Expect.equal "Between 2023-10-12 04:00 UTC+02 and 2023-10-13 22:00 UTC+02 (1 change)"
      , test "should display multiple changes" <|
        \_ ->
          changes 10
            |> showChanges
            |> Expect.equal "Between 2023-10-12 04:00 UTC+02 and 2023-10-13 22:00 UTC+02 (10 changes)"
    ]
  ]
