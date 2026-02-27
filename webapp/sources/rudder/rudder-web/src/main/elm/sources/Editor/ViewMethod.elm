module Editor.ViewMethod exposing (..)

import Dict exposing (Dict)
import Dict.Extra exposing (keepOnly)
import Set
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

import Editor.DataTypes exposing (..)
import Editor.MethodConditions exposing (..)
import Editor.MethodElemUtils exposing (..)
import Editor.AgentValueParser exposing (..)
import Editor.ViewMethodsList exposing (getTooltipContent)
import Editor.ViewTabForeach exposing (..)


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


showParam: Model -> MethodCall -> ValidationState MethodCallParamError -> MethodParameter -> List CallParameter -> Element Msg
showParam model call state methodParam params =
  let
    displayedValue = List.Extra.find (.id >> (==) methodParam.name ) params |> Maybe.map (.value >> displayValue) |> Maybe.withDefault ""
    isMandatory =
      if methodParam.constraints.allowEmpty |> Maybe.withDefault False then
        element "span" |> addClass "allow-empty"
      else
        element "span" |> addClass "mandatory-param" |> appendText " *"
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
    element "div"
      |> addClass "form-group method-parameter"
      |> appendChildList
        [ element "label"
          |> addAttribute (for "param-index")
          |> appendChildList
            [ element "span"
              |> appendChild (element "span" |> appendText (String.Extra.toTitleCase methodParam.name.value))
              |> appendChild isMandatory
              |> appendChild (element "span" |> appendText " -")
              |> appendChild (element "span" |> addClass "badge badge-secondary d-inline-flex align-items-center" |> appendText methodParam.type_)
            , element "small" |> appendText (" " ++ methodParam.description) |> addClass "ms-2"
            ]
        , element "textarea"
          |> addAttributeList
            [ stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
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
            ]
        ]
      |> appendChildConditional ( element "ul"
        |> addClass "list-unstyled"
        |> appendChildList (errors |> List.map (\e -> element "li" |> addClass "text-danger" |> appendText e))
      ) (not (List.isEmpty errors))

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

showMethodTab: Model -> Method -> Maybe CallId ->  MethodCall -> MethodCallUiInfo -> Element Msg
showMethodTab model method parentId call uiInfo =
  case uiInfo.tab of
    CallReporting ->
      element "div"
      |> addClass "tab-parameters"
      |> appendChild ( element "div"
        |> addClass "form-group"
        |> appendChildList
          [ element "label"
            |> addAttribute (for "disable_reporting")
            |> addClass "me-2"
            |> appendText "Disable reporting"
          , element "input"
            |> addAttributeList
              [ readonly (not model.hasWriteRights)
              , type_ "checkbox"
              , name "disable_reporting"
              , checked call.disableReporting
              , onCheck  (\b -> MethodCallModified (Call parentId {call  | disableReporting = b }) Nothing)
              ]
          ]
      )

    CallParameters ->
      element "div" |> addClass "tab-parameters"
       |> appendChildList (List.map (\m  -> showParam model call uiInfo.validation m call.parameters )  method.parameters )

    CallConditions ->
      let
        condition = call.condition

        errorOnConditionInput =
          element "ul"
            |> addClass "list-unstyled"
            |> appendChild ( element "li"
              |> addClass "text-danger"
              |> appendText "Return carriage is forbidden in condition"
            )

        ubuntuLi = List.map (\ubuntuMinor ->
          let
            updatedCall = Call parentId { call | condition = {condition | os =  updateUbuntuMinor  ubuntuMinor condition.os } }
          in
            element "li"
              |> addAction ("click", (MethodCallModified updatedCall Nothing))
              |> appendChild (element "a"
                |> addClass "dropdown-item"
                |> appendText (showUbuntuMinor ubuntuMinor)
              )
          ) [All, ZeroFour, Ten]

        updateConditonVersion = \f s ->
          let
            updatedCall = Call parentId { call | condition = {condition | os =  f  (String.toInt s) condition.os } }
          in
            MethodCallModified updatedCall Nothing
      in
      element "div"
      |> addClass "tab-conditions"
      |> appendChildList
        [ element "div"
          |> addClass "form-group condition-form"
          |> addAttribute (id "os-form")
          |> appendChild ( element "div"
            |> addClass "form-inline"
            |> appendChild ( element "div"
              |> addClass "form-group"
              |> appendChild ( element "label"
                |> addClass "d-inline-block"
                |> addAttribute (for ("OsCondition-" ++ call.id.value))
                |> appendText "Operating system: "
              )
              |> appendChild ( element "div"
                |> addClass "btn-group"
                |> appendChildList
                  [ element "button"
                    |> addClass "btn btn-default dropdown-toggle"
                    |> addAttributeList
                      [ id ("OsCondition-" ++ call.id.value)
                      , attribute "data-bs-toggle" "dropdown"
                      , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                      ]
                    |> appendChildList
                      [ element "span" |> appendText ((osName condition.os) ++ " ")
                      , element "span" |> addClass "caret"
                      ]
                  , element "ul"
                    |> addClass "dropdown-menu"
                    |> appendChildList (List.map (\os ->
                      let
                        updatedCondition = {condition | os = os }
                      in
                        element "li"
                        |> addAction ("click", (MethodCallModified (Call parentId {call | condition = updatedCondition }) Nothing))
                        |> addClass (osClass os)
                        |> appendChild ( element "a"
                          |> addClass "dropdown-item"
                          |> appendText (osName os)
                        )
                    ) osList )
                  ]
              )
            )
          )
          |> appendChildConditional ( element "input"
            |> addClass "form-control mt-0 ms-2 d-inline-block w-auto"
            |> addAttributeList
              [ readonly (not model.hasWriteRights)
              , value (Maybe.withDefault "" (Maybe.map String.fromInt (getMajorVersion condition.os) ))
              , onInput (updateConditonVersion updateMajorVersion)
              , type_ "number"
              , placeholder "Major version"
              , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
              ]
          ) (hasMajorMinorVersion condition.os || isUbuntu condition.os )

          |> appendChildConditional ( element "input"
            |> addClass "form-control mt-0 ms-2 d-inline-block w-auto"
            |> addAttributeList
              [ readonly (not model.hasWriteRights)
              , value (Maybe.withDefault "" (Maybe.map String.fromInt (getMinorVersion condition.os) ))
              , onInput (updateConditonVersion updateMinorVersion)
              , type_ "number"
              , placeholder "Minor version"
              , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
              ]
          ) (hasMajorMinorVersion condition.os )

          |> appendChildConditional (element "div"
            |> addClass "ms-2 btn-group"
            |> appendChildList
              [ element "button"
                |> addClass "btn btn-default dropdown-toggle"
                |> addAttributeList
                  [ id "ubuntuMinor" , attribute  "data-bs-toggle" "dropdown"
                  , attribute  "aria-haspopup" "true", attribute "aria-expanded" "true"
                  , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                  ]
                |> appendChild (element "span" |> appendText ((getUbuntuMinor condition.os) ++ " "))
                |> appendChild (element "span" |> addClass "caret")
              , element "ul"
                |> addClass "dropdown-menu"
                |> addAttribute (attribute "aria-labelledby" "ubuntuMinor")
                |> appendChildList ubuntuLi
              ]
          ) (isUbuntu condition.os)

          |> appendChildConditional (element "input"
            |> addClass "form-control ms-2 mt-0 d-inline-block w-auto"
            |> addAttributeList
            [ readonly (not model.hasWriteRights)
            , value (Maybe.withDefault "" (Maybe.map String.fromInt (getVersion condition.os) ))
            , onInput  (updateConditonVersion updateVersion)
            , type_ "number"
            , placeholder "Version"
            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
            ]
          ) (hasVersion condition.os)

          |> appendChildConditional ( element "input"
            |> addClass "form-control ms-2 mt-0 d-inline-block w-auto"
            |> addAttributeList
              [ readonly (not model.hasWriteRights)
              , value (Maybe.withDefault "" (Maybe.map String.fromInt (getSP condition.os) ))
              , onInput (updateConditonVersion updateSP)
              , type_ "number"
              , placeholder "Service pack"
              , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
              ]
          ) (hasSP condition.os)

        , element "div"
          |> addClass "form-group condition-form"
          |> appendChild (element "label" |> addAttribute (for "advanced") |> appendText "Other conditions:")
          |> appendChild ( element "textarea"
            |> addClass "form-control"
            |> addAttributeList
              [ readonly (not model.hasWriteRights)
              , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
              , onFocus DisableDragDrop
              , name "advanced"
              , rows 1
              , id "advanced"
              , value condition.advanced
              , attribute "onkeypress" "if (event.keyCode == 13) alert('You pressed the return button.'); return false;"
              , onInput (\s ->
                let
                  updatedCondition = {condition | advanced = s }
                  updatedCall = Call parentId {call | condition = updatedCondition }
                in MethodCallModified updatedCall Nothing
              )
              -- to deactivate plugin "Grammarly" or "Language Tool" from
              -- adding HTML that make disapear textarea (see  https://issues.rudder.io/issues/21172)
              , attribute "data-gramm" "false"
              , attribute "data-gramm_editor" "false"
              , attribute "data-enable-grammarly" "false"
              , spellcheck False
              ]
          )
          |> appendChildConditional errorOnConditionInput (String.contains "\n" call.condition.advanced)

        , element "div"
          |> addClass "form-group condition-form"
          |> appendChild ( element "label" |> addAttribute (for "class_context") |> appendText "Applied condition expression:" )
          |> appendChild ( element "textarea"
            |> addClass "form-control"
            |> addAttributeList
              [ readonly (not model.hasWriteRights)
              , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
              , onFocus DisableDragDrop
              , name "class_context"
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
              ]
          )

        , element "span"
          |> appendChildConditional (element "span"
            |> addClass "text-danger"
            |> appendText "Classes over 2048 characters are currently not supported."
          ) (String.length (conditionStr condition) > 2048)
        ]

    Result ->
      let
        classParameter = getClassParameter method
        paramValue = call.parameters |> List.Extra.find (\c -> c.id == classParameter.name) |> Maybe.map (.value) |> Maybe.withDefault [Value ""]
        fullParamValue = method.classPrefix ++ "_" ++ (canonify paramValue)
        conditions =
          if (String.startsWith "condition_from" method.classPrefix)
          then
          [ element "label"
            |> appendChild (element "small" |> appendText "Conditions defined by this method")
          , element "div"
            |> addClass "form-horizontal editForm result-class"
            |> appendChildList
              [ methodCondition {conditionName = "True", conditionValue = "true", paramValue = (canonify paramValue), class = "result-success"}
              , methodCondition {conditionName = "False", conditionValue = "false", paramValue = (canonify paramValue), class = "result-error"}]]
          else []
      in
        element "div"
          |> addClass "tab-result"
          |> appendChildList
            (conditions ++
            [ element "label"
              |> appendChild (element "small" |> appendText "Result conditions defined by this method")
            , element "div"
              |> addClass "form-horizontal editForm result-class"
              |> appendChildList
                [ methodCondition {conditionName = "Success", conditionValue = "kept", paramValue = fullParamValue, class = "result-success"}
                , methodCondition {conditionName = "Repaired", conditionValue = "repaired", paramValue = fullParamValue, class = "result-repaired"}
                , methodCondition {conditionName = "Error", conditionValue = "error", paramValue = fullParamValue, class = "result-error"}]
            ])

    CallForEach ->
      displayTabForeach (CallUiInfo uiInfo call)


methodCondition : { conditionName: String, conditionValue: String, paramValue: String, class: String } -> Element Msg
methodCondition { conditionName, conditionValue, paramValue, class } =
  element "div"
    |> addClass "input-group"
    |> addClass class
    |> appendChildList
      [ element "div"
        |> addClass "input-group-text"
        |> appendText conditionName
      , element "input"
        |> addClass "form-control"
        |> addAttributeList
          [ readonly True
          , type_ "text"
          , value (paramValue ++ "_" ++ conditionValue)
          , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
          , stopPropagationOn "click" (Json.Decode.succeed (DisableDragDrop, True))
          ]
      , element "button"
        |> addClass "btn btn-outline-secondary clipboard"
        |> addAttributeList
          [ type_ "button"
          , title "Copy to clipboard"
          ]
        |> addAction ("click", (Copy (paramValue ++ "_" ++ conditionValue)))
        |> appendChild (element "i" |> addClass "ion ion-clipboard")
     ]





methodDetail: Method -> MethodCall -> Maybe CallId -> MethodCallUiInfo -> Model -> Element Msg
methodDetail method call parentId ui model =
  let
    activeClass = (\c -> if c == ui.tab then "active" else "" )
    (nbForeach, foreachClass) = case call.foreach of
      Nothing ->
        ( "0"
        , ""
        )
      Just foreach ->
        ( String.fromInt (List.length foreach)
        , " has-foreach"
        )

  in
    element "div"
      |> addClass "method-details"
      |> appendChild ( element "div"
        |> appendChild ( element "ul"
          |> addClass "tabs-list mt-1 mb-3"
          |> appendChildList
            [ element "li"
              |> addClass (activeClass CallParameters)
              |> addAttribute (stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallParameters}, True)))
              |> appendText "Parameters"
            , element "li"
              |> addClass (activeClass CallConditions)
              |> addAttribute (stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallConditions}, True)))
              |> appendText "Conditions"
            , element "li"
              |> addClass (activeClass Result)
              |> addAttribute (stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = Result}, True)))
              |> appendText "Result conditions"
            , element "li"
              |> addClass (activeClass CallReporting)
              |> addAttribute (stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallReporting}, True)))
              |> appendText "Reporting"
            , element "li"
              |> addClass (activeClass CallForEach)
              |> addAttribute (stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | tab = CallForEach}, True)))
              |> appendChildList
                [ element "span" |> appendText "Foreach"
                , element "span"
                  |> addClass ("badge" ++ foreachClass)
                  |> appendChild (element "span" |> appendText nbForeach)
                  |> appendChild (element "i" |> addClass "fa fa-retweet ms-1")
                ]
            ]
        )
        |> appendChild ( element "div"
          |> addClass "tabs"
          |> appendChild (showMethodTab model method parentId call ui)
        )
      )

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
        " gm-label-unknown-name"
      else
        ""

    policyModeLabel =
        case call.policyMode of
          Nothing -> "gm-label-default"
          Just Audit -> "label-audit"
          Just Enforce -> "label-enforce"
          Just Default -> ""
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
                                |> addClass ("gm-label rudder-label gm-label-name" ++ methodNameLabelClass)
                                |> appendText method.name
                              )
                           |> foreachLabel call.foreachName call.foreach
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
                      |> addClass ("gm-labels policy-mode-label" ++ methodNameLabelClass)
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
                                               Just Default -> " "
                                             )
                             , element "ul"
                               |> addClass "dropdown-menu"
                               |> appendChildList [
                                  element "li"
                                   |> appendChild
                                      (element "a"
                                        |> addAction ("click",  MethodCallModified (Call pid {call  | policyMode = Nothing }) Nothing )
                                        |> addClass "dropdown-item"
                                        |> appendText "None"
                                      )
                                 , element "li"
                                   |> appendChild
                                      (element "a"
                                        |> addAction ("click",  MethodCallModified (Call pid {call  | policyMode = Just Audit }) Nothing )
                                        |> addClass "dropdown-item"
                                        |> appendText "Audit"
                                      )
                                 , element "li"
                                   |> appendChild
                                      (element "a"
                                        |> addAction ("click",  MethodCallModified (Call pid {call  | policyMode = Just Enforce }) Nothing )
                                        |> addClass "dropdown-item"
                                        |> appendText "Enforce"
                                      )
                                  ]
                               ])
      )

    methodName = case ui.mode of
      Opened ->
        element "div"
          |> addClass "method-name"
          |> appendChild
            ( element "div"
              |> addClass "component-name-wrapper"
              |> appendChild
                ( element "div"
                  |> addClass "form-group mb-0"
                  |> appendChild
                    ( element "div"
                      |> addClass "title-input-name"
                      |> appendText "Name"
                    )
                  |> appendChildConditional
                    ( element "div"
                      |> appendChildList
                        [ element "span"
                          |> appendTextConditional call.component (not (String.isEmpty call.component))
                          |> appendChildConditional ( element "span"
                            |> addClass "text-secondary"
                            |> appendText method.name
                          ) (String.isEmpty call.component)
                        , element "button"
                          |> addClass "btn btn-default ms-2"
                          |> addAttributeList
                            [ onFocus DisableDragDrop
                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                            , stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | editName = True}, True))
                            ]
                          |> appendChild (element "i" |> addClass "fa fa-pencil")

                        ]
                    )
                    (not ui.editName)
                  |> appendChildConditional
                    ( element "div"
                      |> addClass "d-flex"
                      |> appendChildList
                        [ element "input"
                          |> addAttributeList
                            [ readonly (not model.hasWriteRights)
                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                            , onFocus DisableDragDrop
                            , type_ "text"
                            , name "component"
                            , class "form-control"
                            , value call.component
                            , placeholder method.name
                            ]
                          |> addInputHandler  (\s -> MethodCallModified (Call pid {call  | component = s }) Nothing)
                        , element "button"
                          |> addClass "btn btn-default ms-2"
                          |> addAttributeList
                            [ onFocus DisableDragDrop
                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                            , stopPropagationOn "mousedown" (Json.Decode.succeed  (UIMethodAction call.id {ui | editName = False}, True))
                            ]
                          |> appendChild (element "i" |> addClass "fa fa-check")
                        ]
                    )
                    (ui.editName)
                )
            )
      Closed ->
        element "div"
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
            |> appendChild condition -- (call.condition.os /= Nothing || call.condition.advanced /= "")
            |> appendChild methodName
            --|> appendChild methodNameId
            |> appendChildConditional methodContent (ui.mode == Closed)
            |> appendChildConditional
                        ( element "div"
                          |> addClass "method-details"
                          |> appendChild (methodDetail method call pid ui model )
                          |> addAttribute (VirtualDom.property "draggable" (Json.Encode.bool (techniqueUi.enableDragDrop == Just call.id)))

                        ) (ui.mode == Opened)

         ]
    ]
