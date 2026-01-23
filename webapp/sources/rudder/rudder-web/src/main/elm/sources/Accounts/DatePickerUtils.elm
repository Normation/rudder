module Accounts.DatePickerUtils exposing
  ( isBefore
  , posixOrdering
  , posixToString
  , userDefinedDatePickerSettings
  )

import Ordering
import SingleDatePicker exposing (Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings)
import Time exposing (Month(..), Posix, Zone)
import Time.Extra exposing (Interval(..), Parts)


posixOrdering : Posix -> Posix -> Order
posixOrdering =
  Ordering.byField Time.posixToMillis


isBefore : { date : Posix, reference : Posix } -> Bool
isBefore { date, reference } =
  Time.posixToMillis date < Time.posixToMillis reference


userDefinedDatePickerSettings : { zone : Zone, today : Posix, focusedDate : Posix } -> Settings
userDefinedDatePickerSettings { zone, today, focusedDate } =
  let
    defaults = defaultSettings zone
  in
    { defaults
    | isDayDisabled = \clientZone datetime -> isBefore { date = datetime, reference = Time.Extra.floor Day clientZone today }
    , focusedDate = Just focusedDate
    , dateStringFn = posixToDateString
    , timePickerVisibility =
      AlwaysVisible
      { defaultTimePickerSettings
      | timeStringFn = posixToTimeString
      , allowedTimesOfDay = \clientZone datetime -> adjustAllowedTimesOfDayToClientZone zone clientZone today datetime
      }
    }

addLeadingZero : Int -> String
addLeadingZero value =
  let
    string = String.fromInt value
  in
    if String.length string == 1 then "0" ++ string else string

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

adjustAllowedTimesOfDayToClientZone : Zone -> Zone -> Posix -> Posix -> { startHour : Int, startMinute : Int, endHour : Int, endMinute : Int }
adjustAllowedTimesOfDayToClientZone baseZone clientZone today datetimeBeingProcessed =
  let
    processingPartsInClientZone   = Time.Extra.posixToParts clientZone datetimeBeingProcessed
    todayPartsInClientZone        = Time.Extra.posixToParts clientZone today

    startPartsAdjustedForBaseZone = Time.Extra.posixToParts baseZone datetimeBeingProcessed
      |> (\parts -> Time.Extra.partsToPosix baseZone { parts | hour = 0, minute = 0 })
      |> Time.Extra.posixToParts clientZone

    endPartsAdjustedForBaseZone = Time.Extra.posixToParts baseZone datetimeBeingProcessed
      |> (\parts -> Time.Extra.partsToPosix baseZone { parts | hour = 23, minute = 59 })
      |> Time.Extra.posixToParts clientZone

    bounds =
      { startHour   = startPartsAdjustedForBaseZone.hour
      , startMinute = startPartsAdjustedForBaseZone.minute
      , endHour     = endPartsAdjustedForBaseZone.hour
      , endMinute   = endPartsAdjustedForBaseZone.minute
      }
  in
    if processingPartsInClientZone.day == todayPartsInClientZone.day && processingPartsInClientZone.month == todayPartsInClientZone.month && processingPartsInClientZone.year == todayPartsInClientZone.year then
      if todayPartsInClientZone.hour > bounds.startHour || (todayPartsInClientZone.hour == bounds.startHour && todayPartsInClientZone.minute > bounds.startMinute) then
        { startHour = todayPartsInClientZone.hour, startMinute = todayPartsInClientZone.minute, endHour = bounds.endHour, endMinute = bounds.endMinute }
      else
        bounds
    else
      bounds

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


{-| Pretty date for a posix in API accounts display
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


padded : Int -> String
padded n =
  n
    |> String.fromInt
    |> String.padLeft 2 '0'


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
