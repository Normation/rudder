module Accounts.DatePickerUtils exposing (..)

import SingleDatePicker exposing (Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings)
import Time exposing (Month(..), Posix, Zone)
import Time.Extra as Time exposing (Interval(..), Parts, partsToPosix)
import List.Extra exposing (getAt)
import Date exposing (fromIsoString)

import Accounts.DataTypes exposing (..)


isDateBeforeToday : Posix -> Posix -> Bool
isDateBeforeToday today datetime =
  Time.posixToMillis today > Time.posixToMillis datetime

userDefinedDatePickerSettings : Zone -> Posix -> Posix -> Settings
userDefinedDatePickerSettings zone today focusDate =
  let
    defaults = defaultSettings zone
  in
    { defaults
    | isDayDisabled = \clientZone datetime -> isDateBeforeToday (Time.floor Day clientZone today) datetime
    , focusedDate = Just focusDate
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
    processingPartsInClientZone   = Time.posixToParts clientZone datetimeBeingProcessed
    todayPartsInClientZone        = Time.posixToParts clientZone today

    startPartsAdjustedForBaseZone = Time.posixToParts baseZone datetimeBeingProcessed
      |> (\parts -> Time.partsToPosix baseZone { parts | hour = 0, minute = 0 })
      |> Time.posixToParts clientZone

    endPartsAdjustedForBaseZone = Time.posixToParts baseZone datetimeBeingProcessed
      |> (\parts -> Time.partsToPosix baseZone { parts | hour = 23, minute = 59 })
      |> Time.posixToParts clientZone

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

getDateString : DatePickerInfo -> Maybe Posix -> String
getDateString datePickerInfo pickedTime =
  case pickedTime of
    Just d  ->
      posixToString datePickerInfo d
    Nothing ->
      ""

stringToPosix : DatePickerInfo -> String -> Maybe Posix
stringToPosix datePickerInfo str =
-- Format : YYYY-MM-dd HH:mm
  let
    dateTimeString = String.split " " str
    dateString = getAt 0 dateTimeString
    timeString = getAt 1 dateTimeString
  in
    case (dateString, timeString) of
      ( Just d, Just t) ->
        let
          date = fromIsoString d
          decomposeTime = String.split ":" t
          hourStr  = getAt 0 decomposeTime
          minStr   = getAt 1 decomposeTime
        in
          case (date, hourStr, minStr) of
            (Ok dd, Just strH, Just strM) -> case (String.toInt strH, String.toInt strM) of
              (Just h, Just m) -> Just (partsToPosix datePickerInfo.zone (Parts (Date.year dd) (Date.month dd) (Date.day dd) h m 0 0))
              _ -> Nothing
            _ -> Nothing
      _ -> Nothing

posixToString : DatePickerInfo -> Posix -> String
posixToString datePickerInfo p =
  (posixToDateString datePickerInfo.zone p ++ " " ++ posixToTimeString datePickerInfo.zone p)

checkIfExpired : DatePickerInfo -> Account -> Bool
checkIfExpired datePickerInfo account =
  case (account.expirationDateDefined, account.expirationDate) of
    ( False , _       ) -> False
    ( True  , Nothing ) -> False
    ( True  , Just p  ) -> isDateBeforeToday datePickerInfo.currentTime p