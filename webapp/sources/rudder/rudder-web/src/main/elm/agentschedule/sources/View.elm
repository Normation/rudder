module View exposing (..)

import DataTypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (id, class, href, type_, disabled, for, checked, selected, value)
import Html.Events exposing (onClick, onInput)
import Maybe.Extra exposing (isJust)
import Dict
import ViewUtils exposing (..)

view : Model -> Html Msg
view model =
  div []
  [ if model.ui.hasWriteRights then
    div [class "explanation-text"]
    [ case model.globalRun of
      Just globalRun ->
        div[]
        [ p[]
          [ text "By default, agents on all nodes run following the same frequency defined in the global "
          , a [href (model.contextPath ++ "/secure/administration/policyServerManagement#cfagentSchedule")][text "Settings"]
          , text "."
          ]
        , p[]
          [ text "The current global setting is to run every "
          , b[][text (getIntervalValue globalRun.interval)]
          , text ", starting at "
          , b[][text (format2Digits(globalRun.startHour)), text ":", text (format2Digits(globalRun.startMinute))]
          , text ", with a maximum delay after scheduled run time (random interval) of "
          , b[][text (format2Digits(globalRun.splayHour)), text ":", text (format2Digits(globalRun.splayMinute))]
          , text "."
          ]
        , p[][text "You may override this global setting just for this node below:"]
        ]
      Nothing ->
        div[]
        [ p[][text "This setting will not be applied to policy server."]
        , p[][text "By default, the agent runs on all nodes every 5 minutes."]
        , p[][text "This high frequency enables fast response times to apply changes and state assessment for high-precision drift and compliance reports."]
        , p[][text "You can modify this run interval below, as well as the \"splay time\" across nodes (a random delay that alters scheduled run time, in order to spread load across nodes)."]
        ]
    , case model.currentSettings of
      Just currentSettings ->
        let
          selectedSettings = case model.selectedSettings of
            Just s  -> s
            Nothing -> currentSettings

          overrideMode = case selectedSettings.overrides of
            Just o  -> o
            Nothing -> False

          disableForm =
            case selectedSettings.overrides of
              Just o  -> not o
              Nothing -> False

          intervalOptions = intervals
            |> Dict.map (\ik iv -> option[value (String.fromInt ik), selected (selectedSettings.interval == ik)][text iv])
            |> Dict.values

          hourOptions hour = hours selectedSettings
            |> List.map (\h -> option[value (String.fromInt h), selected (hour == h)][text (String.fromInt h)])

          minuteOptions min = minutes selectedSettings
            |> List.map (\m -> option[value (String.fromInt m), selected (min == m)][text (String.fromInt m)])

          selectedStartH = selectedSettings.startHour
          selectedSplayH = selectedSettings.splayHour
          selectedStartM = selectedSettings.startMinute
          selectedSplayM = selectedSettings.splayMinute

          overrideCheckbox =
            if isJust model.globalRun then
              ul []
              [ li [class "rudder-form"]
                [ div [class "input-group"]
                  [ label [class "input-group-addon", for "override", onClick (UpdateSchedule {selectedSettings | overrides = Just (not overrideMode)})]
                    [ input [id "override", checked overrideMode, type_ "checkbox"][]
                    , label [for "override", class"label-radio"]
                      [ span [class "ion ion-checkmark-round"][]
                      ]
                    , span [class "ion ion-checkmark-round check-icon"][]
                    ]
                  , label [for "override", class "form-control"]
                    [ text "Override global value"
                    ]
                  ]
                ]
              ]
            else
              text ""
        in
          form [class "form-horizontal"]
          [ div[]
            [ overrideCheckbox
            , div [class "globalConf"]
              [ div [class "form-group"]
                [ label [for "runInterval", class "control-label"][text "Run agent every "]
                , select [class "form-control input-sm schedule", id "runInterval", disabled disableForm, onInput (\i -> UpdateSchedule {selectedSettings | interval = Maybe.withDefault 0 (String.toInt i)})]
                  ( intervalOptions )
                ]
              , div [class "form-group inline-input-group"]
                [ label [class "control-label"][text "First run time"]
                , div [class "input-group input-group-sm"]
                  [ select [class "form-control input-sm", id "startHour", onInput (\h -> UpdateSchedule {selectedSettings | startHour = Maybe.withDefault 0 (String.toInt h)}), disabled (disableForm || List.length (hourOptions selectedStartH) <= 1) ]
                   (hourOptions selectedStartH)
                  , span [class "input-group-addon"][text ("Hour" ++ if selectedSettings.startHour > 1 then "s" else "")]
                  , select [class "pull-left form-control input-sm", id "startMinute", onInput (\m -> UpdateSchedule {selectedSettings | startMinute = Maybe.withDefault 0 (String.toInt m)}), disabled disableForm ]
                    (minuteOptions selectedStartM)
                  , span [class "input-group-addon"][text ("Minute" ++ if selectedSettings.startMinute > 1 then "s" else "")]
                  ]
                ]
              , div [class "form-group inline-input-group"]
                [ label [class "control-label"][text "Maximum delay"]
                , div [class "input-group input-group-sm"]
                  [ select [class "form-control input-sm", id "splayHour", onInput (\h -> UpdateSchedule {selectedSettings | splayHour = Maybe.withDefault 0 (String.toInt h)}), disabled (disableForm || List.length (hourOptions selectedSplayH) <= 1) ]
                    (hourOptions selectedSplayH)
                  , span [class "input-group-addon"][text ("Hour" ++ if selectedSettings.splayHour > 1 then "s" else "")]
                  , select [class "pull-left form-control input-sm", id "splayMinute", onInput (\m -> UpdateSchedule {selectedSettings | splayMinute = Maybe.withDefault 0 (String.toInt m)}), disabled disableForm ]
                    (minuteOptions selectedSplayM)
                  , span [class "input-group-addon"][text ("Minute" ++ if selectedSettings.splayMinute > 1 then "s" else "")]
                  ]
                ]
              ]
            ]
          , div [class "form-group"]
            [ div [class "pull-left control-label"]
              [ button [class "btn btn-success btn-save btn-settings", type_ "button", disabled (model.selectedSettings == model.currentSettings), onClick (SaveChanges selectedSettings)][]
              ]
            ]
          ]
      Nothing -> text ""
    ]
    else
    text "No rights"
  ]
