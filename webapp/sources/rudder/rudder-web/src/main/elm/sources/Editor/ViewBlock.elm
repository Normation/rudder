module Editor.ViewBlock exposing (..)

import Set
import Dict exposing (Dict)
import Dict.Extra exposing (keepOnly)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Json.Decode
import Dom.DragDrop as DragDrop
import Dom exposing (..)
import Maybe.Extra
import List.Extra

import Editor.DataTypes exposing (..)
import Editor.MethodConditions exposing (..)
import Editor.ViewMethod exposing (showMethodCall)
import Editor.MethodElemUtils exposing (..)
import Editor.ViewTabForeach exposing (foreachLabel, displayTabForeach)


appendNodeConditional : Html msg -> Bool -> Element msg -> Element msg
appendNodeConditional e test =
  case test of
    True -> appendNode e
    False -> (\x -> x)

showMethodBlock: Model -> TechniqueUiInfo ->  MethodBlockUiInfo -> Maybe CallId -> MethodBlock -> Element Msg
showMethodBlock model techniqueUi ui parentId block =
  let
    isHovered = case model.isMethodHovered of
                 Just methodId -> if ((methodId.value == block.id.value)) then "hovered" else ""
                 Nothing -> ""
  in
  element "li"
    |> addClass (if (ui.mode == Opened) then "active" else isHovered)
    |> addClass "card-method showMethodBlock"
    |> appendChild
       ( blockBody model parentId block ui techniqueUi )
    |> addAttribute (hidden (Maybe.withDefault False (Maybe.map ((==) (Move (Block parentId  block))) (DragDrop.currentlyDraggedObject model.dnd) )))
    |> addAction ("mouseover" , HoverMethod (Just block.id))
    |> addAction ("mouseleave" , HoverMethod Nothing)




blockDetail: MethodBlock -> Maybe CallId -> MethodBlockUiInfo -> TechniqueUiInfo -> Model -> Element Msg
blockDetail block parentId ui techniqueUi model =
  let
    activeClass = (\c -> if c == ui.tab then "active" else "" )
    (nbForeach, foreachClass) = case block.foreach of
          Nothing ->
            ( "0"
            , ""
            )
          Just foreach ->
            ( String.fromInt (List.length foreach)
            , " has-foreach"
            )
    tabsList =
      element "ul"
      |> addClass "tabs-list"
      |> appendChildList
          [ element "li"
            |> addClass (activeClass Children)
            |> addActionStopAndPrevent ("click", UIBlockAction block.id {ui | tab = Children})
            |> appendChildList [
                 element "span" |> appendText "Content"
               , element "span" |> addClass "badge badge-secondary badge-resources" |> appendChild(element "span" |> appendText (String.fromInt (List.length block.calls)))
               ]
          , element "li"
            |> addClass (activeClass BlockConditions)
            |> addActionStopAndPrevent ("click", UIBlockAction block.id {ui | tab = BlockConditions})
            |> appendText "Conditions"
          , element "li"
            |> addClass (activeClass BlockReporting)
            |> addActionStopAndPrevent ("click", UIBlockAction block.id {ui | tab = BlockReporting})
            |> appendText "Reporting"
          , element "li"
            |> addClass (activeClass BlockForEach)
            |> addActionStopAndPrevent ("click", UIBlockAction block.id {ui | tab = BlockForEach})
            |> appendChildList [
                 element "span" |> appendText "Foreach"
               , element "span" |> addClass ("badge" ++ foreachClass) |> appendChild(element "span" |> appendText nbForeach |> appendChild(element "i" |> addClass "fa fa-retweet ms-1" ))
               ]
          ]

  in
  element "div"
    |> addClass "method-details"
    |> appendChildList
       [ tabsList
       , element "div" |> addClass "tabs" |> appendChild (showBlockTab model parentId block ui techniqueUi)
       ]



showBlockTab: Model -> Maybe CallId ->  MethodBlock -> MethodBlockUiInfo -> TechniqueUiInfo -> Element Msg
showBlockTab model parentId block uiInfo techniqueUi =
  case uiInfo.tab of
    BlockConditions ->
      let
        osLi = List.map (\os ->
                 let
                   updatedCondition = {condition | os = os }
                 in
                   li [ onClick (MethodCallModified (Block parentId {block | condition = updatedCondition }) Nothing), class (osClass os) ] [ a [class "dropdown-item"] [ text (osName os) ] ]
                 )
               osList
        ubuntuLi = List.map (\ubuntuMinor ->
                     let
                       updatedCall = Block parentId { block | condition = {condition | os =  updateUbuntuMinor  ubuntuMinor condition.os } }
                     in
                       li [ onClick (MethodCallModified updatedCall Nothing) ] [ a [class "dropdown-item"] [ text (showUbuntuMinor ubuntuMinor) ] ]
                   ) [All, ZeroFour, Ten]
        condition = block.condition
        errorOnConditionInput =
          if(String.contains "\n" block.condition.advanced) then
            ul [ class "list-unstyled" ] [ li [ class "text-danger" ] [ text "Return carriage is forbidden in condition" ] ]
          else
            div[][]
        updateConditionVersion = \f s ->
                      let
                        updatedCall = Block parentId { block | condition = {condition | os =  f  (String.toInt s) condition.os } }
                      in
                        MethodCallModified updatedCall Nothing
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
                                      |> addClass "btn-group"
                                      |> appendChildList
                                         [ element "button"
                                           |> addClass "btn btn-default dropdown-toggle"
                                           |> addAttributeList
                                              [ id "OsCondition" , attribute  "data-bs-toggle" "dropdown"
                                              , attribute  "aria-haspopup" "true", attribute "aria-expanded" "true"
                                              , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                                              ]
                                           |> appendText  ((osName condition.os) ++ " ")
                                           |> appendChild  (element "span" |> addClass"caret")
                                         , element "ul"
                                           |> addClass "dropdown-menu"
                                           |> addAttribute (attribute "aria-labelledby" "OsCondition")
                                           |> appendNodeList osLi
                                         ]
                                   ]
                                 |> appendNodeConditional
                                    ( input [ readonly (not model.hasWriteRights)
                                            , value (Maybe.withDefault "" (Maybe.map String.fromInt (getMajorVersion condition.os) ))
                                            , onInput (updateConditionVersion updateMajorVersion)
                                            , type_ "number", style "display" "inline-block", style "width" "auto"
                                            , style "margin-left" "5px", style "margin-top" "0",  class "form-control", placeholder "Major version"
                                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                                            ] [] )
                                    ( hasMajorMinorVersion condition.os || isUbuntu condition.os )
                                 |> appendNodeConditional
                                    ( input [ readonly (not model.hasWriteRights)
                                            , value (Maybe.withDefault "" (Maybe.map String.fromInt (getMinorVersion condition.os) ))
                                            , onInput (updateConditionVersion updateMinorVersion), type_ "number"
                                            , style "display" "inline-block", style "width" "auto", class "form-control"
                                            , style "margin-left" "5px", style "margin-top" "0", placeholder "Minor version"
                                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                                            ] [] )
                                    ( hasMajorMinorVersion condition.os )
                                 |> appendChildConditional
                                    ( element "div"
                                        |> addStyle ("margin-left", "5px")
                                        |> addClass "btn-group"
                                        |> appendChildList
                                           [ element "button"
                                             |> addClass "btn btn-default dropdown-toggle"
                                             |> addAttributeList
                                                [ id "ubuntuMinor" , attribute  "data-bs-toggle" "dropdown"
                                                , attribute  "aria-haspopup" "true", attribute "aria-expanded" "true"
                                                , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                                                ]
                                             |> appendText  ((getUbuntuMinor condition.os) ++ " ")
                                             |> appendChild  (element "span" |> addClass"caret")
                                           , element "ul"
                                             |> addClass "dropdown-menu"
                                             |> addAttribute (attribute "aria-labelledby" "ubuntuMinor")
                                             |> appendNodeList ubuntuLi
                                           ]
                                    )

                                    ( isUbuntu condition.os )
                                 |> appendNodeConditional
                                    ( input [ readonly (not model.hasWriteRights)
                                            , value (Maybe.withDefault "" (Maybe.map String.fromInt (getVersion condition.os) ))
                                            , onInput  (updateConditionVersion updateVersion), type_ "number"
                                            , style "display" "inline-block", style "width" "auto", class "form-control"
                                            , style "margin-left" "5px", style "margin-top" "0", placeholder "Version"
                                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                                            ] [] )
                                    ( hasVersion condition.os )
                                 |> appendNodeConditional
                                    ( input [ readonly (not model.hasWriteRights)
                                            , value (Maybe.withDefault "" (Maybe.map String.fromInt (getSP condition.os) ))
                                            , onInput (updateConditionVersion updateSP), type_ "number"
                                            , style "display" "inline-block", style "width" "auto", class "form-control"
                                            , style "margin-left" "5px", style "margin-top" "0", placeholder "Service pack"
                                            , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                                            ] [] )
                                    ( hasSP condition.os )
                               )
                            )
        advanced = div [ class "form-group condition-form" ] [
                             label [ for "advanced"] [ text "Other conditions:" ]
                           , textarea [
                               readonly (not model.hasWriteRights), name "advanced", class "form-control", rows 1
                               , id "advanced", value condition.advanced,  onFocus DisableDragDrop
                               , stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                               , onInput ( \s ->
                                           let
                                             updatedCondition = {condition | advanced = s }
                                             updatedCall = Block parentId {block | condition = updatedCondition }
                                           in MethodCallModified updatedCall Nothing
                                         )
                             ] []
                          , errorOnConditionInput
                          ]
        result =
          div [ class "form-group condition-form" ] [
            label [ for "class_context" ] [ text "Applied condition expression:" ]
          , textarea [ readonly (not model.hasWriteRights),  name "class_context",  class "form-control",  rows 1
                     , id "advanced", value (conditionStr condition), readonly True,  onFocus DisableDragDrop
                     ,stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True))
                     ] []
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
    Children -> showChildren model block  { uiInfo | showChildDetails = True } techniqueUi parentId
    BlockReporting ->
      let
        -- main select
        compositionText  = (\reportingLogic ->
                               case reportingLogic of
                                 WorstReport _ -> "Worst report"
                                 WeightedReport -> "Weighted sum of reports"
                                 FocusReport _ -> "Focus on one child method report"
                             )
        liCompositionRule =  \rule -> element "li"
                                           |> addActionStopAndPrevent ("click", MethodCallModified (Block parentId {block | reportingLogic = rule }) Nothing)
                                           |> appendChild (element "a" |> addClass "dropdown-item" |> appendText (compositionText rule))
        availableComposition = List.map liCompositionRule [ WeightedReport, FocusReport "", WorstReport WorstReportWeightedSum ]

        -- sub-select - focus
        liFocus =  \child ->
                     let
                       componentValue = getComponent child
                       component = if componentValue == "" then
                                     case child of
                                       Block _ _ -> "< unnamed block > "
                                       Call _ c -> Maybe.withDefault (c.methodName.value) (Maybe.map .name (Dict.get c.methodName.value model.methods))
                                   else
                                     componentValue
                     in
                       element "li"
                               |> addActionStopAndPrevent ("click", MethodCallModified (Block parentId {block | reportingLogic = FocusReport (getId child).value }) Nothing)
                               |> appendChild (element "a" |> addClass "dropdown-item" |> appendText component)

        availableFocus = List.map liFocus block.calls

        -- sub-select - worst case
        labelWorst = \weight -> case weight of
                         WorstReportWeightedOne -> "Use a weight of '1' for component"
                         WorstReportWeightedSum -> "Use sum of sub-components for weight"
                         FocusWorst -> "Focus on the child with worst compliance"

        liWorst = \weight -> element "li"
                    |> addActionStopAndPrevent ("click", MethodCallModified (Block parentId {block | reportingLogic = (WorstReport weight) }) Nothing)
                    |> appendChild (element "a" |> addClass "dropdown-item" |> appendText (labelWorst weight))

        availableWorst = List.map liWorst [FocusWorst, WorstReportWeightedOne, WorstReportWeightedSum]
      in
        element "div"
          |> appendChildList
             [ buildSelectReporting "reporting-rule" "Reporting based on:" availableComposition ((compositionText block.reportingLogic) ++ " ")
             ]
          |> appendChild
            ( case block.reportingLogic of
              FocusReport value ->
                let
                  methodElem = findElemIf (\e -> (getId e).value == value) block.calls
                  componentValue =
                    case methodElem of
                      Just elem ->
                        let
                          name = getComponent elem
                        in
                        if(String.isEmpty name) then
                          case elem of
                            Block _ _ -> "< unnamed block > "
                            Call _ c -> Maybe.withDefault (c.methodName.value) (Maybe.map .name (Dict.get c.methodName.value model.methods))
                        else
                          name
                      Nothing -> ""
                in
                  buildSelectReporting "reporting-rule-subselect" "Focus reporting on method:" availableFocus componentValue
              (WorstReport weight) ->
                buildSelectReporting "reporting-rule-subselect" "Select weight of worst case:" availableWorst (labelWorst weight)
              _ -> element "span"
            )

    BlockForEach ->
        displayTabForeach (BlockUiInfo uiInfo block)

buildSelectReporting: String -> String -> (List (Element Msg)) -> String -> Element Msg
buildSelectReporting id label items value =
  element "div"
  |> addClass "form-group"
  |> appendChildList
     [ element "label"
       |> addAttribute (for id)
       |> appendText label
     , element "div"
       |> addStyleList [ ("display","inline-block") , ("width", "auto"), ("margin-left", "5px") ]
       |> addClass "btn-group"
       |> appendChildList
          [ element "button"
            |> addClass "btn btn-default dropdown-toggle"
            |> Dom.setId id
            |> addAttributeList
                 [ attribute  "data-bs-toggle" "dropdown"
                 , attribute  "aria-haspopup" "true"
                 , attribute "aria-expanded" "true"
                 ]
            |> appendText value
            |> appendChild (element "span" |> addClass "caret")
          , element "ul"
            |> addClass "dropdown-menu"
            |> addAttribute  (attribute "aria-labelledby" "reporting-rule-focus")
            |> addStyle ("margin-left", "0px")
            |> appendChildList items
           ]
       ]


blockBody : Model -> Maybe CallId -> MethodBlock -> MethodBlockUiInfo -> TechniqueUiInfo -> Element Msg
blockBody model parentId block ui techniqueUi =
  let
    (textClass, tooltipContent) = case ui.validation of
                  InvalidState _ -> ("text-danger", "<div class='tooltip-inner-content'>This block is invalid</div>")
                  Unchanged -> ("","")
                  ValidState -> ("text-primary","<div class='tooltip-inner-content'>This method was modified</div>")
    dragElem =  element "div"
                |> addClass "cursorMove"
                |> Dom.appendChild
                           ( element "i"
                             |> addClass "fa"
                             |> addClassConditional "fa-cubes" (ui.mode == Closed)
                             |> addClassConditional "fa-check" (ui.mode == Opened)
                             |> addClass textClass
                             |> addStyleConditional ("font-style", "20px") (ui.mode == Opened)
                             |> addAttributeList
                                [ type_ "button"
                                , attribute "data-bs-toggle" "tooltip"
                                , attribute "data-bs-placement" "top"
                                , attribute "data-bs-html" "true"
                                , title tooltipContent
                                ]
                           )
                |> addAction ("click",  UIBlockAction block.id {ui | mode = if(ui.mode == Opened) then Closed else Opened})

    cloneIcon = element "i" |> addClass "fa fa-clone"
    cloneButton = element "button"
                  |> addClass "text-success method-action"
                  |> addActionStopPropagation ("click", GenerateId (\s -> CloneElem (Block parentId block) (CallId s)))
                  |> addAttributeList
                     [ type_ "button"
                     , title "Clone this block"
                     ]
                  |> appendChild cloneIcon
    resetIcon = element "i" |> addClass "fa fa-rotate-right"
    resetButton = element "button"
                  |> addClass "method-action"
                  |> addActionStopPropagation ("click", ResetMethodCall (Block parentId block))
                  |> addAttributeList
                     [ type_ "button"
                     , title "Reset this block"
                     ]
                  |> appendChild resetIcon
    removeIcon = element "i" |> addClass "fa fa-times-circle"
    removeButton = element "button"
                  |> addClass "text-danger method-action"
                  |> addActionStopPropagation ("click", RemoveMethod block.id)
                  |> addAttributeList
                     [ type_ "button"
                     , title "Remove this block"
                     ]
                  |> appendChild removeIcon
    condition = element "div"
                |> addClass "method-condition flex-form"
                |> addClassConditional "d-none" (block.condition.os == Nothing && block.condition.advanced == "")
                |> appendChildList
                   [ element "label"
                     |> appendText "Condition:"
                   , element "span"
                     |> appendText (conditionStr block.condition)
                     |> addActionStopPropagation ("mousedown" ,DisableDragDrop )
                  ]
    policyModeLabel =
        case block.policyMode of
          Nothing -> "gm-label-default"
          Just Audit -> "label-audit"
          Just Enforce -> "label-enforce"



    appendLeftLabels = appendChild
                         ( element "div"
                           |> addClass ("gm-labels left")
                           |> appendChild
                              ( element "div"
                                |> addClass ("gm-label rudder-label gm-label-name ")
                                |> appendText "Block"
                              )
                           |> foreachLabel block.foreachName block.foreach
                         )
    appendRightLabels = appendChild
      ( case ui.mode of
          Closed -> element "div"
                      |> addClass ("gm-labels")
                      |> appendChildConditional
                        ( element "div"
                          |> addClass ("gm-label rudder-label " ++ policyModeLabel)
                        )
                        (Maybe.Extra.isJust block.policyMode)

          Opened -> element "div"
                      |> addClass ("gm-labels ")
                      |> appendChild
                          ( element "div" |> addClass "gm-label rudder-label gm-label-label" |> appendText "Policy mode override:")
                      |> appendChild
                         ( element "div"
                           |> addClass "btn-group"
                           |> appendChildList
                             [ element "button"
                               |> addClass ("btn dropdown-toggle rudder-label gm-label " ++ policyModeLabel)
                               |> addAttribute (attribute  "data-bs-toggle" "dropdown")
                               |> appendText (case block.policyMode of
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
                                        |> addAction ("click",  MethodCallModified (Block parentId {block | policyMode = Nothing }) Nothing )
                                        |> addClass "dropdown-item"
                                        |> appendText "None"
                                      )
                                 , element "li"
                                   |> appendChild
                                      (element "a"
                                        |> addAction ("click",  MethodCallModified (Block parentId {block | policyMode = Just Audit }) Nothing )
                                        |> addClass "dropdown-item"
                                        |> appendText "Audit"
                                      )
                                 , element "li"
                                   |> appendChild
                                      (element "a"
                                        |> addAction ("click",  MethodCallModified (Block parentId {block | policyMode = Just Enforce }) Nothing )
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
                                             |> addAttributeList [ readonly (not model.hasWriteRights), stopPropagationOn "mousedown" (Json.Decode.succeed (DisableDragDrop, True)), onFocus DisableDragDrop, type_ "text", name "component", style "width" "100%", class "form-control", value block.component,  placeholder "A friendly name for this component" ]
                                             |> addInputHandler  (\s -> MethodCallModified (Block parentId {block  | component = s }) Nothing)
                                           ]
                                       )
                                )
                   Closed -> element "div"
                             |> addClass "method-name"
                             |> addStyleListConditional [ ("font-style", "italic"), ("opacity", "0.7") ]  (String.isEmpty block.component)
                             |> addClassConditional "text-danger"  (String.isEmpty block.component)
                             |> appendChild
                                ( element "span" |> appendText  (if (String.isEmpty block.component) then "No component name" else block.component)
                                  |> addClass "name-content"
                                  |> addActionStopPropagation ("mousedown" , DisableDragDrop)
                                  |> addActionStopPropagation ("click" , DisableDragDrop)
                                  |> addActionStopPropagation ("mouseover" , HoverMethod Nothing)
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
  |> addActionStopPropagation ("mousedown", EnableDragDrop block.id)
  |> (if (techniqueUi.enableDragDrop == Just block.id) then DragDrop.makeDraggable model.dnd (Move (Block parentId block)) dragDropMessages else identity)
  |> addActionStopAndPrevent ( "dragend", CompleteMove)
  |> Dom.appendChildList
     [ dragElem
     , element "div"
       |> addClass ("method-info" ++ if (block.condition.os /= Nothing || block.condition.advanced /= "") then " condition" else "")
       |> addClassConditional ("closed") (ui.mode == Closed)
       |> addAction ("click", UIBlockAction block.id {ui | mode = Opened})
       |> appendLeftLabels
       |> appendRightLabels
       |> appendChildList
          [ element "div"
            |> addClass "btn-holder"
            |> addAttribute (hidden (not model.hasWriteRights))
            |> appendChildList
               [ removeButton
               , resetButton
               , cloneButton
               ]
          , element "div"
            |> addClass "flex-column block-name-container"
            |> appendChild condition -- (block.condition.os /= Nothing || block.condition.advanced /= "")
            |> appendChild methodName
         ]
       |> appendChildConditional
         (blockDetail block parentId ui techniqueUi model )
             (ui.mode == Opened)

       |>appendChildConditional (showChildren model block ui techniqueUi parentId)
             (ui.mode == Closed)

     ]

showChildren : Model -> MethodBlock -> MethodBlockUiInfo -> TechniqueUiInfo -> Maybe CallId ->  Element Msg
showChildren model block ui techniqueUi parentId =
  element "div"
  |> addClass "block-child"
  |> appendChild (
     element "ul"
     |> addClass "methods list-unstyled"
     |> appendChildConditional
        ( element "li"
          |> addStyle ("margin-bottom", "0")
          |> appendChild
            ( element "button"
              |> addClass "btn btn-default"
              |> appendChild (element "span" |> appendText ((if ui.showChildDetails then "Hide" else "Show") ++ " content"))
              |> appendChild (
                 element "span"
                  |> addClass "badge badge-secondary badge-block"
                  |> addStyle ("font-size", "10px")
                  |> appendChild (element "span" |> appendText (String.fromInt (List.length block.calls ) ) )
               )
              |> appendChild (element "span" |> appendText " ")
              |> appendChild (element "span" |> addClass  ("expandBlockChild fas fa-chevron-" ++ (if ui.showChildDetails then "up" else "down")))
              |> addActionStopAndPrevent ("click", UIBlockAction block.id { ui | showChildDetails = not ui.showChildDetails})
            )
        ) (ui.mode == Closed && (not (List.isEmpty block.calls) ))
     |> appendChildConditional
        ( element "li"
          |> addAttribute (class "no-methods")
          |> appendChildList
             [ element "i"
               |> addClass "fas fa-sign-in-alt"
               |> addStyle ("transform", "rotate(90deg)")
             , element "span"
               |> appendText " Drag and drop generic methods here to fill this component"
             ]

          |> DragDrop.makeDroppable model.dnd (InBlock block) dragDropMessages
          |> addClass (if (DragDrop.isCurrentDropTarget model.dnd (InBlock block)) then " drop-target" else  "")
        ) (List.isEmpty block.calls)
     |> appendChildConditional
        ( element "li"
          |> addClass"no-methods drop-zone"
          |> addStyle ("text-align", "center")
          |> addClassConditional "drop-target" (DragDrop.isCurrentDropTarget model.dnd (InBlock block))
          |> appendChild
             ( element "i"
               |> addClass "fas fa-sign-in-alt"
               |> addStyle ("transform", "rotate(90deg)")
             )
          |> DragDrop.makeDroppable model.dnd (InBlock block) dragDropMessages
        ) ( case (List.isEmpty block.calls , DragDrop.currentlyDraggedObject model.dnd) of
            ( True , _    ) -> False
            ( _ , Nothing ) -> False
            ( _ , Just (Move x) ) -> Maybe.withDefault True (Maybe.map (\c->  (getId x) /= (getId c)) (List.head block.calls))
            ( _ , Just _        ) -> not (List.isEmpty block.calls)
        )
     |> appendChildList
          ( List.concatMap ( \ call ->
            (case call of
              Call _ c ->
                let
                  methodUi = Maybe.withDefault (MethodCallUiInfo Closed CallParameters Unchanged (ForeachUI False False (defaultNewForeach c.foreachName c.foreach))) (Dict.get c.id.value techniqueUi.callsUI)
                  currentDragChild = case DragDrop.currentlyDraggedObject model.dnd of
                    Just (Move x) -> getId x == c.id
                    Nothing -> True
                    _ -> False
                  base =     [ showMethodCall model methodUi techniqueUi (Just block.id) c ]
                  dropElem = AfterElem (Just block.id) (Call parentId c)
                  dropTarget =  element "li"
                                |> addClass "no-methods drop-zone"
                                |> addStyle ("text-align", "center")
                                |> addClassConditional "drop-target" (DragDrop.isCurrentDropTarget model.dnd dropElem)
                                |> DragDrop.makeDroppable model.dnd dropElem dragDropMessages
                                |> addAttribute (hidden currentDragChild)
                in
                   List.reverse (dropTarget :: base)
              Block _ b ->
                let
                  methodUi = Maybe.withDefault (MethodBlockUiInfo Closed Children ValidState True (ForeachUI False False (defaultNewForeach b.foreachName b.foreach))) (Dict.get b.id.value techniqueUi.blockUI)
                in
                  [ showMethodBlock model techniqueUi methodUi (Just block.id) b ]
            )
            |> List.map ( addClass (if ui.showChildDetails then "show-method" else "hide-method"))
           ) block.calls )
     )

