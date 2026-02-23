module Utils.DatePickerUtils exposing
  ( userDefinedDatePickerSettings
  )


import SingleDatePicker exposing (Settings, TimePickerVisibility(..), defaultSettings, defaultTimePickerSettings)
import Time exposing (Month(..), Posix, Zone)
import Time.Extra exposing (Interval(..), Parts)
import Utils.DateUtils exposing (isBefore, posixToDateString, posixToTimeString)

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