module Editor.ViewMethod exposing (..)

import Set
import Dict exposing (Dict)
import Dict.Extra exposing (keepOnly)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Json.Encode
import List.Extra
import Maybe.Extra
import Regex
import String.Extra
import Dom.DragDrop as DragDrop exposing (State)
import Dom exposing (..)
import Json.Decode
import VirtualDom

import Rules.ViewUtils exposing (onCustomClick)
import Editor.DataTypes exposing (..)
import Editor.MethodConditions exposing (..)
import Editor.MethodElemUtils exposing (..)
import Editor.AgentValueParser exposing (..)
import Editor.ViewMethodsList exposing (getTooltipContent)


--
-- This file deals with one method container (condition, parameters, etc)
--

{-
  CONDITION
-}

checkConstraintOnCondition: Condition -> ValidationState MethodCallConditionError
checkConstraintOnCondition condition =
  if(String.contains "\n" condition.advanced) then
    InvalidState [ReturnCarrigeForbidden]
  else
    ValidState

-- /END VERSION in the condition part --


{-
  PARAMETERS
-}

getClassParameter: Method -> MethodParameter
getClassParameter method =
  case findClassParameter method of
    Just p -> p
    Nothing -> MethodParameter method.classParameter "" "" defaultConstraint

findClassParameter: Method -> Maybe MethodParameter
findClassParameter method =
  List.Extra.find (\p -> p.name == method.classParameter) method.parameters

parameterName: MethodParameter -> String
parameterName param =
  String.replace "_" " " (String.Extra.toSentenceCase param.name.value)


showParam: Model -> MethodCall -> ValidationState MethodCallParamError -> MethodParameter -> List CallParameter -> Html Msg
showParam model call state methodParam params =
  let
    displayedValue = List.Extra.find (.id >> (==) methodParam.name ) params |> Maybe.map (.value >> displayValue) |> Maybe.withDefault ""
    isMandatory =
      if methodParam.constraints.allowEmpty |> Maybe.withDefault False then
        span [class "allow-empty"] [text ""]
      else
        span [class "mandatory-param"] [text " *"]
    errors = case state of
      InvalidState constraintErrors -> List.filterMap (\c -> case c of
                                                         ConstraintError err ->
                                                           if (err.id == methodParam.name) then
                                                             Just err.message
                                                           else
                                                             Nothing
                                                ) constraintErrors
      _ -> []
  in
  div [class "form-group method-parameter"] [
    label [ for "param-index" ] [
      span [] [
        text (String.Extra.toTitleCase methodParam.name.value)
      , isMandatory
      , text (" -")
      , span [ class "badge badge-secondary ng-binding" ] [ text methodParam.type_ ]
      ]
    , small [] [ text ( " " ++ methodParam.description) ]
    ]
  , textarea  [
        stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
      , onFocus DisableDragDrop
      , readonly (not model.hasWriteRights)
      , name "param"
      , class "form-control"
      , rows  1
      , value displayedValue
      , onInput  (MethodCallParameterModified call methodParam.name)
      -- to deactivate plugin "Grammarly" or "Language Tool" from
      -- adding HTML that make disapear textarea (see  https://issues.rudder.io/issues/21172)
      , attribute "data-gramm" "false"
      , attribute "data-gramm_editor" "false"
      , attribute "data-enable-grammarly" "false"
      , spellcheck False
      ] []
  , if (not (List.isEmpty errors)) then ul [ class "list-unstyled" ]
      (List.map (\e -> li [ class "text-danger" ] [ text e ]) errors)
    else text ""
  ]

accumulateValidationState: List (ValidationState a) -> ValidationState a -> ValidationState a
accumulateValidationState validations base =
  List.foldl (\c acc -> case (acc,  c) of
                          (InvalidState errAcc,InvalidState err ) -> InvalidState (List.concat [ err, errAcc ] )
                          (InvalidState err, _) -> InvalidState err
                          (_, InvalidState err) -> InvalidState err
                          (_, ValidState) -> ValidState
                          (ValidState, Unchanged) -> ValidState
                          (Unchanged, Unchanged) -> Unchanged
             ) base validations

accumulateErrorConstraint: CallParameter -> Constraint -> ValidationState MethodCallParamError -> ValidationState MethodCallParamError
accumulateErrorConstraint call constraints base =
  accumulateValidationState [ (checkConstraintOnParameter call constraints) ] base

checkConstraintOnParameter: CallParameter -> Constraint -> ValidationState MethodCallParamError
checkConstraintOnParameter call constraint =
  let
    checkEmpty =  if not (constraint.allowEmpty  |> Maybe.withDefault False) && isEmptyValue call.value then [ConstraintError { id = call.id, message = ("Parameter '"++call.id.value++"' is empty")}] else []
    checkWhiteSpace =
      if constraint.allowWhiteSpace  |> Maybe.withDefault False then
        []
      else
        case Regex.fromString "(^\\s)|(\\s$)" of
          Nothing -> []
          Just r -> if Regex.contains r (displayValue call.value) then [ConstraintError { id = call.id, message = ( "Parameter '"++call.id.value++"' start or end with whitespace characters"  ) } ] else []

    checkMax = if lengthValue call.value >= (constraint.maxLength  |> Maybe.withDefault 16384) then  [ConstraintError  { id = call.id, message = ("Parameter '"++call.id.value++"' should be at most " ++ (String.fromInt (constraint.maxLength |> Maybe.withDefault 16384) ) ++ " long" ) } ]else []
    min = Maybe.withDefault 0 constraint.minLength
    checkMin = if lengthValue call.value < min then [ConstraintError { id = call.id, message = ("Parameter '"++call.id.value++"' should be at least " ++ (String.fromInt min) ++ " long") } ] else []
    regexValue = Maybe.map Regex.fromString constraint.matchRegex |> Maybe.Extra.join
    checkRegex = case regexValue of
                      Nothing ->  []
                      Just regex ->
                        if Regex.contains regex (displayValue call.value) then
                          []
                        else
                          [ConstraintError { id = call.id, message = ( "Parameter '" ++ call.id.value ++"' should match the following regexp: " ++ (Maybe.withDefault "" constraint.matchRegex)  )} ]
    nonRegexValue = Maybe.map Regex.fromString constraint.notMatchRegex |> Maybe.Extra.join
    notRegexCheck = case  nonRegexValue of
                      Nothing ->  []
                      Just regex -> if Regex.contains regex (displayValue call.value) then
                                       [ConstraintError { id = call.id, message = ("Parameter '" ++ call.id.value ++"' should not match the following regexp: " ++ (Maybe.withDefault "" constraint.notMatchRegex) ) }]
                                    else
                                      []
    checkSelect = Maybe.map ( \ select -> if List.any ( .value >> (==) (displayValue call.value) ) select then
                     []
                   else
                     [ConstraintError { id = call.id, message =  ( "Parameter '" ++ call.id.value ++ "'  should be one of the value from the following list: " ++ (String.join ", " (select |> List.map .value)) )} ]
                  ) constraint.select |> Maybe.withDefault []
    checks = [ checkEmpty, checkWhiteSpace, checkMax, checkMin, checkRegex, notRegexCheck, checkSelect ] |> List.concat
  in
    if List.isEmpty checks then ValidState else InvalidState checks
{-
  DISPLAY ONE METHOD EXTENDED
-}

showMethodTab: Model -> Method -> Maybe CallId ->  MethodCall -> MethodCallUiInfo -> Html Msg
showMethodTab model method parentId call uiInfo=
  case uiInfo.tab of
    CallReporting ->
      div [ class "tab-parameters"] [
        div [ class "form-group"] [
          label [ for "disable_reporting", style "margin-right" "5px"] [ text "Disable reporting"]
        , input [ readonly (not model.hasWriteRights), type_ "checkbox", name "disable_reporting", checked call.disableReporting,  onCheck  (\b -> MethodCallModified (Call parentId {call  | disableReporting = b }))] []
        ]
      ]
    CallParameters ->
      div [ class "tab-parameters"] (List.map (\m  -> showParam model call uiInfo.validation m call.parameters )  method.parameters )
    CallConditions ->
      let
        condition = call.condition
        errorOnConditionInput =
          if(String.contains "\n" call.condition.advanced) then
            ul [ class "list-unstyled" ] [ li [ class "text-danger" ] [ text "Return carriage is forbidden in condition" ] ]
          else
            div[][]
        ubuntuLi = List.map (\ubuntuMinor ->
                     let
                       updatedCall = Call parentId { call | condition = {condition | os =  updateUbuntuMinor  ubuntuMinor condition.os } }
                     in
                       li [ onClick (MethodCallModified updatedCall) ] [ a [class "dropdown-item"] [ text (showUbuntuMinor ubuntuMinor) ] ]

                   ) [All, ZeroFour, Ten]
        updateConditonVersion = \f s ->
                      let
                        updatedCall = Call parentId { call | condition = {condition | os =  f  (String.toInt s) condition.os } }
                      in
                        MethodCallModified updatedCall
      in
      div [ class "tab-conditions"] [
        div [class "form-group condition-form", id "os-form"] [
          div [ class "form-inline" ] [
            div [ class "form-group" ] [
              label [ style "display" "inline-block", for ("OsCondition-" ++ call.id.value)]
              [ text "Operating system: " ]
            , div [ class "btn-group" ]
              [ button [ class "btn btn-default dropdown-toggle", id ("OsCondition-" ++ call.id.value), attribute  "data-bs-toggle" "dropdown"
                , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)) ]
                [ text ((osName condition.os) ++ " ")
                , span [ class "caret" ] []
                ]
              , ul [ class "dropdown-menu" ]
                 ( List.map (\os ->
                     let
                       updatedCondition = {condition | os = os }
                     in
                       li [ onClick (MethodCallModified (Call parentId {call | condition = updatedCondition })), class (osClass os) ] [ a [class "dropdown-item"] [ text (osName os) ] ] ) osList )
              ]
            , if (hasMajorMinorVersion condition.os || isUbuntu condition.os ) then
                input [ readonly (not model.hasWriteRights)
                      , value (Maybe.withDefault "" (Maybe.map String.fromInt (getMajorVersion condition.os) ))
                      , onInput (updateConditonVersion updateMajorVersion)
                      , type_ "number", style "display" "inline-block", style "width" "auto", style "margin-left" "5px"
                      , style "margin-top" "0",  class "form-control", placeholder "Major version"
                      , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                      ] []
              else text ""
            , if (hasMajorMinorVersion condition.os ) then
                input [ readonly (not model.hasWriteRights)
                      , value (Maybe.withDefault "" (Maybe.map String.fromInt (getMinorVersion condition.os) ))
                      , onInput (updateConditonVersion updateMinorVersion)
                      , type_ "number", style "display" "inline-block", style "width" "auto", class "form-control"
                      , style "margin-left" "5px", style "margin-top" "0", placeholder "Minor version"
                      , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                      ] []
              else text ""
           , if  ( isUbuntu condition.os ) then
               div [ style  "margin-left" "5px", class "btn-group" ]
                 [ button
                   [ class "btn btn-default dropdown-toggle", id "ubuntuMinor" , attribute  "data-bs-toggle" "dropdown"
                   , attribute  "aria-haspopup" "true", attribute "aria-expanded" "true"
                   , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                   ]
                   [ text  ((getUbuntuMinor condition.os) ++ " ")
                   , span [ class"caret" ] []
                   ]
                 , ul [ class "dropdown-menu", attribute "aria-labelledby" "ubuntuMinor" ] ubuntuLi

                 ]
              else text ""


            , if (hasVersion condition.os ) then
                input [ readonly (not model.hasWriteRights)
                      , value (Maybe.withDefault "" (Maybe.map String.fromInt (getVersion condition.os) ))
                      , onInput  (updateConditonVersion updateVersion)
                      , type_ "number", style "display" "inline-block", style "width" "auto"
                      , class "form-control", style "margin-left" "5px", style "margin-top" "0", placeholder "Version"
                      , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                      ] []
              else text ""
            , if (hasSP condition.os ) then
                input [ readonly (not model.hasWriteRights)
                      , value (Maybe.withDefault "" (Maybe.map String.fromInt (getSP condition.os) ))
                      , onInput (updateConditonVersion updateSP)
                      , type_ "number", style "display" "inline-block", style "width" "auto", class "form-control"
                      , style "margin-left" "5px", style "margin-top" "0", placeholder "Service pack"
                      , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                      ] []
              else text ""
            ]
          ]
        ]
      , div [ class "form-group condition-form" ] [
          label [ for "advanced"] [ text "Other conditions:" ]
        , textarea [  readonly (not model.hasWriteRights)
                   , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                   , onFocus DisableDragDrop
                   , name "advanced"
                   , class "form-control"
                   , rows 1
                   , id "advanced"
                   , value condition.advanced
                   , attribute "onkeypress" "if (event.keyCode == 13) alert('You pressed the return button.'); return false;"
                   , onInput (\s ->
                     let
                       updatedCondition = {condition | advanced = s }
                       updatedCall = Call parentId {call | condition = updatedCondition }
                     in MethodCallModified updatedCall)
                   -- to deactivate plugin "Grammarly" or "Language Tool" from
                   -- adding HTML that make disapear textarea (see  https://issues.rudder.io/issues/21172)
                   , attribute "data-gramm" "false"
                   , attribute "data-gramm_editor" "false"
                   , attribute "data-enable-grammarly" "false"
                   , spellcheck False
                   ] []
        , errorOnConditionInput
       ]
      , div [ class "form-group condition-form" ] [
          label [ for "class_context" ] [ text "Applied condition expression:" ]
        , textarea [
            readonly (not model.hasWriteRights)
            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
            , onFocus DisableDragDrop
            , name "class_context"
            , class "form-control"
            , rows 1
            , id "class_context"
            , value (conditionStr condition)
            , readonly True
            -- to deactivate plugin "Grammarly" or "Language Tool" from
            -- adding HTML that make disapear textarea (see  https://issues.rudder.io/issues/21172)
            , attribute "data-gramm" "false"
            , attribute "data-gramm_editor" "false"
            , attribute "data-enable-grammarly" "false"
            , spellcheck False
            ] []
        , if String.length (conditionStr condition) > 2048 then
            span [ class "text-danger" ] [text "Classes over 2048 characters are currently not supported." ]
          else
            text ""
        ]
      ]
    Result     ->
      let
        classParameter = getClassParameter method
        paramValue = call.parameters |> List.Extra.find (\c -> c.id == classParameter.name) |> Maybe.map (.value)  |> Maybe.withDefault [Value ""]
      in
      div [ class "tab-result" ] [
        label [] [
          small [] [ text "Result conditions defined by this method" ]
        ]
      , div [ class "form-horizontal editForm result-class" ] [
          div [ class "input-group result-success" ] [
            div [ class "input-group-text" ] [
              text "Success"
            ]
          , input [ readonly True, type_ "text", class "form-control",  value (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_kept")
                   , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)) , stopPropagationOn "click" (Json.Decode.succeed (DisableDragDrop, True)) ] []
          , button [ class "btn btn-outline-secondary clipboard", type_ "button", title "Copy to clipboard", onClick (Copy (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_kept")) ] [
              i [ class "ion ion-clipboard" ] []
            ]
          ]
        , div [ class "input-group result-repaired" ] [
            div [ class "input-group-text" ] [
              text "Repaired"
            ]
          , input [ readonly True, type_ "text", class "form-control",  value (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_repaired")
                   , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)) , stopPropagationOn "click" (Json.Decode.succeed (DisableDragDrop, True)) ] []
          , button [ class "btn btn-outline-secondary clipboard", type_ "button" , title "Copy to clipboard" , onClick (Copy (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_repaired")) ] [
              i [ class "ion ion-clipboard" ] []
            ]
          ]
        , div [ class "input-group result-error" ] [
            div [ class "input-group-text" ] [
              text "Error"
            ]
          , input [ readonly True, type_ "text", class "form-control",  value (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_error")
                   , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)) , stopPropagationOn "click" (Json.Decode.succeed (DisableDragDrop, True)) ] []
          , button [ class "btn btn-outline-secondary clipboard", type_ "button", title "Copy to clipboard", onClick (Copy (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_error")) ] [
              i [ class "ion ion-clipboard" ] []
            ]
          ]
        ]
      ]
    ForEach ->
      let
        foreachUI = uiInfo.foreachUI
        newForeach = foreachUI.newForeach

        newKeys : Bool -> List (Html Msg)
        newKeys edit = newForeach.foreachKeys
          |> List.reverse
          |> List.map (\k ->
            span[class ("d-inline-flex align-items-center me-2" ++ if edit then " ps-2" else " p-2")]
            [ text k
            , ( if edit then
              i[ class "fa fa-times p-2 cursorPointer", onCustomClick (UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = {newForeach | foreachKeys = (List.Extra.remove k newForeach.foreachKeys)}}}) ][]
              else
              text ""
            )
            ]
          )

        tabContent = case call.foreachName of
          Nothing ->
            let
              newItem = newForeach.foreachKeys
                |> List.map (\k -> (k, ""))
                |> Dict.fromList

              newDefaultForeach = defaultNewForeach call.foreachName call.foreach
            in
              div[]
              [ div [class "form-group col-12 col-md-6 col-lg-4 mb-3"]
                [ label [for "foreachName", class "form-label"]
                  [ text "Iterator name"
                  ]
                , input
                  [ type_ "text"
                  , class "form-control"
                  , id "foreachName"
                  , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                  , onFocus DisableDragDrop
                  , value newForeach.foreachName
                  , onInput  (\s -> UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = {newForeach | foreachName = s}}})
                  ]
                  []
                ]
              , div [class "form-group col-12 col-md-6 col-lg-4"]
                [ label [for "foreachKeys", class "form-label"]
                  [ text "Iterator keys"
                  ]
                , div [class "input-group"]
                  [ input
                    [ type_ "text"
                    , class "form-control"
                    , id "foreachKeys"
                    , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                    , onFocus DisableDragDrop
                    , value newForeach.newKey
                    , onInput  (\s -> UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = {newForeach | newKey = s}}})
                    ][]
                  , button
                    [ class "btn btn-default"
                    , type_ "button"
                    , onCustomClick (UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = {newForeach | newKey = "", foreachKeys = (newForeach.newKey :: newForeach.foreachKeys)}}})
                    , disabled (String.isEmpty newForeach.newKey)
                    ]
                    [ i[class "fa fa-plus-circle"][]
                    ]
                  ]
                ]
              , div [class "col-12 col-md-6 col-lg-8 foreach-keys mt-2 mb-3"] ( newKeys True )
              , div []
                [ button[class "btn btn-default me-3", onCustomClick (UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = newDefaultForeach}}) ]
                  [ text "Reset"
                  , i[class "fa fa-undo ms-1"][]
                  ]
                , button
                  [ class "btn btn-primary"
                  , disabled ((String.isEmpty newForeach.foreachName) || (List.isEmpty newForeach.foreachKeys))
                  , onCustomClick (UpdateMethodAndUi call.id {uiInfo | foreachUI = {foreachUI | newForeach = { newDefaultForeach | newItem = newItem}}} (Call (Just call.id) {call | foreachName = Just newForeach.foreachName}))
                  ]
                  [ text "Add foreach"
                  , i[class "fa fa-check ms-1"][]
                  ]
                ]
              ]
          Just foreachName ->
            let
              keys = Dict.keys newForeach.newItem

              header = keys
                |> List.map (\k -> th[][text k])

              foreachItems = case call.foreach of
                Just foreach ->
                  foreach
                  |> List.map (\f ->
                    let
                      values = keys
                        |> List.map (\k ->
                          let
                            val = case Dict.get k f of
                              Just v -> v
                              Nothing -> ""

                            updateForeachVal : String -> List (Dict String String) -> Dict String String -> Maybe (List (Dict String String))
                            updateForeachVal newVal list currentForeach =
                              Just (List.Extra.updateIf (\i -> i == currentForeach) (\i -> Dict.update k (always (Just newVal)) i) list)
                          in
                            td[]
                              [ input
                                [ type_ "text"
                                , value val
                                , class "form-control input-sm"
                                , onInput(\s ->
                                  (MethodCallModified (Call (Just call.id) {call | foreach = (updateForeachVal s foreach f) }))
                                )
                                ][]
                              ]
                        )
                      actionBtns =
                        [ td[class "text-center"]
                          [ button[type_ "button", class "btn btn-danger", onCustomClick (MethodCallModified (Call (Just call.id) {call | foreach = Just (List.Extra.remove f foreach) }))]
                            [ i[class "fa fa-times"][]
                            ]
                          ]
                        ]
                    in
                      tr[class "item-foreach"] (List.append values actionBtns)
                  )
                Nothing -> []

              newItemRow =
                let
                  newValues = keys
                    |> List.map (\k ->
                      let
                        val = case Dict.get k newForeach.newItem of
                          Just v -> v
                          Nothing -> ""
                      in
                        td[]
                          [ input
                            [ type_ "text"
                            , class "form-control input-sm"
                            , value val
                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                            , onFocus DisableDragDrop
                            , onInput (\s ->
                                UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = {newForeach | newItem = Dict.update k (always (Just s) ) newForeach.newItem }}}
                            )
                            ]
                            []
                          ]
                    )
                  actionBtns =
                    let
                      newItems = case call.foreach of
                        Just f -> List.append f [newForeach.newItem]
                        Nothing -> [newForeach.newItem]
                      newItem = newForeach.newItem
                        |> Dict.map (\k v -> "")
                    in
                      [ td[class "text-center"]
                        [ button[type_ "button", class "btn btn-success" , onCustomClick (UpdateMethodAndUi call.id {uiInfo | foreachUI = {foreachUI | newForeach = { newForeach | newItem = newItem}}} (Call (Just call.id) {call | foreach = Just newItems}))]
                          [ i[class "fa fa-plus-circle"][]
                          ]
                        ]
                      ]
                in
                  [ tr[class "item-foreach new"] (List.append newValues actionBtns) ]

              newForeachItems =
                case call.foreach of
                  Nothing -> Nothing
                  Just foreach ->
                    let
                      newKeysList = newForeach.foreachKeys
                      updatedForeach = foreach
                        |> List.Extra.updateIf (\f -> (Dict.keys f) |> List.any (\k -> List.Extra.notMember k newKeysList) ) -- If an old key is not present anymore, remove it
                          (\f -> f |> keepOnly (Set.fromList newKeysList) )
                        |> List.Extra.updateIf (\f -> newKeysList |> List.any (\k -> List.Extra.notMember k (Dict.keys f)) ) -- If a new key is detected, insert it
                          (\f ->
                            let
                              keysList  = Dict.keys f
                              currentList = Dict.toList f
                              newList = newKeysList
                                |> List.Extra.filterNot (\k -> List.member k keysList)
                                |> List.map (\k -> (k, ""))
                            in
                              currentList
                                |> List.append newList
                                |> Dict.fromList
                          )
                    in
                      Just updatedForeach

              updatedNewItem = newForeach.foreachKeys
                              |> List.map (\k -> (k, ""))
                              |> Dict.fromList
            in
              div[]
              [ div [class "row mb-3 form-group"]
                [ label [for "foreachName", class "col-auto col-form-label"]
                  [ text "Iterator name: " ]
                , div [class "col d-flex align-items-center"]
                  ( if foreachUI.editName then
                    [ div [class "input-group w-50"]
                      [ input
                        [ type_ "text"
                        , class "form-control"
                        , id "foreachName"
                        , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                        , onFocus DisableDragDrop
                        , value newForeach.foreachName
                        , onInput (\s -> UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = {newForeach | foreachName = s}}})
                        ][]
                      , button
                        [ class "btn btn-default"
                        , type_ "button"
                        , onCustomClick (UpdateMethodAndUi call.id {uiInfo | foreachUI = {foreachUI | editName = False}} (Call (Just call.id) {call | foreachName = Just (newForeach.foreachName) }))
                        , disabled (String.isEmpty newForeach.foreachName)
                        ]
                        [ i[class "fa fa-check"][]
                        ]
                      ]
                    ]
                  else
                    [ text foreachName
                    , i[class "fa fa-edit ms-2", onCustomClick (UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = {newForeach | foreachName = foreachName}, editName = True}})][]
                    ]
                  )
                ]
              , div[class "row mb-3 form-group"]
                [ label [for "foreachKeys", class "col-auto col-form-label"]
                    [ text "Iterator keys:"
                    ]
                  , ( if foreachUI.editKeys then
                    div[class "col d-flex"]
                      [ div[class "d-flex row w-100"]
                        [ div [class "input-group group-key"]
                          [ input
                            [ type_ "text"
                            , class "form-control"
                            , id "foreachKeys"
                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                            , onFocus DisableDragDrop
                            , value newForeach.newKey
                            , onInput  (\s -> UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = {newForeach | newKey = s}}})
                            ][]
                          , button
                            [ class "btn btn-default"
                            , type_ "button"
                            , onCustomClick (UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | newForeach = {newForeach | newKey = "", foreachKeys = (newForeach.newKey :: newForeach.foreachKeys)}}})
                            , disabled (String.isEmpty newForeach.newKey)
                            ]
                            [ i[class "fa fa-plus-circle"][]
                            ]
                            , button
                              [ class "btn btn-default ms-2"
                              , type_ "button"
                              , onCustomClick (UpdateMethodAndUi call.id {uiInfo | foreachUI = {foreachUI | editKeys = False, newForeach = {newForeach | newItem = updatedNewItem}}} (Call (Just call.id) {call | foreach = newForeachItems }))
                              , disabled (List.isEmpty newForeach.foreachKeys)
                              ]
                              [ i[class "fa fa-check"][]
                              ]
                          ]
                        , div [class "col-12 col-md-6 col-lg-8 foreach-keys mt-2 mb-3"] ( newKeys True )
                        ]
                      ]
                    else
                    div[class "col d-flex align-items-center foreach-keys"]
                      ( List.append (newKeys False) [i[class "fa fa-edit ms-2", onCustomClick (UIMethodAction call.id {uiInfo | foreachUI = {foreachUI | editKeys = True}})][]]
                      )
                    )
                ]
              , div[class "table-foreach-container"]
                [ table[class "table table-bordered table-foreach mb-0"]
                  [ thead[]
                    [ tr[] (List.append header [th[][text "Action"]])
                    ]
                  , tbody[] (List.append foreachItems newItemRow)
                  ]
                ]
              , button [class "btn btn-danger mt-2"
                , onCustomClick (UpdateMethodAndUi call.id {uiInfo | foreachUI = {foreachUI | newForeach = (defaultNewForeach Nothing Nothing)}} (Call (Just call.id) {call | foreachName = Nothing, foreach = Nothing }))]
                [ text "Remove iterator"
                , i[class "fa fa-times ms-1"][]
                ]
              ]
      in
        div [ class "tab-result" ]
          [ tabContent
          ]

methodDetail: Method -> MethodCall -> Maybe CallId -> MethodCallUiInfo -> Model -> Html Msg
methodDetail method call parentId ui model =
  let
    activeClass = (\c -> if c == ui.tab then "active" else "" )
    (nbForeach, foreachClass) = case call.foreachName of
      Nothing ->
        ( "0"
        , ""
        )
      Just f ->
        let
          nb = case call.foreach of
              Just foreach -> String.fromInt (List.length foreach) --TODO : To improve
              Nothing -> "0"
        in
          ( nb
          , " has-foreach"
          )

  in
  div [ class "method-details" ] [
    div [] [
      ul [ class "tabs-list"] [
        li [ class (activeClass CallParameters),  stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallParameters}, True)) ] [text "Parameters"] -- click select param tabs, class active if selected
      , li [ class (activeClass CallConditions),stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallConditions}, True)) ] [text "Conditions"]
      , li [ class (activeClass Result), stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = Result}, True))] [text "Result conditions"]
      , li [ class (activeClass CallReporting), stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallReporting}, True)) ] [text "Reporting"]
      , li [ class (activeClass ForEach), stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = ForEach}, True)) ]
        [ text "Foreach"
        , span[class ("badge" ++ foreachClass)]
          [ text nbForeach
          , i[class "fa fa-retweet ms-1"][]
          ]
        ]
      ]
    , div [ class "tabs" ] [ (showMethodTab model method parentId call ui) ]
    ]
  ]


showMethodCall: Model -> MethodCallUiInfo -> TechniqueUiInfo -> Maybe CallId ->  MethodCall -> Element Msg
showMethodCall model ui tui parentId call =
  let
    isHovered = case model.isMethodHovered of
                  Just methodId -> if (methodId.value == call.id.value) then "hovered" else ""
                  Nothing       -> ""
  in
  element "li"
  |> addClass (if (ui.mode == Opened) then "active" else isHovered)
  |> addClass "card-method showMethodCall"
  |> appendChild (callBody model ui tui call parentId)
  |> addAttribute (hidden (Maybe.withDefault False (Maybe.map ((==) (Move (Call parentId  call))) (DragDrop.currentlyDraggedObject model.dnd) )))
  |> addAction ("mouseover" , HoverMethod (Just call.id))
  |> addActionStopPropagation ("mouseleave" , HoverMethod Nothing)

callBody : Model -> MethodCallUiInfo -> TechniqueUiInfo ->  MethodCall -> Maybe CallId -> Element Msg
callBody model ui techniqueUi call pid =
  let
    method = case Dict.get call.methodName.value model.methods of
                   Just m -> m
                   Nothing -> Method call.methodName call.methodName.value "" "" (Maybe.withDefault (ParameterId "") (Maybe.map .id (List.head call.parameters))) [] [] Nothing Nothing Nothing

    deprecatedClass = "fa fa-info-circle method-action text-info" ++
                         case method.deprecated of
                           Just _ -> " deprecated-icon"
                           Nothing -> ""
    classParameter = getClassParameter method
    paramValue = call.parameters |> List.Extra.find (\c -> c.id == classParameter.name) |> Maybe.map (.value)  |> Maybe.withDefault [Value ""]

    isHovered = case model.isMethodHovered of
                  Just methodId -> (methodId.value == call.id.value) && ui.mode == Closed
                  Nothing -> False
    (textClass, tooltipContent) = case ui.validation of
                  InvalidState [_] -> ("text-danger", "A parameter of this method is invalid")
                  InvalidState err -> ("text-danger", (String.fromInt (List.length err)) ++ " parameters of this method are invalid")
                  Unchanged -> ("","")
                  ValidState -> ("text-primary","This method was modified")
    dragElem =  element "div"
                |> addClass "cursorMove"

                |> Dom.appendChild
                           ( element "i"
                             |> addClass "fa"
                             |> addClassConditional "fa-cog" (ui.mode == Closed && not isHovered)
                             |> addClassConditional "fa-edit" isHovered
                             |> addClassConditional "fa-check" (ui.mode == Opened)
                             |> addClass textClass
                             |> addStyleConditional ("font-style", "20px") (ui.mode == Opened)
                             |> addAttribute (type_ "button")
                           )
                |> addAction ("click",  UIMethodAction call.id {ui | mode = if(ui.mode == Opened) then Closed else Opened})
    cloneIcon = element "i" |> addClass "fa fa-clone"
    cloneButton = element "button"
                  |> addClass "text-success method-action"
                  |> addActionStopAndPrevent ("click", GenerateId (\s -> CloneElem (Call pid call) (CallId s)))
                  |> addAttributeList
                     [ type_ "button"
                     , title "Clone this method"
                     ]
                  |> appendChild cloneIcon
    removeIcon = element "i" |> addClass "fa fa-times-circle"
    removeButton = element "button"
                  |> addClass "text-danger method-action"
                  |> addActionStopAndPrevent ("click", RemoveMethod call.id)
                  |> addAttributeList
                     [ type_ "button"
                     , title "Remove this method"
                     ]
                  |> appendChild removeIcon
    resetIcon = element "i" |> addClass "fa fa-rotate-right"
    resetButton = element "button"
                  |> addClass "method-action"
                  |> addActionStopAndPrevent ("click", ResetMethodCall (Call pid call))
                  |> addAttributeList
                     [ type_ "button"
                     , title "Reset this method"
                     ]
                  |> appendChild resetIcon
    docIcon = element "i" |> addClass "fa fa-book"
    docButton = element "button"
                  |> addClass "text-info method-action"
                  |> addActionStopAndPrevent ("click", ShowDoc call.methodName)
                  |> addAttributeList
                     [ type_ "button"
                     , title "Show documentation"
                     ]
                  |> appendChild docIcon
{--
, div [ class "method-details-footer"] [
          case method.documentation of
            Just _ ->
              let
                classes = "btn btn-sm btn-primary " ++
                          if List.member method.id model.methodsUI.docsOpen then "doc-opened" else ""
              in
                button [ class classes, type_ "button", onClick (ShowDoc call.methodName) ] [
                  text "Show docs "
                , i [ class "fa fa-book"] []
                ]
            Nothing -> text ""
        ]
--}
    condition = element "div"
                |> addClass "method-condition flex-form"
                |> addClassConditional "d-none" (call.condition.os == Nothing && call.condition.advanced == "")
                |> appendChildList
                   [ element "label"
                     |> appendText "Condition:"
                   , element "span"
                     |> appendText (conditionStr call.condition)
                     |> addActionStopPropagation ("mousedown" , DisableDragDrop)
                     |> addActionStopPropagation ("click" , DisableDragDrop)
                  ]
    shoudHoveredMethod = case model.isMethodHovered of
                           Just methodId -> if((methodId.value == call.id.value) && ui.mode == Closed) then " hovered" else ""
                           Nothing -> ""
    methodNameLabelClass =
      if List.isEmpty (Maybe.Extra.toList (Dict.get method.id.value model.methods)) then
        "gm-label-unknown-name"
      else
        ""

    policyModeLabel =
        case call.policyMode of
          Nothing -> "gm-label-default"
          Just Audit -> "label-audit"
          Just Enforce -> "label-enforce"

    appendForeachLabel =
      let
        nbForeach = case call.foreach of
          Just foreach -> String.fromInt (List.length foreach) --TODO : To improve
          Nothing -> "0"

        labelTxt = case call.foreachName of
          Nothing -> ""
          Just f  -> "foreach ${" ++ f ++ ".x}"

      in
        appendChildConditional
          ( element "div"
            |> addClass ("gm-label rudder-label gm-foreach d-inline-flex ps-0 overflow-hidden")
            |> appendChild
              ( element "span"
                |> addClass "counter px-1 me-1"
                |> appendText nbForeach
                |> appendChild
                  ( element "i"
                    |> addClass "fa fa-retweet ms-1"
                  )
              )
            |> appendChild
              ( element "span"
                |> appendText labelTxt
              )
          )
          ( Maybe.Extra.isJust call.foreachName )

    appendLeftLabels = appendChild
                         ( element "div"
                           |> addClass ("gm-labels left")
                           |> appendChild
                              ( element "div"
                                |> addClass ("gm-label rudder-label gm-label-name ")
                                |> appendText "Method"
                              )
                           |> appendChild
                              ( element "div"
                                |> addClass ("gm-label rudder-label gm-label-name " ++ methodNameLabelClass)
                                |> appendText method.name
                              )
                           |> appendForeachLabel
                         )
    appendRightLabels = appendChild
      ( case ui.mode of
          Closed -> element "div"
                      |> addClass ("gm-labels")
                      |> appendChildConditional
                        ( element "div"
                          |> addClass ("gm-label rudder-label " ++ policyModeLabel)
                        )
                        (Maybe.Extra.isJust call.policyMode)

          Opened -> element "div"
                      |> addClass ("gm-labels " ++ methodNameLabelClass)
                      |> appendChild
                          ( element "div" |> addClass "gm-label rudder-label gm-label-label" |> appendText "Policy mode override:")
                      |> appendChild
                         ( element "div"
                           |> addClass "btn-group"
                           |> appendChildList
                             [ element "button"
                               |> addClass ("btn dropdown-toggle rudder-label gm-label " ++ policyModeLabel)
                               |> addAttribute (attribute  "data-bs-toggle" "dropdown")
                               |> appendText (case call.policyMode of
                                               Nothing -> "None"
                                               Just Enforce -> " "
                                               Just Audit -> " "
                                             )
                             , element "ul"
                               |> addClass "dropdown-menu"
                               |> appendChildList [
                                  element "li"
                                   |> appendChild
                                      (element "a"
                                        |> addAction ("click",  MethodCallModified (Call pid {call  | policyMode = Nothing }) )
                                        |> addClass "dropdown-item"
                                        |> appendText "None"
                                      )
                                 , element "li"
                                   |> appendChild
                                      (element "a"
                                        |> addAction ("click",  MethodCallModified (Call pid {call  | policyMode = Just Audit }) )
                                        |> addClass "dropdown-item"
                                        |> appendText "Audit"
                                      )
                                 , element "li"
                                   |> appendChild
                                      (element "a"
                                        |> addAction ("click",  MethodCallModified (Call pid {call  | policyMode = Just Enforce }) )
                                        |> addClass "dropdown-item"
                                        |> appendText "Enforce"
                                      )
                                  ]
                               ])
      )
    methodName = case ui.mode of
                   Opened -> element "div"
                             |> addClass "method-name"
                             |> appendChild
                                ( element "div"
                                    |> addClass ("component-name-wrapper")
                                    |> appendChild
                                       ( element "div"
                                         |> addClass "form-group"
                                         |> appendChildList
                                           [ element "div"
                                             |> addClass "title-input-name"
                                             |> appendText "Name"
                                           , element "input"
                                             |> addAttributeList [ readonly (not model.hasWriteRights), stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)), onFocus DisableDragDrop, type_ "text", name "component", style "width" "100%", class "form-control", value call.component,  placeholder "A friendly name for this component" ]
                                             |> addInputHandler  (\s -> MethodCallModified (Call pid {call  | component = s }))
                                           ]
                                       )
                                )
                   Closed -> element "div"
                             |> addClass "method-name"
                             |> appendChild
                                ( element "span"
                                  |> addClass "name-content"
                                  |> appendText  (if (String.isEmpty call.component) then method.name else call.component )
                                  |> addActionStopPropagation ("mousedown" , DisableDragDrop)
                                  |> addActionStopPropagation ("click" , DisableDragDrop)
                                  |> addActionStopPropagation ("mouseover" , HoverMethod Nothing)
                                )

    methodContent = element "div"
                    |> addClass  "method-param flex-form"
                    |> appendChildList
                       [ element "label" |> appendText ((parameterName classParameter) ++ ": ")
                       , element "span"
                         |> addClass "label-value"
                         |> appendText (displayValue paramValue)
                         |> addActionStopPropagation ("mousedown" , DisableDragDrop)
                         |> addActionStopPropagation ("click" , DisableDragDrop)
                         |> addActionStopPropagation ("mouseover" , HoverMethod Nothing)
                       ]

    currentDrag = case DragDrop.currentlyDraggedObject model.dnd of
                    Just (Move x) -> getId x == call.id
                    Nothing -> False
                    _ -> False
  in
  element "div"
  |> addClass "method"
  |> addAttribute (id call.id.value)
  |> addAttribute (hidden currentDrag)
  |> addActionStopPropagation ("mousedown", EnableDragDrop call.id)
  |> (if techniqueUi.enableDragDrop == (Just call.id) then DragDrop.makeDraggable model.dnd (Move (Call pid call)) dragDropMessages else identity)
  |> addActionStopAndPrevent ( "dragend", CompleteMove)
  |> Dom.appendChildList
     [ dragElem
     , element "div"
       |> addClass ("method-info" ++ shoudHoveredMethod)
       |> addActionStopPropagation ("mouseleave" , HoverMethod Nothing)
       |> addClassConditional ("closed") (ui.mode == Closed)
       |> addAction ("click",  UIMethodAction call.id {ui | mode = Opened})
       |> appendChildList
          [ element "div"
            |> addClass "flex-column"
            |> addAction ("click",  UIMethodAction call.id {ui | mode = Opened})
            |> appendLeftLabels
            |> appendRightLabels
            |> appendChild (
               element "div"
                 |> addClass "btn-holder"
                 |> addAttribute (hidden (not model.hasWriteRights))
                 |> appendChildList
                    [ removeButton
                    , resetButton
                    , cloneButton
                    , ( case method.documentation of
                      Just _ -> docButton
                      Nothing -> element "span"
                    )
                    , element "span" |> addAttributeList
                                        [ class deprecatedClass
                                        , attribute "data-bs-toggle" "tooltip"
                                        , attribute "data-bs-placement" "top"
                                        , attribute "data-bs-html" "true"
                                        , title (getTooltipContent method)
                                        ]
                    ]
               )
            |> appendChild condition-- (call.condition.os /= Nothing || call.condition.advanced /= "")
            |> appendChild methodName
            --|> appendChild methodNameId
            |> appendChildConditional methodContent (ui.mode == Closed)
            |> appendChildConditional
                        ( element "div"
                          |> addClass "method-details"
                          |> appendNode (methodDetail method call pid ui model )
                          |> addAttribute (VirtualDom.property "draggable" (Json.Encode.bool (techniqueUi.enableDragDrop == Just call.id)))

                        ) (ui.mode == Opened)

         ]
    ]
