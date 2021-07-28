module ViewBlock exposing (..)

import DataTypes exposing (..)
import Dict
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import List.Extra
import MethodConditions exposing (..)
import Dom.DragDrop as DragDrop
import Dom exposing (..)
import ViewMethod exposing (showMethodCall)
import MethodElemUtils exposing (..)

appendNodeConditional : Html msg -> Bool -> Element msg -> Element msg
appendNodeConditional e test =
  case test of
    True -> appendNode e
    False -> (\x -> x)

showMethodBlock: Model -> TechniqueUiInfo ->  MethodCallUiInfo -> Maybe CallId -> MethodBlock -> Element Msg
showMethodBlock model techniqueUi ui parentId block =

  element "li"
    |> appendChild  --     ng-class="{'active': methodIsSelected(method_call), 'missingParameters': checkMissingParameters(method_call.parameters, method.parameter).length > 0, 'errorParameters': checkErrorParameters(method_call.parameters).length > 0, 'is-edited' : canResetMethod(method_call)}"
       ( blockBody model parentId block ui techniqueUi )
    |> appendChildConditional
         (blockDetail block parentId ui model )
         (ui.mode == Opened)
    |> addAttribute (hidden (Maybe.withDefault False (Maybe.map ((==) (Move (Block parentId  block))) (DragDrop.currentlyDraggedObject model.dnd) )))



blockDetail: MethodBlock -> Maybe CallId -> MethodCallUiInfo -> Model -> Element Msg
blockDetail block parentId ui model =
  let
    activeClass = (\c -> if c == (Maybe.withDefault Reporting ui.tab) then "active" else "" )

    tabsList =
      element "ul"
      |> addClass "tabs-list"
      |> appendChildList
          [ element "li"
            |> addClass (activeClass Conditions)
            |> addActionStopAndPrevent ("click", SwitchTabMethod block.id Conditions)
            |> appendText "Conditions"
          , element "li"
            |> addClass (activeClass Reporting)
            |> addActionStopAndPrevent ("click", SwitchTabMethod block.id Reporting)
            |> appendText "Reporting"

          ]

  in
  element "div"
    |> addClass "method-details"
    |> appendChildList
       [ tabsList
       , element "div" |> addClass "tabs" |> appendChild (showBlockTab model parentId block ui)
       , element "div"
         |> addClass "method-details-footer"
         |> appendChild
            ( element "button"
              |> addClass "btn btn-outline-secondary btn-sm"
              |> appendText "Reset "
              |> appendChild (element "i" |> addClass "fa fa-undo-all" )
            )
       ]



showBlockTab: Model -> Maybe CallId ->  MethodBlock -> MethodCallUiInfo -> Element Msg
showBlockTab model parentId block uiInfo=
  case (Maybe.withDefault Reporting uiInfo.tab) of
    Conditions ->
      let
        osLi = List.map (\os ->
                 let
                   updatedCondition = {condition | os = os }
                 in
                   li [ onClick (MethodCallModified (Block parentId {block | condition = updatedCondition })), class (osClass os) ] [ a [href "#" ] [ text (osName os) ] ]
                 )
               osList
        condition = block.condition
        updateConditionVersion = \f s ->
                      let
                        updatedCall = Block parentId { block | condition = {condition | os =  f  (String.toInt s) condition.os } }
                      in
                        MethodCallModified updatedCall
        osConditions = element "div"
                       |> addClass "form-group condition-form"
                       |> addAttribute (id "os-form")
                       |> appendChild
                          ( element "div"
                            |> addClass "form-inline"
                            |> appendChild
                               ( element "div"
                                 |> addClass "form-group"
                                 |> appendChildList
                                    [ element "label"
                                      |> addStyle ("display", "inline-block")
                                      |> addAttribute (for "OsCondition")
                                      |> appendText "Operating system: "
                                    , element "div"
                                      |> addStyleList [ ("display", "inline-block"), ("width", "auto"), ("margin-left", "5px") ]
                                      |> addClass "btn-group"
                                      |> appendChildList
                                         [ element "button"
                                           |> addClass "btn btn-default dropdown-toggle"
                                           |> addAttributeList
                                              [ id "OsCondition" , attribute  "data-toggle" "dropdown"
                                              , attribute  "aria-haspopup" "true", attribute "aria-expanded" "true"
                                              ]
                                           |> appendText  ((osName condition.os) ++ " ")
                                           |> appendChild  (element "span" |> addClass"caret")
                                         , element "ul"
                                           |> addClass "dropdown-menu"
                                           |> addAttribute (attribute "aria-labelledby" "OsCondition")
                                           |> addStyle ("margin-left", "0px")
                                           |> appendNodeList osLi
                                         ]
                                   ]
                                 |> appendNodeConditional
                                    ( input [readonly (not model.hasWriteRights),value (Maybe.withDefault "" (Maybe.map String.fromInt (getMajorVersion condition.os) )), onInput (updateConditionVersion updateMajorVersion),type_ "number", style "display" "inline-block", style "width" "auto", style "margin-left" "5px",  class "form-control", placeholder "Major version"] [] )
                                    ( hasMajorMinorVersion condition.os)
                                 |> appendNodeConditional
                                    ( input [readonly (not model.hasWriteRights), value (Maybe.withDefault "" (Maybe.map String.fromInt (getMinorVersion condition.os) )), onInput (updateConditionVersion updateMinorVersion), type_ "number", style "display" "inline-block", style "width" "auto", class "form-control", style "margin-left" "5px", placeholder "Minor version"] [] )
                                    ( hasMajorMinorVersion condition.os )
                                 |> appendNodeConditional
                                    ( input [readonly (not model.hasWriteRights), value (Maybe.withDefault "" (Maybe.map String.fromInt (getVersion condition.os) )), onInput  (updateConditionVersion updateVersion), type_ "number",style "display" "inline-block", style "width" "auto", class "form-control", style "margin-left" "5px", placeholder "Version"] [] )
                                    ( hasVersion condition.os )
                                 |> appendNodeConditional
                                    ( input [readonly (not model.hasWriteRights), value (Maybe.withDefault "" (Maybe.map String.fromInt (getSP condition.os) )), onInput (updateConditionVersion updateSP), type_ "number", style "display" "inline-block", style "width" "auto", class "form-control", style "margin-left" "5px", placeholder "Service pack"] [] )
                                    ( hasSP condition.os )
                               )
                            )
        advanced = div [ class "form-group condition-form" ] [
                             label [ for "advanced"] [ text "Other conditions:" ]
                           , textarea [  readonly (not model.hasWriteRights), name "advanced", class "form-control", rows 1, id "advanced", value condition.advanced, onInput (\s ->
                                        let
                                          updatedCondition = {condition | advanced = s }
                                          updatedCall = Block parentId {block | condition = updatedCondition }
                                        in MethodCallModified updatedCall)  ] [] --ng-pattern="/^[a-zA-Z0-9_!.|${}\[\]()@:]+$/" ng-model="method_call.advanced_class" ng-change="updateClassContext(method_call)"></textarea>

                          ]
        result =
          div [ class "form-group condition-form" ] [
            label [ for "class_context" ] [ text "Applied condition expression:" ]
          , textarea [ readonly (not model.hasWriteRights),  name "class_context",  class "form-control",  rows 1, id "advanced", value (conditionStr condition), readonly True ] []
          , if String.length (conditionStr condition) > 2048 then
              span [ class "text-danger" ] [text "Classes over 2048 characters are currently not supported." ]
            else
              text ""
          ]

      in
        element "div"
          |> addClass "tab-conditions"
          |> appendChildList
             [ osConditions
             ]
          |> appendNodeList [
               advanced
             , result
             ]
    Result     -> element "span"
    CallParameters     -> element "span"
    Reporting ->
      let
        compositionText  = (\reportingLogic ->
                               case reportingLogic of
                                 WorstReport -> "Worst report"
                                 SumReport -> "Sum of reports"
                                 FocusReport "" -> "Focus on one child method report"
                                 FocusReport x -> "Focus on one child method: " ++ (focusText x)
                             )
        liCompositionRule =  \rule -> element "li"
                                           |> addActionStopAndPrevent ("click", MethodCallModified (Block parentId {block | reportingLogic = rule }))
                                           |> appendChild (element "a" |> addAttribute (href "#") |> appendText (compositionText rule))
        availableComposition = List.map liCompositionRule [ WorstReport, SumReport, FocusReport "" ]

        focusText  = (\reportingLogic ->
                         case reportingLogic of
                           "" -> ""
                           x -> Maybe.withDefault x (Maybe.map getComponent (List.Extra.find (getId >> .value >> (==) x) block.calls))
                       )
        liFocus =  \child -> element "li"
                               |> addActionStopAndPrevent ("click", MethodCallModified (Block parentId {block | reportingLogic = FocusReport (getId child).value }))
                               |> appendChild (element "a" |> addAttribute (href "#") |> appendText (getComponent child))
        availableFocus = List.map liFocus block.calls

      in
         element "div"
           |> appendChildList
                        [ element "div"
                          |> addClass "form-group"
                          |> appendChildList
                             [ element "label"
                               |> addAttribute (for "component")
                               |> appendText "Report component:"
                             , element "input"
                               |> addAttributeList [ readonly (not model.hasWriteRights), type_ "text", name "component", class "form-control", value block.component,  placeholder "Enter a component name" ]
                               |> addInputHandler  (\s -> MethodCallModified (Block parentId {block  | component = s }))
                             ]
                        , element "div"
                          |> addClass "form-group"
                          |> appendChildList
                             [ element "label"
                               |> addAttribute (for "reporting-rule")
                               |> appendText "Reporting based on:"
                             , element "div"
                               |> addStyleList [ ("display","inline-block") , ("width", "auto"), ("margin-left", "5px") ]
                               |> addClass "btn-group"
                               |> appendChildList
                                  [ element "button"
                                    |> addClass "btn btn-default dropdown-toggle"
                                    |> Dom.setId  "reporting-rule"
                                    |> addAttributeList
                                         [ attribute  "data-toggle" "dropdown"
                                         , attribute  "aria-haspopup" "true"
                                         , attribute "aria-expanded" "true"
                                         ]
                                    |> appendText ((compositionText block.reportingLogic) ++ " ")
                                    |> appendChild (element "span" |> addClass "caret")
                                  , element "ul"
                                    |> addClass "dropdown-menu"
                                    |> addAttribute  (attribute "aria-labelledby" "reporting-rule")
                                    |> addStyle ("margin-left", "0px")
                                    |> appendChildList availableComposition
                                   ]
                            ]
                        ]
                     |> appendChildConditional
                          ( element "div"
                          |> addClass "form-group"
                          |> appendChildList
                             [ element "label"
                               |> addAttribute (for "reporting-rule-focus")
                               |> appendText "Focus reporting on method:"
                             , element "div"
                               |> addStyleList [ ("display","inline-block") , ("width", "auto"), ("margin-left", "5px") ]
                               |> addClass "btn-group"
                               |> appendChildList
                                  [ element "button"
                                    |> addClass "btn btn-default dropdown-toggle"
                                    |> Dom.setId  "reporting-rule-focus"
                                    |> addAttributeList
                                         [ attribute  "data-toggle" "dropdown"
                                         , attribute  "aria-haspopup" "true"
                                         , attribute "aria-expanded" "true"
                                         ]
                                    |> appendText ((compositionText block.reportingLogic) ++ " ")
                                    |> appendChild (element "span" |> addClass "caret")
                                  , element "ul"
                                    |> addClass "dropdown-menu"
                                    |> addAttribute  (attribute "aria-labelledby" "reporting-rule-focus")
                                    |> addStyle ("margin-left", "0px")
                                    |> appendChildList availableFocus
                                   ]
                            ] )
                            ( case block.reportingLogic of
                                  FocusReport _ -> True
                                  _ -> False
                            )


blockBody : Model -> Maybe CallId -> MethodBlock -> MethodCallUiInfo -> TechniqueUiInfo -> Element Msg
blockBody model parentId block ui techniqueUi =
  let

    editAction = case ui.mode of
                   Opened -> UIMethodAction block.id {ui | mode = Closed}
                   Closed -> UIMethodAction block.id {ui | mode = Opened}

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
                  --|> addAction ("click", GenerateId (\s -> CloneMethod block (CallId s)))
                  |> addAttributeList
                     [ type_ "button", title "Clone this method", attribute "data-toggle" "tooltip"
                     , attribute "data-trigger" "hover", attribute "data-container" "body", attribute "data-placement" "left"
                     , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'"""
                     ]
                  |> appendChild cloneIcon
    removeIcon = element "i" |> addClass "fa fa-times-circle"
    removeButton = element "button"
                  |> addClass "text-danger method-action tooltip-bs"
                  |> addAction ("click", RemoveMethod block.id)
                  |> addAttribute (type_ "button")
                  |> appendChild removeIcon
    condition = element "div"
                |> addClass "method-condition flex-form"
                |> appendChildList
                   [ element "label"
                     |> appendText "Condition:"
                   , element "span"
                     |> appendText (conditionStr block.condition)
                     |> addAttributeList
                        [ class "popover-bs", title (conditionStr block.condition)
                            --msd-elastic
                            --ng-click="$event.stopPropagation();"
                        , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-placement" "top"
                        , attribute "data-title" (conditionStr block.condition), attribute "data-content" "<small>Click <span class='text-info'>3</span> times to copy the whole condition below</small>"
                        , attribute "data-template" """<div class="popover condition" role="tooltip"><div class="arrow"></div><h3 class="popover-header"></h3><div class="popover-body"></div></div>"""
                        , attribute "data-html" "true"
                        ]
                  ]
    methodName = element "div"
                 |> addClass "method-name"
                 |> addStyleListConditional [ ("font-style", "italic"), ("color", "#ccc") ]  (String.isEmpty block.component)
                 |> appendText  (if (String.isEmpty block.component) then "no component name" else block.component)


    warns = element "div"
            |> addClass "warns"
            |> appendChild
               ( element "span"
                 |> addClass  "warn-param error popover-bs"
                 |> appendChild (element "b" |> appendText (String.fromInt nbErrors)  )
                 |> appendText (" invalid " ++ (if nbErrors == 1 then "parameter" else "parameters") )
               )
    currentDrag = case DragDrop.currentlyDraggedObject model.dnd of
                    Just (Move x) -> getId x == block.id
                    Nothing -> False
                    _ -> False

  in
  element "div"
  |> addClass "method"
  |> addAttribute (id block.id.value)
  |> addAttribute (hidden currentDrag)
  |> DragDrop.makeDraggable model.dnd (Move (Block parentId block)) dragDropMessages
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
            |> appendChildConditional condition  (block.condition.os /= Nothing || block.condition.advanced /= "")
            |> appendChild methodName
            |> appendChildConditional warns (nbErrors > 0)

          , element "div"
             |> addClass ("expandBlockChild fas fa-chevron-" ++ (if ui.showChildDetails then "down" else "up"))
             |> addAction ("click", UIMethodAction block.id { ui | showChildDetails = not ui.showChildDetails})

          ,  ( element "div"
                    |> addClass "block-child"
                    |> addStyleListConditional [ ("opacity" ,"0"),  ("padding", "0"), ("height", "0"), ("border", "none")] (not ui.showChildDetails)

                    |> appendChild (
                       element "ul"
                       |> addClass "methods list-unstyled"
                       |> appendChild
                       ( element "li"
                         |> addAttribute (id "no-methods")
                         |> appendChildList
                            [ element "i"
                              |> addClass "fas fa-sign-in-alt"
                              |> addStyle ("transform", "rotate(90deg)")
                            , element "span"
                              |> appendText " Drag and drop generic methods here to fill this component"
                            ]
                         |> DragDrop.makeDroppable model.dnd (InBlock block) dragDropMessages
                         |> addStyle ("opacity", (if (DragDrop.isCurrentDropTarget model.dnd (InBlock block)) then "1" else  "0.4"))
                         |> addAttribute (hidden (not (List.isEmpty block.calls)))
                       )


                       |> appendChild
                            ( element "li"
                                         |> addAttribute (id "no-methods")
                                         |> addStyle ("text-align", "center")
                                         |> addStyle ("opacity", (if (DragDrop.isCurrentDropTarget model.dnd (InBlock block)) then "1" else  "0.4"))
                                         |> appendChild
                                            ( element "i"
                                              |> addClass "fas fa-sign-in-alt"
                                              |> addStyle ("transform", "rotate(90deg)")
                                            )
                                         |> addStyle ("padding", "3px 15px")
                                         |> DragDrop.makeDroppable model.dnd (InBlock block) dragDropMessages
                                         |> addAttribute (hidden  ( (case DragDrop.currentlyDraggedObject model.dnd of
                                                                               Nothing -> True
                                                                               Just (Move x) ->Maybe.withDefault True (Maybe.map (\c->  (getId x) /= (getId c)) (List.head block.calls))
                                                                               Just _ -> List.isEmpty block.calls
                                                         ) ) )
                                       )
                            |> appendChildList
                                       ( List.concatMap ( \ call ->
                                           case call of
                                             Call _ c ->
                                               let
                                                 methodUi = Maybe.withDefault (MethodCallUiInfo Closed Nothing Dict.empty True) (Dict.get c.id.value techniqueUi.callsUI)


                                                 currentDragChild = case DragDrop.currentlyDraggedObject model.dnd of
                                                   Just (Move x) -> getId x == c.id
                                                   Nothing -> True
                                                   _ -> False
                                                 base =     [ showMethodCall model methodUi parentId c ]
                                                 dropElem = AfterElem (Just block.id) (Call parentId c)
                                                 dropTarget =  element "li"
                                                               |> addAttribute (id "no-methods") |> addStyle ("padding", "3px 15px")
                                                               |> addStyle ("text-align", "center")
                                                               |> addStyle ("opacity", (if (DragDrop.isCurrentDropTarget model.dnd dropElem) then "1" else  "0.4"))
                                                               |> DragDrop.makeDroppable model.dnd dropElem dragDropMessages
                                                               |> addAttribute (hidden currentDragChild)
                                                               |> appendChild
                                                                  ( element "i"
                                                                    |> addClass "fas fa-sign-in-alt"
                                                                    |> addStyle ("transform", "rotate(90deg)")
                                                                  )
                                               in
                                                  List.reverse (dropTarget :: base)
                                             Block _ b ->
                                               let
                                                 methodUi = Maybe.withDefault (MethodCallUiInfo Closed Nothing Dict.empty True) (Dict.get b.id.value techniqueUi.callsUI)
                                               in
                                                 [ showMethodBlock model techniqueUi methodUi parentId b ]
                             ) block.calls ) ) )

        ]
       , element "div"
         |> addAttributeList [ class "edit-method popover-bs", onClick editAction
                 , attribute "data-toggle" "popover", attribute "data-trigger" "hover", attribute "data-placement" "left"
                 --, attribute "data-template" "{{getStatusTooltipMessage(method_call)}}", attribute "data-container" "body"
                 , attribute "data-html" "true", attribute "data-delay" """'{"show":"400", "hide":"100"}'""" ]
         |> appendChild (element "i" |> addClass "ion ion-edit" )

     ]
