module ViewMethod exposing (..)

import DataTypes exposing (..)
import Dict
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Json.Encode
import List.Extra
import MethodConditions exposing (..)
import Regex
import String.Extra
import MethodElemUtils exposing (..)
import Dom.DragDrop as DragDrop exposing (State)
import Dom exposing (..)
import Json.Decode
import AgentValueParser exposing (..)
import ViewMethodsList exposing (getTooltipContent)
import VirtualDom

--
-- This file deals with one method container (condition, parameters, etc)
--

{-
  CONDITION
-}


-- /END VERSION in the condition part --


{-
  PARAMETERS
-}

getClassParameter: Method -> MethodParameter
getClassParameter method =
  case findClassParameter method of
    Just p -> p
    Nothing -> MethodParameter method.classParameter "" "" []

findClassParameter: Method -> Maybe MethodParameter
findClassParameter method =
  List.Extra.find (\p -> p.name == method.classParameter) method.parameters

parameterName: MethodParameter -> String
parameterName param =
  String.replace "_" " " (String.Extra.toSentenceCase param.name.value)

showParam: Model -> MethodCall -> ValidationState MethodCallParamError -> MethodParameter -> CallParameter -> Html Msg
showParam model call state methodParam param =
  let
    errors = case state of
      InvalidState constraintErrors -> List.filterMap (\c -> case c of
                                                         ConstraintError err ->
                                                           if (err.id == param.id) then
                                                             Just err.message
                                                           else
                                                             Nothing
                                                ) constraintErrors
      _ -> []
  in
  div [class "form-group method-parameter"] [
    label [ for "param-index" ] [
      span [] [
        text (String.Extra.toTitleCase param.id.value ++ " -")
      , span [ class "badge badge-secondary ng-binding" ] [ text methodParam.type_ ]
      ]
    , small [] [ text ( " " ++ methodParam.description) ]
    ]
  , textarea  [ stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)), onFocus DisableDragDrop,  readonly (not model.hasWriteRights),  name "param", class "form-control", rows  1 , value (displayValue param.value) , onInput  (MethodCallParameterModified call param.id)   ] [] --msd-elastic     ng-trim="{{trimParameter(parameterInfo)}}" ng-model="parameter.value"></textarea>
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

accumulateErrorConstraint: CallParameter -> List Constraint -> ValidationState MethodCallParamError -> ValidationState MethodCallParamError
accumulateErrorConstraint call constraints base =
  accumulateValidationState (List.map  (checkConstraint call) constraints ) base

checkConstraint: CallParameter -> Constraint -> ValidationState MethodCallParamError
checkConstraint call constraint =
  case constraint of
    AllowEmpty True -> ValidState
    AllowEmpty False -> if (isEmptyValue call.value) then InvalidState [ConstraintError { id = call.id, message = ("Parameter '"++call.id.value++"' is empty")}] else ValidState
    AllowWhiteSpace True -> ValidState
    AllowWhiteSpace False -> case Regex.fromString "(^\\s)|(\\s$)" of
                               Nothing -> ValidState
                               Just r -> if Regex.contains r (displayValue call.value) then InvalidState [ConstraintError { id = call.id, message = ( "Parameter '"++call.id.value++"' start or end with whitespace characters"  ) } ] else ValidState
    MaxLength max -> if lengthValue call.value >= max then  InvalidState [ConstraintError  { id = call.id, message = ("Parameter '"++call.id.value++"' should be at most " ++ (String.fromInt max) ++ " long" ) } ]else ValidState
    MinLength min -> if lengthValue call.value <= min then  InvalidState [ConstraintError { id = call.id, message = ("Parameter '"++call.id.value++"' should be at least " ++ (String.fromInt min) ++ " long") } ] else ValidState
    MatchRegex r -> case Regex.fromString r of
                      Nothing ->  ValidState
                      Just regex -> if Regex.contains regex (displayValue call.value) then
                                      ValidState
                                    else
                                       InvalidState [ConstraintError { id = call.id, message = ( "Parameter '" ++ call.id.value ++"' should match the following regexp: " ++ r  )} ]
    NotMatchRegex r -> case Regex.fromString r of
                      Nothing ->  ValidState
                      Just regex -> if Regex.contains regex (displayValue call.value) then
                                       InvalidState [ConstraintError { id = call.id, message = ("Parameter '" ++ call.id.value ++"' should not match the following regexp: " ++ r ) }]
                                    else
                                      ValidState
    Select list -> if List.any ( (==) (displayValue call.value) ) list then
                     ValidState
                   else
                     InvalidState [ConstraintError { id = call.id, message =  ( "Parameter '" ++ call.id.value ++ "'  should be one of the value from the following list: " ++ (String.join ", " list) )} ]


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
      div [ class "tab-parameters"] (List.map2 (\m c -> showParam model call uiInfo.validation m c )  method.parameters call.parameters)
    CallConditions ->
      let
        condition = call.condition
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
              label [ style "display" "inline-block",  class "", for "OsCondition"] [ text "Operating system: " ]
            , div [ style "display" "inline-block", style "width" "auto", style "margin-left" "5px",class "btn-group" ] [
                button [ class "btn btn-default dropdown-toggle", id ("OsCondition-" ++ call.id.value), onClick (ToggleDropdown  ("OsCondition-" ++ call.id.value)), stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))  ] [
                  text ((osName condition.os) ++ " ")
                , span [ class "caret" ] []
                ]
              , ul [ class "dropdown-menu", style "margin-left" "0px" ]
                 ( List.map (\os ->
                     let
                       updatedCondition = {condition | os = os }
                     in
                       li [ onClick (MethodCallModified (Call parentId {call | condition = updatedCondition })), class (osClass os) ] [ a [href "#" ] [ text (osName os) ] ] ) osList )
              ]
            , if (hasMajorMinorVersion condition.os ) then
                input [ readonly (not model.hasWriteRights)
                      , value (Maybe.withDefault "" (Maybe.map String.fromInt (getMajorVersion condition.os) ))
                      , onInput (updateConditonVersion updateMajorVersion)
                      , type_ "number", style "display" "inline-block", style "width" "auto", style "margin-left" "5px"
                      ,  class "form-control", placeholder "Major version"
                      , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                      ] []
              else text ""
            , if (hasMajorMinorVersion condition.os ) then
                input [ readonly (not model.hasWriteRights)
                      , value (Maybe.withDefault "" (Maybe.map String.fromInt (getMinorVersion condition.os) ))
                      , onInput (updateConditonVersion updateMinorVersion)
                      , type_ "number", style "display" "inline-block", style "width" "auto", class "form-control"
                      , style "margin-left" "5px", placeholder "Minor version"
                      , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                      ] []
              else text ""
            , if (hasVersion condition.os ) then
                input [ readonly (not model.hasWriteRights)
                      , value (Maybe.withDefault "" (Maybe.map String.fromInt (getVersion condition.os) ))
                      , onInput  (updateConditonVersion updateVersion)
                      , type_ "number", style "display" "inline-block", style "width" "auto"
                      , class "form-control", style "margin-left" "5px", placeholder "Version"
                      , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                      ] []
              else text ""
            , if (hasSP condition.os ) then
                input [ readonly (not model.hasWriteRights)
                      , value (Maybe.withDefault "" (Maybe.map String.fromInt (getSP condition.os) ))
                      , onInput (updateConditonVersion updateSP)
                      , type_ "number", style "display" "inline-block", style "width" "auto", class "form-control"
                      , style "margin-left" "5px", placeholder "Service pack"
                      , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                      ] []
              else text ""
            ]
          ]
        ]
      , div [ class "form-group condition-form" ] [
          label [ for "advanced"] [ text "Other conditions:" ]
        , textarea [  readonly (not model.hasWriteRights), stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)),  onFocus DisableDragDrop, name "advanced", class "form-control", rows 1, id "advanced", value condition.advanced, onInput (\s ->
                     let
                       updatedCondition = {condition | advanced = s }
                       updatedCall = Call parentId {call | condition = updatedCondition }
                     in MethodCallModified updatedCall)  ] []
       ]
      , div [ class "form-group condition-form" ] [
          label [ for "class_context" ] [ text "Applied condition expression:" ]
        , textarea [ readonly (not model.hasWriteRights), stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)), onFocus DisableDragDrop,  name "class_context",  class "form-control",  rows 1, id "advanced", value (conditionStr condition), readonly True ] []
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
            div [ class "input-group-addon" ] [
              text "Success"
            ]
          , input [ readonly True, type_ "text", class "form-control",  value (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_kept")
                   , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)) , stopPropagationOn "click" (Json.Decode.succeed (DisableDragDrop, True)) ] []
          , span [ class "input-group-btn" ] [
              button [ class "btn btn-outline-secondary clipboard", type_ "button", title "Copy to clipboard", onClick (Copy (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_kept")) ] [
                i [ class "ion ion-clipboard" ] []
              ]
            ]
          ]
        , div [ class "input-group result-repaired" ] [
            div [ class "input-group-addon" ] [
              text "Repaired"
            ]
          , input [ readonly True, type_ "text", class "form-control",  value (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_repaired")
                   , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)) , stopPropagationOn "click" (Json.Decode.succeed (DisableDragDrop, True)) ] []
          , span [ class "input-group-btn" ] [
              button [ class "btn btn-outline-secondary clipboard", type_ "button" , title "Copy to clipboard" , onClick (Copy (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_repaired")) ] [
                i [ class "ion ion-clipboard" ] []
              ]
            ]
          ]
        , div [ class "input-group result-error" ] [
            div [ class "input-group-addon" ] [
              text "Error"
            ]
          , input [ readonly True, type_ "text", class "form-control",  value (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_error")
                   , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)) , stopPropagationOn "click" (Json.Decode.succeed (DisableDragDrop, True)) ] []
          , span [ class "input-group-btn" ] [
              button [ class "btn btn-outline-secondary clipboard", type_ "button", title "Copy to clipboard", onClick (Copy (method.classPrefix ++ "_" ++ (canonify paramValue) ++ "_error")) ] [
                i [ class "ion ion-clipboard" ] []
              ]
            ]
          ]
        ]
      ]

methodDetail: Method -> MethodCall -> Maybe CallId -> MethodCallUiInfo -> Model -> Html Msg
methodDetail method call parentId ui model =
  let
    activeClass = (\c -> if c == ui.tab then "active" else "" )
  in
  div [ class "method-details" ] [
    div [] [
      ul [ class "tabs-list"] [
        li [ class (activeClass CallParameters),  stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallParameters}, True)) ] [text "Parameters"] -- click select param tabs, class active if selected
      , li [ class (activeClass CallConditions),stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallConditions}, True)) ] [text "Conditions"]
      , li [class (activeClass Result), stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = Result}, True))] [text "Result conditions"]
      , li [class (activeClass CallReporting), stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallReporting}, True)) ] [text "Reporting"]
      ]
    , div [ class "tabs" ] [ (showMethodTab model method parentId call ui) ]
    , div [ class "method-details-footer"] [
          button [ class "btn btn-outline-secondary btn-sm" , type_ "button", onClick (ResetMethodCall (Call parentId call))] [ -- ng-disabled="!canResetMethod(method_call)" ng-click="resetMethod(method_call)"
            text "Reset "
          , i [ class "fa fa-undo-all"] []
          ]
        , case method.documentation of
            Just _ ->
              let
                classes = "btn btn-sm btn-primary " ++
                          if List.member method.id model.methodsUI.docsOpen then "doc-opened" else ""
              in
                button [ class classes, type_ "button", onClick (ToggleDoc call.methodName) ] [
                  text "Show docs "
                , i [ class "fa fa-book"] []
                ]
            Nothing -> text ""
        ]
    ]
  ]


showMethodCall: Model -> MethodCallUiInfo -> TechniqueUiInfo -> Maybe CallId ->  MethodCall -> Element Msg
showMethodCall model ui tui parentId call =
  element "li"
  |> addClass (if (ui.mode == Opened) then "active" else "")
  |> appendChild (callBody model ui tui call parentId)
  |> addAttribute (hidden (Maybe.withDefault False (Maybe.map ((==) (Move (Call parentId  call))) (DragDrop.currentlyDraggedObject model.dnd) )))




callBody : Model -> MethodCallUiInfo -> TechniqueUiInfo ->  MethodCall -> Maybe CallId -> Element Msg
callBody model ui techniqueUi call pid =
  let
    method = case Dict.get call.methodName.value model.methods of
                   Just m -> m
                   Nothing -> Method call.methodName call.methodName.value "" "" (Maybe.withDefault (ParameterId "") (Maybe.map .id (List.head call.parameters))) [] [] Nothing Nothing Nothing

    deprecatedClass = "fa fa-info-circle method-action text-info popover-bs" ++
                         case method.deprecated of
                           Just _ -> " deprecated-icon"
                           Nothing -> ""
    classParameter = getClassParameter method
    paramValue = call.parameters |> List.Extra.find (\c -> c.id == classParameter.name) |> Maybe.map (.value)  |> Maybe.withDefault [Value ""]


    (textClass, tooltipContent) = case ui.validation of
                  InvalidState [_] -> ("text-danger", "A parameter of this method is invalid")
                  InvalidState err -> ("text-danger", (String.fromInt (List.length err)) ++ " parameters of this method are invalid")
                  Unchanged -> ("","")
                  ValidState -> ("text-primary","This method was modified")
    dragElem =  element "div"
                |> addClass "cursorMove"
                |> Dom.appendChild
                           ( element "i"
                             |> addClass "popover-bs fa"
                             |> addClassConditional "fa-cog" (ui.mode == Closed)
                             |> addClassConditional "fa-check" (ui.mode == Opened)
                             |> addClass textClass
                             |> addStyleConditional ("font-style", "20px") (ui.mode == Opened)
                             |> addAttributeList
                                  [ type_ "button", attribute "data-content" ((if (ui.mode == Opened) then "Close method details<br/>" else "") ++ tooltipContent) , attribute "data-toggle" "popover"
                                  , attribute "data-trigger" "hover", attribute "data-container" "body", attribute "data-placement" "auto"
                                  , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'"""
                                  ]
                           )
                |> addActionStopPropagation ("click",  UIMethodAction call.id {ui | mode = Closed})
    cloneIcon = element "i" |> addClass "fa fa-clone"
    cloneButton = element "button"
                  |> addClass "text-success method-action popover-bs"
                  |> addActionStopAndPrevent ("click", GenerateId (\s -> CloneElem (Call pid call) (CallId s)))
                  |> addAttributeList
                     [ type_ "button", attribute "data-content" "Clone this method", attribute "data-toggle" "popover"
                     , attribute "data-trigger" "hover", attribute "data-container" "body", attribute "data-placement" "auto"
                     , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'"""
                     ]
                  |> appendChild cloneIcon
    removeIcon = element "i" |> addClass "fa fa-times-circle"
    removeButton = element "button"
                  |> addClass "text-danger method-action popover-bs"
                  |> addActionStopAndPrevent ("click", RemoveMethod call.id)
                  |> addAttributeList
                     [ type_ "button", attribute "data-content" "Remove this method", attribute "data-toggle" "popover"
                       , attribute "data-trigger" "hover", attribute "data-container" "body", attribute "data-placement" "auto"
                     , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'"""
                     ]
                  |> appendChild removeIcon
    condition = element "div"
                |> addClass "method-condition flex-form"
                |> appendChildList
                   [ element "label"
                     |> appendText "Condition:"
                   , element "span"
                     |> appendText (conditionStr call.condition)
                     |> addActionStopPropagation ("mousedown" , DisableDragDrop)
                     |> addActionStopPropagation ("click" , DisableDragDrop)
                     |> addAttributeList
                        [ class "popover-bs", title (conditionStr call.condition)
                        , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-placement" "top"
                        , attribute "data-title" (conditionStr call.condition), attribute "data-content" "<small>Click <span class='text-info'>3</span> times to copy the whole condition below</small>"
                        , attribute "data-template" """<div class="popover condition" role="tooltip"><div class="arrow"></div><h3 class="popover-header"></h3><div class="popover-body"></div></div>"""
                        , attribute "data-html" "true"
                        ]
                  ]
    methodName = case ui.mode of
                   Opened -> element "div"
                             |> appendChild
                                ( element "div"
                                    |> addClass "component-name-wrapper"
                                    |> appendChildList
                                       [ element "div"
                                         |> addClass "title-input-name"
                                         |> appendText "Name"
                                       , element "div"
                                         |> addClass "gm-label-name"
                                         |> appendText method.name
                                       ]
                                    |> appendChild
                                       (element "input"
                                         |> addAttributeList [ readonly (not model.hasWriteRights), stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)), onFocus DisableDragDrop, type_ "text", name "component", style "width" "100%", class "form-control", value call.component,  placeholder "Enter a component name" ]
                                         |> addInputHandler  (\s -> MethodCallModified (Call pid {call  | component = s })))
                                )
                   Closed -> element "div"
                             |> addClass "method-name"
                             |> appendChild
                                ( element "span" |> appendText  (if (String.isEmpty call.component) then method.name else call.component )
                                  |> addActionStopPropagation ("mousedown" , DisableDragDrop)
                                  |> addActionStopPropagation ("click" , DisableDragDrop)
                                )
                             |> appendChildConditional
                                ( element "div"
                                  |> addClass "gm-label-name"
                                  |> appendText method.name
                                )
                                ((not (String.isEmpty call.component)) && call.component /= method.name )

    methodNameId = case ui.mode of
                     Opened -> element "span" |> appendText method.name
                                    |> addActionStopPropagation ("mousedown" , DisableDragDrop)
                                    |> addActionStopPropagation ("click" , DisableDragDrop)
                     Closed -> element ""
    methodContent = element "div"
                    |> addClass  "method-param flex-form"
                    |> appendChildList
                       [ element "label" |> appendText ((parameterName classParameter) ++ ": ")
                       , element "span"
                         |> appendText (displayValue paramValue)
                         |> addActionStopPropagation ("mousedown" , DisableDragDrop)
                         |> addActionStopPropagation ("click" , DisableDragDrop)
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
  |> addActionStopAndPrevent ( "dragend", MoveFirstElemBLock (Call pid call))
  |> Dom.appendChildList
     [ dragElem
     , element "div"
       |> addClass "method-info"
       |> addClassConditional ("closed") (ui.mode == Closed)
       |> addAction ("click",  UIMethodAction call.id {ui | mode = Opened})
       |> appendChildList
          [ element "div"
            |> addClass "btn-holder"
            |> addAttribute (hidden (not model.hasWriteRights))
            |> appendChildList
               [ cloneButton
               , element "span" |> appendText " "
               , removeButton
               , element "span" |> appendText " "
               , element "span" |> addAttributeList
                                   [ class deprecatedClass
                                   , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-container" "body"
                                   , attribute "data-placement" "auto", attribute "data-content" (getTooltipContent method)
                                   , attribute "data-html" "true"
                                   ]
               , element "span" |> appendText " "
               ]
          , element "div"
            |> addClass "flex-column"
            |> appendChildConditional condition (call.condition.os /= Nothing || call.condition.advanced /= "")
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
       {-, element "div"
         |> addAttributeList [ class "edit-method popover-bs", onClick editAction
                 , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-placement" "left"
                 --, attribute "data-template" "{{getStatusTooltipMessage(method_call)}}", attribute "data-container" "body"
                 , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'""" ]
         |> appendChild (element "i" |> addClass "ion ion-edit" ) -}
     ]