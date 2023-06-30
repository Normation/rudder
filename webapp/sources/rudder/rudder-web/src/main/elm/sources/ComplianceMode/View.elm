module ComplianceMode.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (class, type_, value, checked, id, disabled, for)
import Html.Events exposing (onClick, onInput)
import List
import String

import ComplianceMode.DataTypes exposing (..)


view : Model -> Html Msg
view model =
  div [class "portlet-content"]
  [ div []
    [ div [class "explanation-text"]
      [ text "This setting affects the reports sent from each agent to this central server."
      , ul[]
        [ li[]
          [ text "In "
          , b[][ text "Full compliance"]
          , text "mode, a report will be sent for each configuration component that is checked, even if no changes were necessary (these are known as 'success' reports). This mode is much more verbose, in terms of logs and network traffic, but provides more precise reporting and may be necessary to prove compliance in your organization."
          ]
        , li[]
            [ text "In "
            , b[][ text "Non compliant only"]
            , text "mode, reports will only be sent when the agent makes a change or an error occurs on a node (these are 'repair' or 'error' reports). This mode saves a lot of log space and bandwidth, but leads to some assumptions about actual configuration status in reporting."
          ]
        , li[]
            [ text "In "
            , b[][ text "Disabled"]
            , text "mode, no reports will be sent, and rudder-agent will not re-configure the local syslog to send reports. This mode uses no log space or bandwidth, but will also not allow you to check if your configuration policy is successfully applied. We do not recommend using this mode except for setups where you have another feedback mechanism in place."
          ]
        ]
      ]
    ]
  , form [class "form-horizontal"]
    [ div [class "compliance-form"]
      [ ul[]
        [ li [class "rudder-form"]
          [ div [class "input-group", onClick (UpdateMode FullCompliance)]
            [ label [class "input-group-addon", for "fullcompliance"]
              [ input [type_ "radio", id "fullcompliance", checked (model.newMode == FullCompliance)][]
              , label [for "fullcompliance", class "label-radio"]
                [ span [class "ion ion-record"][]
                ]
              , span [class "ion ion-checkmark-round check-icon"][]
              ]
            , label [class "form-control", for"fullcompliance"]
              [ text " Full compliance"
              ]
            ]
          ]
        , li [class "rudder-form"]
          [ div [class "input-group", onClick (UpdateMode ChangesOnly)]
            [ label [class "input-group-addon", for "noncompliant"]
              [ input [type_ "radio", id "noncompliant", checked (model.newMode == ChangesOnly)][]
              , label [for "noncompliant", class "label-radio"]
                [ span [class "ion ion-record"][]
                ]
              , span [class "ion ion-checkmark-round check-icon"][]
              ]
            , label [class "form-control", for"noncompliant"]
              [ text " Non compliant only"
              ]
            ]
          ]
        , li [class "rudder-form"]
          [ div [class "input-group", onClick (UpdateMode ReportsDisabled)]
            [ label [class "input-group-addon", for "disabledCompliance"]
              [ input [type_ "radio", id "disabledCompliance", checked (model.newMode == ReportsDisabled)][]
              , label [for "disabledCompliance", class "label-radio"]
                [ span [class "ion ion-record"][]
                ]
              , span [class "ion ion-checkmark-round check-icon"][]
              ]
            , label [class "form-control", for"disabledCompliance"]
              [ text " Disabled"
              ]
            ]
          ]
        ]
      ]
    , div []
      [ button [type_ "button", class "btn btn-success", id "complianceModeSubmit", disabled (model.newMode == model.complianceMode || model.newMode == UnknownMode), onClick SaveChanges][text "Save changes"] -- ng-click="save()"
      ]
    ]
  ]