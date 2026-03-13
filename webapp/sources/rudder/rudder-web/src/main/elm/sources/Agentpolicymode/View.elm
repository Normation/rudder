module Agentpolicymode.View exposing (..)

import Html exposing (..)
import Html.Attributes exposing (attribute, class, disabled, href, id, placeholder, tabindex, type_, value)
import Html.Events exposing (onClick, onInput)

import Agentpolicymode.DataTypes exposing (..)
import String.Extra


view : Model -> Html Msg
view model =
  let
    selectedMode = model.selectedSettings.policyMode

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

    btnSave : Bool -> Bool -> Maybe ModalSettings -> Html Msg
    btnSave saving disable modalSettings =
      let
        icon = if saving then i [ class "fa fa-spinner fa-pulse"] [] else text ""
        msg = modalSettings |> Maybe.map OpenModal |> Maybe.withDefault (StartSaving Nothing)
      in
        button [class ("btn btn-success btn-save btn-settings" ++ (if saving then " saving" else "")), type_ "button", disabled (saving || disable), onClick msg]
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
                [ Maybe.map text (explainPolicyMode selectedMode)
                |> Maybe.withDefault (text "")
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
        [ btnSave model.ui.saving (model.currentSettings == model.selectedSettings) model.ui.modalSettings
        ]
      ]
      else
      div [class "col-sm-12 policymode-group readonly"]
      [ label[][text "Policy mode:"]
       , policyModeLabel model.currentSettings.policyMode
      ]
    , viewModal model.ui.saving model.selectedSettings.policyMode model.ui.modalState
    ]


viewModal : Bool -> PolicyMode -> ModalState -> Html Msg
viewModal isSaving mode modalState =
  case modalState of
    NoModal ->
      text ""

    ConfirmModal { value, error } { prompt, isMandatory } ->
      let
        btnDisabled =
          isSaving || (isMandatory && String.Extra.isBlank value)

      in
      div [ tabindex -1, class "modal fade show d-block" ]
      [ div [class "modal-backdrop fade show", onClick CloseModal][]
      , div [ class "modal-dialog" ] [
          div [ class "modal-content" ] [
            div [ class "modal-header" ] [
              h5 [ class "modal-title" ] [ text <| policyModeStr mode ++ " policy mode"]
            , button [type_ "button", class "btn-close", onClick CloseModal, attribute "aria-label" "Close"][]
            ]
          , div [ class "modal-body" ]
            [ h4 [class "text-center"][text "Are you sure you want to update the policy mode of this node ?"]
            , (explainPolicyMode mode)
              |> Maybe.map (\msg -> div [class "alert alert-info"] [text msg])
              |> Maybe.withDefault (text "")
            , error
              |> Maybe.map (\msg -> div [class "alert alert-danger text-center col-xl-12 col-sm-12 col-md-12 text-danger"] [text msg])
              |> Maybe.withDefault (text "")
            , div []
              [ h4 [class "audit-title"] [text "Change audit log"]
              , div[class "form-group"]
                [ label[]
                  [ text "Change audit message"
                  , text (if isMandatory then " (required)" else "")
                  ]
                  , textarea [class "form-control", placeholder prompt, onInput UpdateChangeMessage, Html.Attributes.value value][]
                ]
              ]
            ]
          , div [ class "modal-footer" ] [
              button [ class "btn btn-default", onClick CloseModal ]
              [ text "Cancel " ]
            , button [ class "btn btn-success", onClick (StartSaving (Just value)), disabled btnDisabled ]
              [ text "Confirm"
              , i [ class "ms-2 fa fa-check" ] []
              ]
            ]
          ]
        ]
      ]

policyModeStr : PolicyMode -> String
policyModeStr mode =
  case mode of
    Default -> "Default"
    Audit   -> "Audit"
    Enforce -> "Enforce"
    None    -> ""


explainPolicyMode : PolicyMode -> Maybe String
explainPolicyMode mode =
  case mode of
    Default -> Just "This may be overridden on a per-directive basis."
    Audit   -> Just "Directives will never be enforced on this node, regardless of their policy mode."
    Enforce -> Just "All directives will apply necessary changes on this node, except directives with an 'Audit' override setting."
    _       -> Nothing