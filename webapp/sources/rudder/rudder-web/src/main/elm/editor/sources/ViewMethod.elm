module ViewMethod exposing (..)

import DataTypes exposing (..)
import Dict
import DnDList.Groups
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import List.Extra
import MethodConditions exposing (..)
import Regex
import String.Extra

--
-- This file deals with one method container (condition, parameters, etc)
--

{-
  CONDITION
-}
noVersion = {major = Nothing, minor = Nothing }

osList : List (Maybe OS)
osList =
  [ Nothing
  , Just (Linux Nothing)
  , Just (Linux (Just (Debian noVersion)))
  , Just (Linux (Just (Ubuntu noVersion)))
  , Just (Linux (Just (RH noVersion)))
  , Just (Linux (Just (Centos noVersion)))
  , Just (Linux (Just (Fedora {version = Nothing})))
  , Just (Linux (Just (Oracle)))
  , Just (Linux (Just (Amazon)))
  , Just (Linux (Just (Suse)))
  , Just (Linux (Just (SLES {version = Nothing, sp = Nothing})))
  , Just (Linux (Just (SLED {version = Nothing, sp = Nothing})))
  , Just (Linux (Just (OpenSuse noVersion)))
  , Just (Linux (Just (Slackware noVersion)))
  , Just Windows
  , Just ( AIX {version = Nothing} )
  , Just ( Solaris noVersion)
  ]

-- VERSION in the condition part --
-- some OS only have one version (+maybe service packs). Deals with it here
hasVersion: Maybe OS -> Bool
hasVersion os =
  case os of
    Just (Linux (Just (Fedora _))) -> True
    Just (Linux (Just (SLES _))) -> True
    Just (Linux (Just (SLED _))) -> True
    Just (AIX _) -> True
    _ -> False

getVersion: Maybe OS -> Maybe Int
getVersion os =
  case os of
    Just (Linux (Just (Fedora v))) -> v.version
    Just (Linux (Just (SLES v))) -> v.version
    Just (Linux (Just (SLED v))) -> v.version
    Just (AIX v) -> v.version
    _ -> Nothing

updateVersion:  Maybe Int -> Maybe OS -> Maybe OS
updateVersion newVersion os =
  case os of
    Just (Linux (Just (SLES v))) -> Just (Linux (Just (SLES {v | version = newVersion})))
    Just (Linux (Just (SLED v))) -> Just (Linux (Just (SLED {v | version = newVersion})))
    Just (Linux (Just (Fedora v))) -> Just (Linux (Just (Fedora {v | version = newVersion})))
    Just (AIX v) -> Just (AIX {v | version = newVersion})
    _ -> os

-- for OS with service patcks
hasSP: Maybe OS -> Bool
hasSP os =
  case os of
    Just (Linux (Just (SLES _))) -> True
    Just (Linux (Just (SLED _))) -> True
    _ -> False

getSP: Maybe OS -> Maybe Int
getSP os =
  case os of
    Just (Linux (Just (SLES v))) -> v.sp
    Just (Linux (Just (SLED v))) -> v.sp
    _ -> Nothing

updateSP:   Maybe OS -> Maybe Int -> Maybe OS
updateSP os newSP =
  case os of
    Just (Linux (Just (SLES v))) -> Just (Linux (Just (SLES {v | sp = newSP})))
    Just (Linux (Just (SLED v))) -> Just (Linux (Just (SLED {v | sp = newSP})))
    _ -> os

-- most OS have a major+minor version
hasMajorMinorVersion: Maybe OS -> Bool
hasMajorMinorVersion os =
  case os of
    Just (Linux (Just (Debian _))) -> True
    Just (Linux (Just (Ubuntu _)))-> True
    Just (Linux (Just (RH _))) -> True
    Just (Linux (Just (Centos _))) -> True
    Just (Linux (Just (OpenSuse _))) -> True
    Just (Linux (Just (Slackware _))) -> True
    Just (Solaris _) -> True
    _ -> False


getMajorVersion:  Maybe OS -> Maybe Int
getMajorVersion os =
  case os of
    Just (Linux (Just (Debian v))) -> v.major
    Just (Linux (Just (Ubuntu v)))-> v.major
    Just (Linux (Just (RH v))) -> v.major
    Just (Linux (Just (Centos v))) -> v.major
    Just (Linux (Just (OpenSuse v))) -> v.major
    Just (Linux (Just (Slackware v))) -> v.major
    Just (Solaris v) -> v.major
    _ -> Nothing

getMinorVersion:  Maybe OS -> Maybe Int
getMinorVersion os =
  case os of
    Just (Linux (Just (Debian v))) -> v.minor
    Just (Linux (Just (Ubuntu v)))-> v.minor
    Just (Linux (Just (RH v))) -> v.minor
    Just (Linux (Just (Centos v))) -> v.minor
    Just (Linux (Just (OpenSuse v))) -> v.minor
    Just (Linux (Just (Slackware v))) -> v.minor
    Just (Solaris v) -> v.minor
    _ -> Nothing

updateMajorVersion: Maybe Int -> Maybe OS -> Maybe OS
updateMajorVersion newMajor os =
  case os of
    Just (Linux (Just (Debian v))) -> Just (Linux (Just (Debian {v | major = newMajor})))
    Just (Linux (Just (Ubuntu v)))-> Just (Linux (Just (Ubuntu {v | major = newMajor})))
    Just (Linux (Just (RH v))) -> Just (Linux (Just (RH {v | major = newMajor})))
    Just (Linux (Just (Centos v))) -> Just (Linux (Just (Centos {v | major = newMajor})))
    Just (Linux (Just (OpenSuse v))) -> Just (Linux (Just (OpenSuse {v | major = newMajor})))
    Just (Linux (Just (Slackware v))) -> Just (Linux (Just (Slackware {v | major = newMajor})))
    Just (Solaris v) -> Just ( Solaris { v | major = newMajor } )
    _ -> os

updateMinorVersion: Maybe Int -> Maybe OS -> Maybe OS
updateMinorVersion newMinor os =
  case os of
    Just (Linux (Just (Debian v))) -> Just (Linux (Just (Debian {v | minor = newMinor})))
    Just (Linux (Just (Ubuntu v)))-> Just (Linux (Just (Ubuntu {v | minor = newMinor})))
    Just (Linux (Just (RH v))) -> Just (Linux (Just (RH {v | minor = newMinor})))
    Just (Linux (Just (Centos v))) -> Just (Linux (Just (Centos {v | minor = newMinor})))
    Just (Linux (Just (OpenSuse v))) -> Just (Linux (Just (OpenSuse {v | minor = newMinor})))
    Just (Linux (Just (Slackware v))) -> Just (Linux (Just (Slackware {v | minor = newMinor})))
    Just (Solaris v) -> Just ( Solaris { v | minor = newMinor } )
    _ -> os

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

osClass: Maybe OS -> String
osClass maybeOs =
  case maybeOs of
    Nothing -> "optGroup"
    Just os ->
      case os of
        AIX _ -> "optGroup"
        Solaris _ -> "optGroup"
        Windows -> "optGroup"
        Linux Nothing -> "optGroup"
        Linux (Just _) -> "optChild"

showMethodTab: Model -> Method -> MethodCall -> MethodCallUiInfo -> Html Msg
showMethodTab model method call uiInfo=
  case uiInfo.tab of
    CallParameters ->
      div [ class "tab-parameters"] (List.map2 (\m c -> showParam model call (Maybe.withDefault Untouched (Dict.get c.id.value uiInfo.validation)) m c )  method.parameters call.parameters)
    Conditions ->
      let
        condition = call.condition
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
                 ( List.map (\os -> li [ onClick (UpdateCondition call.id {condition | os = os }), class (osClass os) ] [ a [href "#" ] [ text (osName os) ] ] ) osList )
              ]
            , if (hasMajorMinorVersion condition.os ) then input [readonly model.hasWriteRights,value (Maybe.withDefault "" (Maybe.map String.fromInt (getMajorVersion condition.os) )), onInput (\s -> UpdateCondition call.id {condition | os = updateMajorVersion  (String.toInt s) condition.os}  ),type_ "number", style "display" "inline-block", style "width" "auto", style "margin-left" "5px",  class "form-control", placeholder "Major version"] [] else text ""
            , if (hasMajorMinorVersion condition.os ) then input [readonly model.hasWriteRights, value (Maybe.withDefault "" (Maybe.map String.fromInt (getMinorVersion condition.os) )), onInput (\s -> UpdateCondition call.id {condition | os = updateMinorVersion  (String.toInt s) condition.os}  ), type_ "number", style "display" "inline-block", style "width" "auto", class "form-control", style "margin-left" "5px", placeholder "Minor version"] []  else text ""
            , if (hasVersion condition.os ) then input [readonly model.hasWriteRights, value (Maybe.withDefault "" (Maybe.map String.fromInt (getVersion condition.os) )), onInput (\s -> UpdateCondition call.id {condition | os = updateVersion  (String.toInt s) condition.os}  ), type_ "number",style "display" "inline-block", style "width" "auto", class "form-control", style "margin-left" "5px", placeholder "Version"] []  else text ""
            , if (hasSP condition.os ) then input [readonly (not model.hasWriteRights), value (Maybe.withDefault "" (Maybe.map String.fromInt (getSP condition.os) )), onInput (\s -> UpdateCondition call.id {condition | os = updateSP condition.os (String.toInt s)}  ), type_ "number", style "display" "inline-block", style "width" "auto", class "form-control", style "margin-left" "5px", placeholder "Service pack"] []  else text ""

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
        , textarea [  readonly (not model.hasWriteRights), name "advanced", class "form-control", rows 1, id "advanced", value condition.advanced, onInput (\s -> UpdateCondition call.id {condition | advanced = s })  ] [] --ng-pattern="/^[a-zA-Z0-9_!.|${}\[\]()@:]+$/" ng-model="method_call.advanced_class" ng-change="updateClassContext(method_call)"></textarea>
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

methodDetail: Method -> MethodCall -> MethodCallUiInfo -> Model -> Html Msg
methodDetail method call ui model =
  let
    activeClass = (\c -> if c == ui.tab then "active" else "" )
  in
  div [ class "method-details" ] [
    div [] [
      div [ class "form-group"] [
        label [ for "component"] [ text "Report component:"]
      , input [ readonly (not model.hasWriteRights), type_ "text", name "component", class "form-control", value call.component,  placeholder method.name] []
      ]
    , ul [ class "tabs-list"] [
        li [ class (activeClass CallParameters), onClick (SwitchTabMethod call.id CallParameters) ] [text "Parameters"] -- click select param tabs, class active if selected
      , li [ class (activeClass Conditions), onClick (SwitchTabMethod call.id Conditions) ] [text "Conditions"]
      , li [class (activeClass Result), onClick (SwitchTabMethod call.id Result) ] [text "Result conditions"]
      ]
    , div [ class "tabs" ] [ (showMethodTab model method call ui) ]
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

showMethodCall: Model -> MethodCallUiInfo -> DnDList.Groups.Model -> Int -> MethodCall -> Html Msg
showMethodCall model ui dnd index call =
  let
    method = case Dict.get call.methodName.value model.methods of
               Just m -> m
               Nothing -> Method call.methodName call.methodName.value "" "" (Maybe.withDefault (ParameterId "") (Maybe.map .id (List.head call.parameters))) [] [] Nothing Nothing Nothing
    dragAttributes =
       case dndSystem.info dnd of
         Just { dragIndex } ->
           if dragIndex /= index then
             dndSystem.dropEvents index call.id.value
           else
             [ ]
         Nothing ->
            dndSystem.dragEvents index call.id.value
  in
    if (List.isEmpty dragAttributes) then
      li [ class "dndPlaceholder"] [ ]
    else
      li [ class (if (ui.mode == Opened) then "active" else "") ] [ --     ng-class="{'active': methodIsSelected(method_call), 'missingParameters': checkMissingParameters(method_call.parameters, method.parameter).length > 0, 'errorParameters': checkErrorParameters(method_call.parameters).length > 0, 'is-edited' : canResetMethod(method_call)}"
        callBody model ui call dragAttributes False
      , case ui.mode of
         Opened -> div [ class "method-details" ] [ methodDetail method call ui model ]
         Closed -> div [] []
      ]

callBody : Model -> MethodCallUiInfo ->  MethodCall ->  List (Attribute Msg) -> Bool -> Html Msg
callBody model ui call dragAttributes isGhost =
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
                   Opened -> CloseMethod call.id
                   Closed -> OpenMethod  call.id

    nbErrors = List.length (List.filter ( List.any ( (/=) Nothing) ) []) -- get errors
  in
  div ( class "method" :: id call.id.value :: if isGhost then List.reverse ( style  "z-index" "1" :: style "pointer-events" "all" :: id "ghost" :: style "opacity" "0.7" :: style "background-color" "white" :: dndSystem.ghostStyles model.dnd) else []) [
    div  (class "cursorMove" :: dragAttributes) [ p [] [ text ":::"] ]
  , div [ class "method-info"] [
      div [ hidden (not model.hasWriteRights), class "btn-holder" ] [
        button [ class "text-success method-action tooltip-bs", onClick ( GenerateId (\s -> CloneMethod call (CallId s)) ), type_ "button"
               , title "Clone this method", attribute "data-toggle" "tooltip"
               , attribute "data-trigger" "hover", attribute "data-container" "body", attribute "data-placement" "left"
               , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'""" ] [
          i [ class "fa fa-clone"] []
        ]
      , button [  class "text-danger method-action", type_ "button", onClick (RemoveMethod call.id) ] [
          i [ class "fa fa-times-circle" ] []
        ]
      ]
    , div [ class "flex-column" ] [
        if (call.condition.os == Nothing && call.condition.advanced == "") then
          text ""
        else
          div [ class "method-condition flex-form" ] [
            label [] [ text "Condition:" ]
          , textarea [ class "form-control popover-bs", rows 1, readonly True, value (conditionStr call.condition), title (conditionStr call.condition)
                            --msd-elastic
                            --ng-click="$event.stopPropagation();"
                     , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-placement" "top"
                     , attribute "data-title" (conditionStr call.condition), attribute "data-content" "<small>Click <span class='text-info'>3</span> times to copy the whole condition below</small>"
                     , attribute "data-template" """<div class="popover condition" role="tooltip"><div class="arrow"></div><h3 class="popover-header"></h3><div class="popover-body"></div></div>"""
                     , attribute "data-html" "true" ] []
          ]
        , div [ class "method-name" ] [
            text (if (String.isEmpty call.component) then method.name else call.component)
          , span [ class "cursor-help" ] [
              i [ class deprecatedClass
                , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-container" "body"
                , attribute "data-placement" "auto", attribute "data-title" method.name, attribute "data-content" "{{getTooltipContent(method_call)}}"
                , attribute "data-html" "true" ]  []
            ]
          ]
        , div [ class "method-content"] [
            div [ class "method-param flex-form" ] [ --  ng-if="getClassParameter(method_call).value && checkMissingParameters(method_call.parameters, method.parameter).length<=0 && checkErrorParameters(method_call.parameters).length<=0"
              label [] [ text ((parameterName classParameter) ++ ":")]
            , textarea [ class "form-control", rows 1, readonly True, value paramValue ] [] -- msd elastic  ng-click="$event.stopPropagation();"
            ]
          ]
        , div [class "warns" ] [
             ( if nbErrors > 0 then
                 span [ class "warn-param error popover-bs", hidden (nbErrors == 0) ] [
                   b [] [ text (String.fromInt nbErrors) ]
                 , text (" invalid " ++ (if nbErrors == 1 then "parameter" else "parameters") )
                 ]
               else
                 text ""
             )
              {-
                                  ng-click="selectMethod(method_call)"
                                  data-toggle="popover"
                                  data-trigger="hover"
                                  data-container="body"
                                   data-placement="top"
                                  data-title="<b>{{checkErrorParameters(method_call.parameters).length}}</b> invalid parameter{{checkErrorParameters(method_call.parameters).length > 1 ? 's' : ''}}"
                                  data-content="{{getErrorTooltipMessage(checkErrorParameters(method_call.parameters))}}"
                                  data-html="true"
                                  >
                                  <b>{{checkErrorParameters(method_call.parameters).length}}</b> invalid parameter{{checkErrorParameters(method_call.parameters).length > 1 ? "s" : ""}}
                                </span>
                              </div> -}
            ]
              -- here used to be one entry for each for each param but we can be smart with elm

{- error display <div class="warns" ng-if="!getClassParameter(method_call).value || checkMissingParameters(method_call.parameters, method.parameter).length>0 || checkErrorParameters(method_call.parameters).length>0">
                                <span
                                  class="warn-param warning popover-bs"
                                  ng-click="selectMethod(method_call)"
                                  data-toggle="popover"
                                  data-trigger="hover"
                                  data-container="body"
                                  data-placement="top"
                                  data-title="<b>{{checkMissingParameters(method_call.parameters, method.parameter).length}}</b> required parameter{{checkMissingParameters(method_call.parameters, method.parameter).length > 1 ? 's' : ''}} missing"
                                  data-content="{{getWarningTooltipMessage(checkMissingParameters(method_call.parameters, method.parameter))}}"
                                  data-html="true"
                                  >
                                  <b>{{checkMissingParameters(method_call.parameters, method.parameter).length}}</b> required parameter{{checkMissingParameters(method_call.parameters, method.parameter).length > 1 ? 's' : ''}} missing
                                </span>
                                <span -}
        ]
      ]



  , div [ class "edit-method popover-bs", onClick editAction
          , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-placement" "left"
          --, attribute "data-template" "{{getStatusTooltipMessage(method_call)}}", attribute "data-container" "body"
          , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'""" ] [
      i [ class "ion ion-edit"] []
    ]
  ]

