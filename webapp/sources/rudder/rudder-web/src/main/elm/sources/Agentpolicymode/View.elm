module Agentpolicymode.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (id, class, href, type_, attribute, disabled)
import Html.Events exposing (onClick)

import Agentpolicymode.DataTypes exposing (..)


view : Model -> Html Msg
view model =
  let
    selectedMode = model.selectedSettings.policyMode
    policyModeStr : PolicyMode -> String
    policyModeStr mode =
      case mode of
        Default -> "Default"
        Audit   -> "Audit"
        Enforce -> "Enforce"
        None    -> ""

    policyModeClass : PolicyMode -> String
    policyModeClass mode =
      case mode of
        Audit   -> " audit"
        Enforce -> " enforce"
        _       -> ""

    policyModeLabel : PolicyMode -> Html Msg
    policyModeLabel mode =
      case mode of
        None    -> text ""
        Default -> span[][text "Global mode", span [class ("mode" ++ (policyModeClass model.globalPolicyMode))][]]
        _       -> span [class ("node mode" ++ (policyModeClass mode))][text (policyModeStr mode)]

    policyModeBtn : PolicyMode -> Html Msg
    policyModeBtn mode =
      case mode of
        None    -> text ""
        _       ->
          label [class ("btn btn-default" ++ policyModeClass mode ++ if selectedMode == mode then " active" else ""), onClick (SelectMode mode)]
          [ (if mode == Default then span[][text "Global mode", span [class ("mode" ++ (policyModeClass model.globalPolicyMode))][text (String.toLower (policyModeStr model.globalPolicyMode))]] else text (policyModeStr mode))
          ]

    btnSave : Bool -> Bool -> Html Msg
    btnSave saving disable =
      let
        icon = if saving then i [ class "fa fa-spinner fa-pulse"] [] else text ""
      in
        button [class ("btn btn-success btn-save btn-settings" ++ (if saving then " saving" else "")), type_ "button", disabled (saving || disable), onClick StartSaving]
        [ icon ]

    globalInfo = case model.ui.form of
      GlobalForm ->
        p[] [text "This setting is a global switch and will apply to all nodes and all directives as the default mode. You may also override this mode on a per-node and per-directive basis."]
      NodeForm _ ->
        p[]
        [ text "By default all nodes and all directives operate in the global mode defined in "
        , a [href (model.contextPath ++ "/secure/administration/settings")][text "Settings"]
        , text " which is currently "
        , b [][text (policyModeStr model.globalPolicyMode)]
        , text "."
        ]

    policyModeForm = case model.ui.form of
      GlobalForm ->
        form[class "rudder-form"]
        [ label[][text "Global policy mode"]
        , div [class "policymode-group"]
          [ div [class "btn-group"]
            [ policyModeBtn Audit
            , policyModeBtn Enforce
            ]
          ]
        , div []
          [ label [][text "Allow overrides on this default setting?"]
          , div [class "form-group"]
            [ div [class "btn-group yesno"]
              [ label [class ("btn btn-default" ++ if model.selectedSettings.overridable then " active" else ""), onClick (SelectOverrideMode True )]
                [ text "Yes" ]
              , label [class ("btn btn-default" ++ if model.selectedSettings.overridable then "" else " active"), onClick (SelectOverrideMode False)]
                [ text "No" ]
              ]
            , label [class "fit label-switch"]
              [ text ( if model.selectedSettings.overridable then
                "Make this setting a default only and allow overrides on a per-node or per-directive basis."
                else
                "This setting may not be overridden per-node or per-directive. All Rudder configuration rules will operate in this mode."
                )
              ]
            ]
          ]
        ]
      NodeForm _ ->
        if model.currentSettings.overridable then
          form[class "rudder-form"]
          [ label[][text "Override policy mode for this node"]
          , div [class "policymode-group node"]
            [ div [class "btn-group"]
              [ policyModeBtn Default
              , policyModeBtn Audit
              , policyModeBtn Enforce
              ]
              , span [class "info-mode"]
                [ text (case selectedMode of
                  Default -> "This may be overridden on a per-Directive basis."
                  Audit   -> "Directives will never be enforced on this node, regardless of their policy mode."
                  Enforce -> "All Directives will apply necessary changes on this node, except Directives with an Audit override setting."
                  _       -> ""
                )
                ]
            ]
          ]
        else
          div [class "alert alert-warning"]
          [ text "Current global settings do not allow this mode to be overridden on a per-node basis. You may change this in "
          , a [href (model.contextPath ++ "/secure/administration/settings#agentPolicyMode")] [text "Settings"]
          , text ", or contact your Rudder administrator about this."
          ]
  in
    div []
    [ div [class "explanation-text"]
      [ p[][text "Configuration rules in Rudder can operate in one of two modes:"]
      , ol[]
        [ li[]
          [ b[][text "Audit: "]
          , text "the agent will examine configurations and report any differences, but will not make any changes"
          ]
        , li[]
          [ b[][text "Enforce: "]
          , text "the agent will make changes to fix any configurations that differ from your directives"
          ]
        ]
      , globalInfo
      ]
    , if model.ui.hasWriteRights then
      div []
      [ policyModeForm
      , div [class "button-group-success"]
        [ btnSave model.ui.saving (model.currentSettings == model.selectedSettings)
        ]
      ]
      else
      div [class "col-sm-12 policymode-group readonly"]
      [ label[][text "Policy mode:"]
       , policyModeLabel model.currentSettings.policyMode
      ]
    ]
