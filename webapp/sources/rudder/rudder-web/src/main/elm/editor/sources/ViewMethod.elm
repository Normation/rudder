module ViewMethod exposing (..)

import DataTypes exposing (..)
import Dict
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import List.Extra
import MethodConditions exposing (..)
import Regex
import String.Extra
import MethodElemUtils exposing (..)
import Dom.DragDrop as DragDrop
import Dom exposing (..)
import Json.Decode
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
      InvalidState (ConstraintError err) -> err
      _ -> []
  in
  div [class "form-group method-parameter"] [
    label [ for "param-index" ] [
      span [] [
        text (param.id.value ++ " - ")
      , span [ class "badge badge-secondary ng-binding" ] [ text methodParam.type_ ]
      ]
    , small [] [ text methodParam.description ]
    ]
  , textarea  [  readonly (not model.hasWriteRights),  name "param", class "form-control", rows  1 , value param.value , onInput  (MethodCallParameterModified call param.id)   ] [] --msd-elastic     ng-trim="{{trimParameter(parameterInfo)}}" ng-model="parameter.value"></textarea>
  , ul [ class "list-unstyled" ]
      (List.map (\e -> li [ class "text-danger" ] [ text e ]) errors)
  ]

accumulateErrorConstraint: CallParameter -> List Constraint -> ValidationState MethodCallParamError
accumulateErrorConstraint call constraints =
  List.foldl (\c acc -> case (acc,  checkConstraint call c) of
                          (InvalidState (ConstraintError errAcc),InvalidState (ConstraintError err) ) -> InvalidState (ConstraintError (List.concat [ err, errAcc ] ))
                          (InvalidState err, _) -> InvalidState err
                          (_, InvalidState err) -> InvalidState err
                          _ -> ValidState
             ) Untouched constraints

checkConstraint: CallParameter -> Constraint -> ValidationState MethodCallParamError
checkConstraint call constraint =
  case constraint of
    AllowEmpty True -> ValidState
    AllowEmpty False -> if (String.isEmpty call.value) then InvalidState (ConstraintError ["Parameter '"++call.id.value++"' is empty"]) else ValidState
    AllowWhiteSpace True -> ValidState
    AllowWhiteSpace False -> case Regex.fromString "(^\\s)|(\\s$)" of
                               Nothing -> ValidState
                               Just r -> if Regex.contains r call.value then InvalidState (ConstraintError [ "Parameter '"++call.id.value++"' start or end with whitespace characters" ] ) else ValidState
    MaxLength max -> if String.length call.value >= max then  InvalidState (ConstraintError [ "Parameter '"++call.id.value++"' should be at most " ++ (String.fromInt max) ++ " long"] ) else ValidState
    MinLength min -> if String.length call.value <= min then  InvalidState (ConstraintError ["Parameter '"++call.id.value++"' should be at least " ++ (String.fromInt min) ++ " long"] ) else ValidState
    MatchRegex r -> case Regex.fromString r of
                      Nothing ->  ValidState
                      Just regex -> if Regex.contains regex call.value then
                                      ValidState
                                    else
                                       InvalidState (ConstraintError [ "Parameter '" ++ call.id.value ++"' should match the following regexp: " ++ r ] )
    NotMatchRegex r -> case Regex.fromString r of
                      Nothing ->  ValidState
                      Just regex -> if Regex.contains regex call.value then
                                       InvalidState (ConstraintError ["Parameter '" ++ call.id.value ++"' should not match the following regexp: " ++ r]  )
                                    else
                                      ValidState
    Select list -> if List.any ( (==) call.value ) list then
                     ValidState
                   else
                     InvalidState (ConstraintError [ "Parameter '" ++ call.id.value ++ "'  should be one of the value from the following list: " ++ (String.join ", " list)] )


{-
  DISPLAY ONE METHOD EXTENDED
-}



showMethodTab: Model -> Method -> Maybe CallId ->  MethodCall -> MethodCallUiInfo -> Html Msg
showMethodTab model method parentId call uiInfo=
  case uiInfo.tab of
    CallParameters ->
      div [ class "tab-parameters"] (List.map2 (\m c -> showParam model call (Maybe.withDefault Untouched (Dict.get c.id.value uiInfo.validation)) m c )  method.parameters call.parameters)
    Conditions ->
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
          div [ class "form-inline" ] [ -- form
            div [ class "form-group" ] [
              label [ style "display" "inline-block",  class "", for "OsCondition"] [ text "Operating system: " ]
            , div [ style "display" "inline-block", style "width" "auto", style "margin-left" "5px",class "btn-group"] [
                button [ class "btn btn-default dropdown-toggle", id "OsCondition", attribute  "data-toggle" "dropdown", attribute  "aria-haspopup" "true", attribute "aria-expanded" "true" ] [
                  text ((osName condition.os) ++ " ")
                , span [ class "caret" ] []
                ]
              , ul [ class "dropdown-menu", attribute "aria-labelledby" "OsCondition", style "margin-left" "0px" ]
                 ( List.map (\os ->
                     let
                       updatedCondition = {condition | os = os }
                     in
                       li [ onClick (MethodCallModified (Call parentId {call | condition = updatedCondition })), class (osClass os) ] [ a [href "#" ] [ text (osName os) ] ] ) osList )
              ]
            , if (hasMajorMinorVersion condition.os ) then input [readonly model.hasWriteRights,value (Maybe.withDefault "" (Maybe.map String.fromInt (getMajorVersion condition.os) )), onInput (updateConditonVersion updateMajorVersion),type_ "number", style "display" "inline-block", style "width" "auto", style "margin-left" "5px",  class "form-control", placeholder "Major version"] [] else text ""
            , if (hasMajorMinorVersion condition.os ) then input [readonly model.hasWriteRights, value (Maybe.withDefault "" (Maybe.map String.fromInt (getMinorVersion condition.os) )), onInput (updateConditonVersion updateMinorVersion), type_ "number", style "display" "inline-block", style "width" "auto", class "form-control", style "margin-left" "5px", placeholder "Minor version"] []  else text ""
            , if (hasVersion condition.os ) then input [readonly model.hasWriteRights, value (Maybe.withDefault "" (Maybe.map String.fromInt (getVersion condition.os) )), onInput  (updateConditonVersion updateVersion), type_ "number",style "display" "inline-block", style "width" "auto", class "form-control", style "margin-left" "5px", placeholder "Version"] []  else text ""
            , if (hasSP condition.os ) then input [readonly (not model.hasWriteRights), value (Maybe.withDefault "" (Maybe.map String.fromInt (getSP condition.os) )), onInput (updateConditonVersion updateSP), type_ "number", style "display" "inline-block", style "width" "auto", class "form-control", style "margin-left" "5px", placeholder "Service pack"] []  else text ""

            ]
          ]
          {-
                        <div class="tab-conditions" ng-if="ui.methodTabs[method_call['$$hashKey']]=='conditions'">
                          <div class="form-group condition-form" id="os-form">
                            <label for="os_class">Operating system:</label>
                            <form class="form-inline" role="form">
                            <form class="form-inline sm-space-top" name="CForm.versionForm" role="form">
                              <div class="form-group" ng-show="checkMajorVersion(method_call)">
                                <label for="os_class">Version (Major):</label>
                                <input type="text" ng-pattern="versionRegex" class="form-control" ng-change="updateClassContext(method_call)" ng-model="method_call.OS_class.majorVersion" name="versionMaj" placeholder="">
                              </div>
                              <div class="form-group" ng-show="checkMinorVersion(method_call)">
                                <label for="os_class">Version (Minor):</label>
                                <input type="text"  ng-pattern="versionRegex" class="form-control" ng-change="updateClassContext(method_call)" ng-disabled="method_call.OS_class.majorVersion === undefined || method_call.OS_class.majorVersion === '' " ng-model="method_call.OS_class.minorVersion"  placeholder="" name="versionMin">
                              </div>
                              <div ng-messages="CForm.versionForm.versionMaj.$error" class="sm-space-top" role="alert">
                                <div ng-message="pattern" class="text-danger">Invalid major version's number</div>
                              </div>
                              <div ng-messages="CForm.versionForm.versionMin.$error" role="alert">
                                <div ng-message="pattern" class="text-danger">Invalid minor version's number</div>
                              </div>
                            </form>
                          </div>
                          -}
        ]
      , div [ class "form-group condition-form" ] [
          label [ for "advanced"] [ text "Other conditions:" ]
        , textarea [  readonly (not model.hasWriteRights), name "advanced", class "form-control", rows 1, id "advanced", value condition.advanced, onInput (\s ->
                     let
                       updatedCondition = {condition | advanced = s }
                       updatedCall = Call parentId {call | condition = updatedCondition }
                     in MethodCallModified updatedCall)  ] [] --ng-pattern="/^[a-zA-Z0-9_!.|${}\[\]()@:]+$/" ng-model="method_call.advanced_class" ng-change="updateClassContext(method_call)"></textarea>
          {-<div ng-messages="CForm.form.cfClasses.$error" role="alert">
                                <div ng-message="pattern" class="text-danger">This field should only contains alphanumerical characters (a-zA-Z0-9) or the following characters _!.|${}[]()@:</div>
                              </div>
                            </div>-}
       ]
      , div [ class "form-group condition-form" ] [
          label [ for "class_context" ] [ text "Applied condition expression:" ]
        , textarea [ readonly (not model.hasWriteRights),  name "class_context",  class "form-control",  rows 1, id "advanced", value (conditionStr condition), readonly True ] []
        , if String.length (conditionStr condition) > 2048 then
            span [ class "text-danger" ] [text "Classes over 2048 characters are currently not supported." ]
          else
            text ""
        ]
      ]
    {-
                        <div class="tab-conditions" ng-if="ui.methodTabs[method_call['$$hashKey']]=='conditions'">
                          <div class="form-group condition-form" id="os-form">
                            <label for="os_class">Operating system:</label>
                            <form class="form-inline" role="form">
                            <form class="form-inline sm-space-top" name="CForm.versionForm" role="form">
                              <div class="form-group" ng-show="checkMajorVersion(method_call)">
                                <label for="os_class">Version (Major):</label>
                                <input type="text" ng-pattern="versionRegex" class="form-control" ng-change="updateClassContext(method_call)" ng-model="method_call.OS_class.majorVersion" name="versionMaj" placeholder="">
                              </div>
                              <div class="form-group" ng-show="checkMinorVersion(method_call)">
                                <label for="os_class">Version (Minor):</label>
                                <input type="text"  ng-pattern="versionRegex" class="form-control" ng-change="updateClassContext(method_call)" ng-disabled="method_call.OS_class.majorVersion === undefined || method_call.OS_class.majorVersion === '' " ng-model="method_call.OS_class.minorVersion"  placeholder="" name="versionMin">
                              </div>
                              <div ng-messages="CForm.versionForm.versionMaj.$error" class="sm-space-top" role="alert">
                                <div ng-message="pattern" class="text-danger">Invalid major version's number</div>
                              </div>
                              <div ng-messages="CForm.versionForm.versionMin.$error" role="alert">
                                <div ng-message="pattern" class="text-danger">Invalid minor version's number</div>
                              </div>
                            </form>
                          </div>


                          -}
    Result     ->
      let
        classParameter = getClassParameter method
        paramValue = call.parameters |> List.Extra.find (\c -> c.id == classParameter.name) |> Maybe.map (.value)  |> Maybe.withDefault ""
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
          , input [ readonly True, type_ "text", class "form-control",  value (classParameter.name.value ++ "_" ++ (canonify paramValue) ++ "_kept") ] []
          , span [ class "input-group-btn" ] [
              button [ class "btn btn-outline-secondary clipboard", type_ "button", title "Copy to clipboard" ] [ --data-clipboard-text="{{getClassKind(method_call,'kept')}}" title="Copy to clipboard">
                i [ class "ion ion-clipboard" ] []
              ]
            ]
          ]
        , div [ class "input-group result-repaired" ] [
            div [ class "input-group-addon" ] [
              text "Repaired"
            ]
          , input [ readonly True, type_ "text", class "form-control",  value (classParameter.name.value ++ "_" ++ (canonify paramValue) ++ "_repaired") ] []
          , span [ class "input-group-btn" ] [
              button [ class "btn btn-outline-secondary clipboard", type_ "button" , title "Copy to clipboard" ] [ --data-clipboard-text="{{getClassKind(method_call,'kept')}}" title="Copy to clipboard">
                i [ class "ion ion-clipboard" ] []
              ]
            ]
          ]
        , div [ class "input-group result-error" ] [
            div [ class "input-group-addon" ] [
              text "Error"
            ]
          , input [ readonly True, type_ "text", class "form-control",  value (classParameter.name.value ++ "_" ++ (canonify paramValue) ++ "_error") ] []
          , span [ class "input-group-btn" ] [
              button [ class "btn btn-outline-secondary clipboard", type_ "button", title "Copy to clipboard" ] [ --data-clipboard-text="{{getClassKind(method_call,'kept')}}" title="Copy to clipboard">
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
      div [ class "form-group"] [
        label [ for "component"] [ text "Report component:"]
      , input [ readonly (not model.hasWriteRights), type_ "text", name "component", class "form-control", value call.component,  placeholder method.name,  onInput  (\s -> MethodCallModified (Call parentId {call  | component = s }))] []
      ]
    , ul [ class "tabs-list"] [
        li [ class (activeClass CallParameters), onClick (SwitchTabMethod call.id CallParameters) ] [text "Parameters"] -- click select param tabs, class active if selected
      , li [ class (activeClass Conditions), onClick (SwitchTabMethod call.id Conditions) ] [text "Conditions"]
      , li [class (activeClass Result), onClick (SwitchTabMethod call.id Result) ] [text "Result conditions"]
      ]
    , div [ class "tabs" ] [ (showMethodTab model method parentId call ui) ]
    , div [ class "method-details-footer"] [
          button [ class "btn btn-outline-secondary btn-sm" , type_ "button", onClick (ResetMethodCall call)] [ -- ng-disabled="!canResetMethod(method_call)" ng-click="resetMethod(method_call)"
            text "Reset "
          , i [ class "fa fa-undo-all"] []
          ]
        , case method.documentation of
            Just _ ->
              let
                classes = "btn btn-sm btn-primary show-doc " ++
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


showMethodCall: Model -> MethodCallUiInfo -> Maybe CallId ->  MethodCall -> Element Msg
showMethodCall model ui  parentId call =
  let
    method = case Dict.get call.methodName.value model.methods of
               Just m -> m
               Nothing -> Method call.methodName call.methodName.value "" "" (Maybe.withDefault (ParameterId "") (Maybe.map .id (List.head call.parameters))) [] [] Nothing Nothing Nothing
  in
      element "li"
      |> addClass (if (ui.mode == Opened) then "active" else "") --     ng-class="{'active': methodIsSelected(method_call), 'missingParameters': checkMissingParameters(method_call.parameters, method.parameter).length > 0, 'errorParameters': checkErrorParameters(method_call.parameters).length > 0, 'is-edited' : canResetMethod(method_call)}"
      |> appendChild (callBody model ui call parentId)
      |> addAttribute (hidden (Maybe.withDefault False (Maybe.map ((==) (Move (Call parentId  call))) (DragDrop.currentlyDraggedObject model.dnd) )))
      |> appendChildConditional
         ( element "div"
           |> addClass "method-details"
           |> appendNode (methodDetail method call parentId ui model )
         ) (ui.mode == Opened)



callBody : Model -> MethodCallUiInfo ->  MethodCall -> Maybe CallId -> Element Msg
callBody model ui call pid =
  let
    method = case Dict.get call.methodName.value model.methods of
                   Just m -> m
                   Nothing -> Method call.methodName call.methodName.value "" "" (Maybe.withDefault (ParameterId "") (Maybe.map .id (List.head call.parameters))) [] [] Nothing Nothing Nothing

    deprecatedClass = "fa fa-info-circle tooltip-icon popover-bs" ++
                         case method.deprecated of
                           Just _ -> " deprecated-icon"
                           Nothing -> ""
    classParameter = getClassParameter method
    paramValue = call.parameters |> List.Extra.find (\c -> c.id == classParameter.name) |> Maybe.map (.value)  |> Maybe.withDefault ""

    editAction = case ui.mode of
                   Opened -> UIMethodAction call.id {ui | mode = Closed}
                   Closed -> UIMethodAction call.id {ui | mode = Opened}

    nbErrors = List.length (List.filter ( List.any ( (/=) Nothing) ) []) -- get errors
    dragElem =  element "div"
                |> addClass "cursorMove"
                |> Dom.appendChild
                           ( element "i"
                             |> addClass "fas fa-grip-horizontal"
                           )
    cloneIcon = element "i" |> addClass "fa fa-clone"
    cloneButton = element "button"
                  |> addClass "text-success method-action tooltip-bs"
                  |> addAction ("click", GenerateId (\s -> CloneMethod call (CallId s)))
                  |> addAttributeList
                     [ type_ "button", title "Clone this method", attribute "data-toggle" "tooltip"
                     , attribute "data-trigger" "hover", attribute "data-container" "body", attribute "data-placement" "left"
                     , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'"""
                     ]
                  |> appendChild cloneIcon
    removeIcon = element "i" |> addClass "fa fa-times-circle"
    removeButton = element "button"
                  |> addClass "text-danger method-action tooltip-bs"
                  |> addAction ("click", RemoveMethod call.id)
                  |> addAttribute (type_ "button")
                  |> appendChild removeIcon
    condition = element "div"
                |> addClass "method-condition flex-form"
                |> appendChildList
                   [ element "label"
                     |> appendText "Condition:"
                   , element "span"
                     |> appendText (conditionStr call.condition)
                     |> addAttributeList
                        [ class "popover-bs", title (conditionStr call.condition)
                        , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-placement" "top"
                        , attribute "data-title" (conditionStr call.condition), attribute "data-content" "<small>Click <span class='text-info'>3</span> times to copy the whole condition below</small>"
                        , attribute "data-template" """<div class="popover condition" role="tooltip"><div class="arrow"></div><h3 class="popover-header"></h3><div class="popover-body"></div></div>"""
                        , attribute "data-html" "true"
                        ]
                  ]
    methodName = element "div"
                 |> addClass "method-name"
                 |> appendText  (if (String.isEmpty call.component) then method.name else call.component)
                 |> appendChild
                    ( element "span"
                      |> appendChild
                         ( element "i"
                           |> addAttributeList
                              [ class deprecatedClass
                              , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-container" "body"
                              , attribute "data-placement" "auto", attribute "data-title" method.name, attribute "data-content" "{{getTooltipContent(method_call)}}"
                              , attribute "data-html" "true"
                              ]
                         )
                    )

    methodContent = element "div"
                    |> addClass  "method-param flex-form"
                    |> addActionStopAndPrevent ("ondragstart", Ignore)
                    |> addActionStopAndPrevent ("dragstart", Ignore)
                    |> addListenerStopAndPrevent ("dragStart", Json.Decode.succeed Ignore)
                    |> appendChildList
                       [ element "label" |> appendText ((parameterName classParameter) ++ ":")
                       , element "span"
                         |> appendText paramValue
                       ]

    warns = element "div"
            |> addClass "warns"
            |> appendChild
               ( element "span"
                 |> addClass  "warn-param error popover-bs"
                 |> appendChild (element "b" |> appendText (String.fromInt nbErrors)  )
                 |> appendText (" invalid " ++ (if nbErrors == 1 then "parameter" else "parameters") )
               )
    currentDrag = case DragDrop.currentlyDraggedObject model.dnd of
                    Just (Move x) -> getId x == call.id
                    Nothing -> False
                    _ -> False
  in
  element "div"
  |> addClass "method"
  |> addAttribute (id call.id.value)
  |> addAttribute (hidden currentDrag)
  |> DragDrop.makeDraggable model.dnd (Move (Call pid call)) dragDropMessages
  |> Dom.appendChildList
     [ dragElem
     , element "div"
       |> addClass "method-info"
       |> appendChildList
          [ element "div"
            |> addClass "btn-holder"
            |> addAttribute (hidden (not model.hasWriteRights))
            |> appendChildList
               [ cloneButton
               , removeButton
               ]
          , element "div"
            |> addClass "flex-column"
            |> appendChildConditional condition (call.condition.os /= Nothing || call.condition.advanced /= "")
            |> appendChildList
               [ methodName
               , methodContent
               ]
            |> appendChildConditional warns (nbErrors > 0)
        ]
       , element "div"
         |> addAttributeList [ class "edit-method popover-bs", onClick editAction
                 , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-placement" "left"
                 --, attribute "data-template" "{{getStatusTooltipMessage(method_call)}}", attribute "data-container" "body"
                 , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'""" ]
         |> appendChild (element "i" |> addClass "ion ion-edit" )

     ]

