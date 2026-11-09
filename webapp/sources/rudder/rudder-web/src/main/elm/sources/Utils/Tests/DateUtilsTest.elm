module Utils.Tests.DateUtilsTest exposing (suite)

import Expect
import Test exposing (Test, describe, test)
import Time exposing (Month(..), Posix, Zone)
import Time.Extra
import TimeZone
import Utils.DateUtils exposing (offsetString, posixToDateString, posixToString, posixToTimeString)


utc : Int -> Month -> Int -> Int -> Int -> Int -> Int -> Posix
utc year month day hour minute second millisecond =
    Time.Extra.partsToPosix Time.utc (Time.Extra.Parts year month day hour minute second millisecond)


paris : Zone
paris =
    TimeZone.europe__paris ()


newYork : Zone
newYork =
    TimeZone.america__new_york ()


type alias Case =
    { name : String
    , zone : Zone
    , time : Posix
    , expected : String
    }


posixToStringCases : List Case
posixToStringCases =
    [ Case "UTC is written Z" Time.utc (utc 2025 Feb 12 10 12 14 0) "2025-02-12 10:12:14Z"
    , Case "milliseconds are truncated, not rounded" Time.utc (utc 2025 Feb 12 10 12 14 999) "2025-02-12 10:12:14Z"
    , Case "leap day" Time.utc (utc 2024 Feb 29 12 0 0 0) "2024-02-29 12:00:00Z"
    , Case "Paris winter offset" paris (utc 2025 Feb 12 10 12 14 0) "2025-02-12 11:12:14+01:00"
    , Case "Paris summer offset" paris (utc 2025 Jul 1 12 0 0 0) "2025-07-01 14:00:00+02:00"
    , Case "Paris shifts the day forward past midnight" paris (utc 2025 Jan 15 23 30 0 0) "2025-01-16 00:30:00+01:00"
    , Case "Paris last second before spring forward" paris (utc 2025 Mar 30 0 59 59 0) "2025-03-30 01:59:59+01:00"
    , Case "Paris first minute after spring forward" paris (utc 2025 Mar 30 1 1 0 0) "2025-03-30 03:01:00+02:00"
    , Case "Paris last second before fall back" paris (utc 2025 Oct 26 0 59 59 0) "2025-10-26 02:59:59+02:00"
    , Case "Paris first minute after fall back" paris (utc 2025 Oct 26 1 1 0 0) "2025-10-26 02:01:00+01:00"
    , Case "New York shifts the day backward" newYork (utc 2025 Jan 15 3 0 0 0) "2025-01-14 22:00:00-05:00"
    , Case "New York summer offset" newYork (utc 2025 Jul 1 12 0 0 0) "2025-07-01 08:00:00-04:00"
    ]


suite : Test
suite =
    describe "Utils.DateUtils"
        [ describe "posixToString"
            (posixToStringCases
                |> List.map (\c -> test c.name <| \_ -> posixToString c.zone c.time |> Expect.equal c.expected)
            )
        , describe "offsetString"
            [ test "UTC is Z, never +00:00" <|
                \_ -> offsetString Time.utc (utc 2025 Feb 12 10 12 14 0) |> Expect.equal "Z"
            , test "follows DST at the given instant" <|
                \_ ->
                    [ utc 2025 Jan 15 12 0 0 0, utc 2025 Jul 1 12 0 0 0 ]
                        |> List.map (offsetString paris)
                        |> Expect.equal [ "+01:00", "+02:00" ]
            , test "elm/time keeps the previous offset during the first minute of a transition" <|
                -- elm/time picks a zone era with `era.start < posixMinutes`, so the transition minute itself still
                -- uses the old offset. This is the elm/time behavior, it could be surprising but it is at least explicit
                \_ ->
                    [ utc 2025 Mar 30 1 0 0 0, utc 2025 Oct 26 1 0 59 0 ]
                        |> List.map (offsetString paris)
                        |> Expect.equal [ "+01:00", "+02:00" ]
            ]
        , describe "posixToDateString"
            [ test "is the date in the zone" <|
                \_ ->
                    [ Time.utc, paris, newYork ]
                        |> List.map (\zone -> posixToDateString zone (utc 2025 Jan 15 23 30 0 0))
                        |> Expect.equal [ "2025-01-15", "2025-01-16", "2025-01-15" ]
            ]
        , describe "posixToTimeString"
            [ test "is hours and minutes, without seconds" <|
                \_ -> posixToTimeString Time.utc (utc 2025 Feb 12 9 5 59 0) |> Expect.equal "09:05"
            , test "is the time in the zone" <|
                \_ -> posixToTimeString newYork (utc 2025 Feb 12 10 12 14 0) |> Expect.equal "05:12"
            ]
        ]
