module Utils.DateUtils exposing
    ( relativeTimeOptions
    , posixToString
    , posixOrdering
    , posixToDateString
    , posixToTimeString
    , isBefore
    )

import DateFormat.Relative
import Ordering
import String.Extra
import Time exposing (Month(..), Posix, Zone)
import Time.Extra

relativeTimeOptions : DateFormat.Relative.RelativeTimeOptions
relativeTimeOptions =
    let
        default =
            DateFormat.Relative.defaultRelativeOptions

        -- do not show passing seconds
        someSecondsAgo _ =
            "less than a minute ago"

        -- copy of defaultSomeDaysAgo, but show weeks
        someDaysAgo days =
            let
                weeks =
                    days // 7
            in
                if days < 2 then
                    "yesterday"

                else if weeks > 0 then
                    String.Extra.pluralize "week" "weeks" weeks ++ " ago"

                else
                    String.fromInt days ++ " days ago"

    in
        { default | someSecondsAgo = someSecondsAgo, someDaysAgo = someDaysAgo }

{-| Pretty date for a posix
-}
posixToString : Zone -> Posix -> String
posixToString zone time =
  String.fromInt (Time.toYear zone time)
    ++ "-"
    ++ monthToNmbString (Time.toMonth zone time)
    ++ "-"
    ++ padded (Time.toDay zone time)
    ++ " "
    ++ padded (Time.toHour zone time)
    ++ ":"
    ++ padded (Time.toMinute zone time)
    ++ ":"
    ++ padded (Time.toSecond zone time)
    ++ offsetString zone time

monthToNmbString : Month -> String
monthToNmbString month =
  case month of
    Jan -> "01"
    Feb -> "02"
    Mar -> "03"
    Apr -> "04"
    May -> "05"
    Jun -> "06"
    Jul -> "07"
    Aug -> "08"
    Sep -> "09"
    Oct -> "10"
    Nov -> "11"
    Dec -> "12"


addLeadingZero : Int -> String
addLeadingZero value =
    let
        string = String.fromInt value
    in
        if String.length string == 1 then "0" ++ string else string

posixToDateString : Zone -> Posix -> String
posixToDateString zone date =
    addLeadingZero (Time.toYear zone date)
        ++ "-"
        ++ monthToNmbString (Time.toMonth zone date)
        ++ "-"
        ++ addLeadingZero (Time.toDay zone date)

posixToTimeString : Zone -> Posix -> String
posixToTimeString zone datetime =
    addLeadingZero (Time.toHour zone datetime)
        ++ ":"
        ++ addLeadingZero (Time.toMinute zone datetime)

padded : Int -> String
padded n =
    n
    |> String.fromInt
    |> String.padLeft 2 '0'


posixOrdering : Posix -> Posix -> Order
posixOrdering =
  Ordering.byField Time.posixToMillis


isBefore : { date : Posix, reference : Posix } -> Bool
isBefore { date, reference } =
  Time.posixToMillis date < Time.posixToMillis reference

-- This has been adapted from Time.TimeZone.offsetString since we want UTC offsets, but for Zone instead of TimeZone

{-| Given an arbitrary Time and TimeZone, offsetString returns an
ISO8601-formatted UTC offset for at that Time.
-}
offsetString : Zone -> Posix -> String
offsetString zone time =
    let
        utcOffset =
            Time.Extra.toOffset zone time

        hours =
            abs utcOffset // 60

        minutes =
            modBy 60 (abs utcOffset)

        string =
            padded hours ++ ":" ++ padded minutes
    in
        if utcOffset < 0 then
            "-" ++ string

        else if utcOffset == 0 then
            "Z"

        else
            "+" ++ string
