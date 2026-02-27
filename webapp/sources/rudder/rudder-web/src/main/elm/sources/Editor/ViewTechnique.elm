module Editor.ViewTechnique exposing (..)

import Dict exposing (Dict)
import Http exposing (Metadata)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import Dom exposing (..)
import Dom.DragDrop as DragDrop

import Editor.ApiCalls exposing (..)
import Editor.DataTypes exposing (..)
import Editor.MethodElemUtils exposing (..)
import Editor.ViewBlock exposing (..)
import Editor.ViewMethod exposing (..)
import Editor.ViewMethodsList exposing (..)
import Editor.ViewTechniqueTabs exposing (..)
import Editor.ViewTechniqueList exposing (..)
import Maybe.Extra
import Json.Decode
import Regex


--
-- This file deals with the UI of one technique
--

checkTechniqueId: TechniqueState -> TechniqueCheckState -> List TechniqueCheckState -> ValidationState TechniqueIdError
checkTechniqueId origin technique techniques =
  case origin of
    Edit _ -> ValidState
    _ -> if (List.any (.id >> (==) technique.id) techniques) then
           InvalidState [ AlreadyTakenId ]
         else if String.length technique.id.value > 255 then
           InvalidState [ TooLongId ]
         else
           ValidState

checkTechniqueName : TechniqueCheckState -> List TechniqueCheckState -> ValidationState TechniqueNameError
checkTechniqueName technique techniques =
  if String.isEmpty technique.name then
   InvalidState [ EmptyName ]
  else
   if List.any (.name >> (==) technique.name) (List.filter (.id >> (/=) technique.id ) techniques) then
     InvalidState [ AlreadyTakenName ]
   else
     ValidState


checkTechniqueUiState : TechniqueState -> TechniqueCheckState -> List TechniqueCheckState -> TechniqueUiInfo -> TechniqueUiInfo
checkTechniqueUiState origin technique techniques ui =
  { ui | idState = checkTechniqueId origin technique techniques, nameState = checkTechniqueName technique techniques }

invalidTechniqueIdState : ValidationState TechniqueIdError -> (Bool, String)
invalidTechniqueIdState state =
  case state of
    InvalidState errors ->
      let
        invalidMsg = errors
          |> List.map (\e ->
            case e of
              TooLongId -> "Technique ID is too long"
              AlreadyTakenId -> "Technique ID is already taken"
          )
          |> String.join ".\n"
      in
        (True, invalidMsg)
    _ -> (False, "")

invalidTechniqueNameState : ValidationState TechniqueNameError -> (Bool, String)
invalidTechniqueNameState state =
  case state of
    InvalidState errors ->
      let
        invalidMsg = errors
          |> List.map (\e ->
            case e of
              EmptyName -> "Technique name is required"
              AlreadyTakenName -> "Technique name is already taken"
          )
          |> String.join ".\n"
      in
        (True, invalidMsg)
    _ -> (False, "")

checkParameter param = (not (Maybe.Extra.isNothing param.description && String.isEmpty param.name )) && (not (Regex.contains ((Regex.fromString >> Maybe.withDefault Regex.never) "[^_a-zA-Z\\d]") param.name))

checkInvalidStates: Technique -> TechniqueUiInfo -> List String
checkInvalidStates t ui =
  let
    idState = invalidTechniqueIdState ui.idState
    nameState = invalidTechniqueNameState ui.nameState
    parametersState =
      ( not (List.all checkParameter t.parameters)
      , "Invalid parameters state"
      )

    allStates = [idState, nameState, parametersState]
    invalidStates = allStates
      |> List.filterMap (\(b, c) -> if b then Just c else Nothing)
  in
    invalidStates

{- Contains methods with parameters error

   For example :
      In technique "foo" there is three methods
       * "command_execution" with id "e8a8d662-9d2d-44b4-87f8-591fb6f4db1b"
         - [Mandatory] with parameter "Command"      -> "touch file"
       * "package_present" with id "6394770e-a9c2-4e67-8569-d21560a07dc2
         - [Mandatory] with parameter "Name"         -> empty
         - [Optional] with parameter  "Version"      -> "latest"
         - [Optional] with parameter  "Architecture" -> empty
         - [Optional] with parameter  "Provider"     -> "unknown_provider"

   the result will be :
   Dict {
     "6394770e-a9c2-4e67-8569-d21560a07dc2" -> [
       InvalidState [ConstraintError { id = { value = "name" }, message = "Parameter 'name' is empty" }]
     , InvalidState [
         ConstraintError {
           id = { value = "provider" }
         , message = "Parameter 'provider'  should be one of the value from the following list: , default, yum, apt, zypper, zypper_pattern, slackpkg, pkg"
         }
       ]
     ]
   }
-}

listAllMethodWithErrorOnParameters: List MethodCall -> Dict String Method -> List String
listAllMethodWithErrorOnParameters methodCallList libMethods =
  let
    errorsOnParamByCallId =
      methodCallList
        |> List.map ( \mCall ->
          ( if String.isEmpty mCall.component then mCall.methodName.value else mCall.component
          , case (Dict.get mCall.methodName.value libMethods) of
            Just method ->
              let
                -- all the parameters errors found on a method
                paramErrors =
                  List.map (\param ->
                    let
                      paramConstraints = case (List.head (List.filter (\p -> param.id.value == p.name.value) method.parameters)) of
                        Just paramWithConstraints -> paramWithConstraints.constraints
                        _                         -> defaultConstraint
                      state = accumulateErrorConstraint param paramConstraints ValidState
                    in
                    state
                  ) mCall.parameters
              in
                paramErrors
                |> List.filterMap ( \state ->
                  case state of
                    InvalidState errors ->
                      let
                        invalidMsg = errors
                          |> List.map (\e ->
                            case e of
                              ConstraintError c -> c.message
                          )
                          |> String.join ".\n"
                      in
                       Just invalidMsg
                    _ -> Nothing
                  )
            _ -> []
          )
      )
    concatErrors =
      errorsOnParamByCallId
        |> List.filterMap ( \(methodName, errors) ->
          if List.isEmpty errors then
            Nothing
          else
            Just ("Invalid method '" ++ methodName ++ "': " ++ (String.join ".\n" errors))
        )
  in
    concatErrors

listAllMethodWithErrorOnCondition: List MethodCall -> Dict String Method -> Dict String (ValidationState MethodCallConditionError)
listAllMethodWithErrorOnCondition methodCallList libMethods =
  let
    errorsOnParamByCallId =
      List.map ( \mCall ->
        case (Dict.get mCall.methodName.value libMethods) of
          Just method ->
            let
              conditionErrors = checkConstraintOnCondition mCall.condition
            in
            ( mCall.id.value
            , conditionErrors
            )
          _      -> (mCall.methodName.value, ValidState)
      ) methodCallList
  in
  Dict.fromList (List.filter ( \(_, error) ->
    case error of
      InvalidState err -> True
      _ -> False
  ) errorsOnParamByCallId)

checkBlocksOnError: List MethodElem -> List String
checkBlocksOnError methodElems =
  let
    methodsBlocks = List.concatMap getAllBlocks methodElems
    methodBlockOnError = methodsBlocks
      |> List.map (\b -> checkBlockConstraint b)
  in
    methodBlockOnError
      |> List.filterMap ( \state ->
        case state of
          InvalidState errors ->
            let
              invalidMsg = errors
                |> List.map (\e ->
                  case e of
                    EmptyComponent -> "Block must have a name"
                    NoFocusError -> "A method must be selected if the reporting of the block is based on one child method report"
                    ConditionError -> "There are errors on block conditions"
                )
                |> String.join ".\n"
            in
              Just invalidMsg
          _ -> Nothing
      )

showTechniqueError : TechniqueError -> Html Msg
showTechniqueError error =
  div [class "jumbotron"]
      [ h1 [] [ i [ class "fa fa-warning" ] [], text "Technique file is invalid" ]
      , h2 [] [ text "At ", em [] [ text error.errorPath ] ]
      , p [] [ text "Error details :" ]
      , p [] [ pre [] [ text error.errorMsg ] ]
      ]

showTechnique : Model -> Technique ->  TechniqueState -> TechniqueUiInfo -> TechniqueEditInfo -> Html Msg
showTechnique model technique origin ui editInfo =
  let
    fakeMetadata = Http.Metadata "internal-elm-call" 200 "call from elm app" Dict.empty
    errorOnBlocks = checkBlocksOnError technique.elems
    activeTabClass = (\tab -> if ui.tab == tab then " active" else "")
    (creation, optDraftId) = case origin of
                 Creation id -> (True, Just id )
                 Clone _ _ id -> (True, Just id )
                 Edit _ -> (False, Nothing)
    methodCallList = List.concatMap getAllCalls technique.elems
    statesByMethodIdCondition = listAllMethodWithErrorOnCondition methodCallList model.methods
    -- Keep the ID of the method in the UI if it contains invalid parameters value
    errorsOnMethodParameters = listAllMethodWithErrorOnParameters methodCallList model.methods
    areErrorOnMethodCondition = List.isEmpty (Dict.keys statesByMethodIdCondition)
    isMethodListEmpty = List.isEmpty (technique.elems)
    areResourceUpdated = List.any (.state >> (/=) Untouched) technique.resources
    -- Check if enum type is chosen, then we should verify that the list on enum is not empty
    isEnumListIsEmpty =
      if (List.isEmpty technique.parameters) then
        False
      else
        let
          listEnumInputs =
            List.map (\parameter ->
              case parameter.constraints.select of
                Nothing -> False
                Just e  -> List.isEmpty e
            )
            technique.parameters
        in
          List.any identity listEnumInputs

    -- An Enum name cannot should not be empty
    isEnumWithEmptyName =
      if (List.isEmpty technique.parameters) then
        False
      else
        let
          -- Each enum's name
          listEnumName =
            List.concatMap (\parameter ->
                List.map (\enum -> Maybe.withDefault "" enum.name) (Maybe.withDefault [] parameter.constraints.select)
            ) technique.parameters
        in
          List.any String.isEmpty listEnumName

    -- Empty value are allowed only if "Required" option is not checked
    isEnumWithEmptyValue =
      if (List.isEmpty technique.parameters) then
        False
      else
        let
          -- List (required: Bool, values: List String)
          listEnumConstraintValues =
            List.map (\parameter ->
              ( (not parameter.mayBeEmpty)
              , List.map (\enum -> enum.value) (Maybe.withDefault [] parameter.constraints.select)
              )
            ) technique.parameters
        in
         List.any identity
         ( listEnumConstraintValues
             -- required = True  and emptiness = True       ==> ERROR
             -- required = True  and emptiness = False      ==> OK
             -- required = False and emptiness = True/False ==> OK
             |> List.map (\(required, values) -> required && List.any String.isEmpty values)
         )

    isUnchanged = (not areResourceUpdated) && case origin of
                    Edit t -> t == technique
                    Creation _ -> False
                    Clone t _ _ -> t == technique
    deleteAction = case origin of
                     Creation id -> DeleteTechnique (Ok (fakeMetadata, id))
                     Clone _ _ id -> DeleteTechnique (Ok (fakeMetadata, id))
                     Edit _ -> OpenDeletionPopup technique
    topButtons =  [ li [] [
                      a [ class "dropdown-item", disabled creation , onClick (GenerateId (\s -> CloneTechnique technique optDraftId (TechniqueId s))) ] [
                        text "Clone "
                      , i [ class "fa fa-clone"] []
                      ]
                    ]
                  , li [] [
                      a [ class "dropdown-item", onClick Export] [
                        text "Export "
                      , i [ class "fa fa-download"] []
                      ]
                    ]
                  , li [] [
                      a [ class "dropdown-item action-danger", onClick deleteAction ] [
                        text "Delete "
                      , i [ class "fa fa-times-circle"] []
                      ]
                    ]
                  ]
    title = if creation then
              [ i [] [ text "New Technique" ] ]
            else
              [ span [class "technique-version" ] [ text technique.version ] , text (" - " ++ technique.name) ]

    methodsList =
      element "ul"
      |> addAttributeList [ id "methods", class "list-unstyled" ]
      |> appendChild
           ( element "li"
             |> addAttribute (class "no-methods")
             |> addAction ("click", OpenMethods)
             |> appendChildList
                [ element "i"
                  |> addClass "fas fa-sign-in-alt me-1"
                  |> addStyle ("transform", "rotate(90deg)")
                , element "span"
                  |> appendText " Drag and drop generic methods here from the list on the right to build target configuration for this technique."
                ]
             |> DragDrop.makeDroppable model.dnd StartList dragDropMessages
             |> addAttribute (hidden (not (List.isEmpty technique.elems)))
           )
      |> appendChildConditional
           ( element "li"
             |> addClass "no-methods drop-zone"
             |> addStyle ("text-align", "center")
             |> addClassConditional "drop-target" (DragDrop.isCurrentDropTarget model.dnd StartList)
             |> DragDrop.makeDroppable model.dnd StartList dragDropMessages
           ) ( case DragDrop.currentlyDraggedObject model.dnd of
                 Nothing -> False
                 Just _ -> not (List.isEmpty technique.elems)
             )
      |> appendChildList
           ( List.concatMap
             ( \ call ->
                let
                  currentDrag = case DragDrop.currentlyDraggedObject model.dnd of
                                                       Nothing -> True
                                                       Just (Move x) ->(getId x) == (getId call)
                                                       Just _ -> False
                  dropElem = AfterElem Nothing call
                  dropTarget =  element "li"
                                   |> addClass "no-methods drop-zone"
                                   |> addStyle ("text-align", "center")
                                   |> addClassConditional "drop-target" (DragDrop.isCurrentDropTarget model.dnd dropElem)
                                   |> DragDrop.makeDroppable model.dnd dropElem dragDropMessages

                  base = if currentDrag then [] else [dropTarget]
                  elem =
                      case call of
                       Call parentId c ->
                         let
                           methodUi = Maybe.withDefault (MethodCallUiInfo Closed CallParameters Unchanged (ForeachUI False False (defaultNewForeach c.foreachName c.foreach))) (Dict.get c.id.value ui.callsUI)
                         in
                           showMethodCall model methodUi ui parentId c
                       Block parentId b ->
                         let
                           methodUi = Maybe.withDefault (MethodBlockUiInfo Closed Children ValidState True (ForeachUI False False (defaultNewForeach b.foreachName b.foreach))) (Dict.get b.id.value ui.blockUI)
                         in
                           showMethodBlock model ui methodUi parentId b
                in
                  elem :: base
             ) technique.elems
           )

    checkList : List (Bool, String)
    checkList =
      let
        invalidStates = checkInvalidStates technique ui
        invalidTechnique = not (List.isEmpty invalidStates)
      in
        [ (isUnchanged, "There are no modifications to save")
        , (invalidTechnique, String.join ".\n" invalidStates)
        , (isMethodListEmpty, "Technique must contain at least one method")
        , (not (List.isEmpty errorsOnMethodParameters), String.join ".\n" errorsOnMethodParameters)
        , (not areErrorOnMethodCondition, "There are errors on method conditions")
        , (not (List.isEmpty errorOnBlocks), String.join ".\n" errorOnBlocks)
        , (isEnumListIsEmpty, "Enum type parameters should contain at least one element")
        , (isEnumWithEmptyName, "The field 'display name' of Enum type parameter values cannot be empty")
        , (isEnumWithEmptyValue, "The field 'value' of Enum type parameter values cannot be empty")
        ]

    btnSave : Bool -> List (Bool, String) -> Msg -> Html Msg
    btnSave saving disableChecks action =
      let
        disable = disableChecks |> List.any (\(check, _) -> check == True)
        btnTitle =
          if disable then
            String.append
              ( disableChecks
                |> List.filter (\(check, _) -> check == True )
                |> List.map (\(_, txt) -> txt )
                |> String.join ".\n"
              ) "."
          else
            ""
        icon = if saving then "fa-spinner fa-pulse" else if disable then "fa-ban" else "fa-download"
      in
        button
        [ class ("btn btn-success btn-save" ++ (if saving then " saving" else ""))
        , type_ "button"
        , Html.Attributes.title btnTitle
        , disabled (saving || disable)
        , onClick action
        ] [ i [ class ("fa " ++ icon)][] ]
  in
    div [ class "main-container" ] [
      div [ class "main-header" ] [
        div [ class "header-title" ] [
          h1 [] title
        , div [ class "header-buttons btn-technique", hidden (not model.hasWriteRights) ] [
            div [ class "btn-group" ] [
              button [ class "btn btn-default dropdown-toggle" , attribute "data-bs-toggle" "dropdown" ] [
                text "Actions "
              , i [ class "caret" ] []
              ]
            , ul [ class "dropdown-menu" ] topButtons
            ]
          , button [ class "btn btn-primary", disabled (isUnchanged || creation) , onClick ResetTechnique ] [
              text "Reset "
            , i [ class "fa fa-undo"] []
            ]

          , button [ class "btn btn-primary", onClick (UpdateEdition ({editInfo | open = not editInfo.open }))] [
              text (if (editInfo.open) then "Visual editor " else "YAML editor")
            , i [ class "fa fa-pen"] []
            ]
          , btnSave ui.saving checkList StartSaving
          ]
        ]
      ]
    , div [ class "main-navbar" ]
      [ ul [ class "nav nav-underline" ]
        [ li [ class "nav-item"]
          [ button
            [ attribute "role" "tab", type_ "button", class ("nav-link " ++ (activeTabClass General)), onClick (SwitchTab General)]
            [ text "Information"]
          ]
        , li [ class "nav-item"]
          [ button
            [ attribute "role" "tab", type_ "button", class ("nav-link " ++ (activeTabClass Parameters)), onClick (SwitchTab Parameters)]
            [ text "Parameters "
            , span [ class ( "badge badge-secondary badge-resources " ++ if List.isEmpty technique.parameters then "empty" else "") ] [
                span [] [ text (String.fromInt (List.length technique.parameters)) ]
              ]
            ]
          ]
        , li [ class "nav-item"]
          [ button
            [ attribute "role" "tab", type_ "button", class ("nav-link " ++ (activeTabClass Resources)), onClick (SwitchTab Resources)]
            [ text "Resources "
            , span [  class  ( "badge badge-secondary badge-resources " ++ if List.isEmpty technique.resources then "empty" else "") ] [
                if ((List.isEmpty technique.resources)|| (List.any (\s -> (s.state == Untouched) || (s.state == Modified)) technique.resources) ) then span [ class "nb-resources" ] [text (String.fromInt (List.length (List.filter  (\s -> s.state == Untouched || s.state == Modified) technique.resources ) ))] else text ""
              , if not (List.isEmpty (List.filter (.state >> (==) New) technique.resources)) then  span [class "nb-resources new"] [ text ((String.fromInt (List.length (List.filter (.state >> (==) New) technique.resources))))] else text ""
              , if not (List.isEmpty (List.filter (.state >> (==) Deleted) technique.resources)) then  span [class "nb-resources del"] [ text (String.fromInt (List.length  (List.filter (.state >> (==) Deleted) technique.resources)))] else text ""
              ]
            ]
          ]
        , if (Maybe.Extra.isJust technique.output) then
          li [ class "nav-item" ]
          [ button
            [ attribute "role" "tab", type_ "button", class ("nav-link " ++ (activeTabClass Output)), onClick (SwitchTab Output)]
            [ text "Compilation output "
            , span [ class  ( "icon-output fa fa-cogs") ] []
            ]
          ]
          else
            text ""
        ]
      ]
    , div [ class "main-details", id "details"]
      [ div [ class "editForm",  name "ui.editForm" ]
        [ techniqueTab model technique creation ui
        , div [ class "row"]
          [ h5 []
            [ text "Methods"
            , span [ class "badge badge-secondary" ]
              [ span [] [ text (String.fromInt (List.length technique.elems ) ) ]
              ]
            , if (model.genericMethodsOpen || (not model.hasWriteRights) ) then text "" else
              button [class "btn-sm btn btn-success", type_ "button", onClick OpenMethods]
              [ text "Add "
              , i [ class "fa fa-plus-circle" ] []
              ]
            ]
          , if (editInfo.open) then
              div [class "col-sm-12"]
              [ textarea
                [ -- to deactivate plugin "Grammarly" or "Language Tool" from
                  -- adding HTML that make disapear textarea (see  https://issues.rudder.io/issues/21172)
                  attribute "data-gramm" "false"
                , attribute "data-gramm_editor" "false"
                , attribute "data-enable-grammarly" "false"
                , spellcheck False
                , class "yaml-editor"
                , rows (String.lines editInfo.value |> List.length)
                , onInput (\s -> UpdateEdition ({editInfo | value =  s})), value editInfo.value][]
              ]
            else render methodsList
          ]
        ]
      ]
    ]

view : Model -> Html Msg
view model =
  let
    central = case model.mode of
                Introduction ->
                    div [ class "main-container" ] [
                      div [ class "col jumbotron" ] [
                        h1 [] [ text "Techniques" ]
                      , p [] [ text "Create a new technique or edit one from the list on the left."]
                      , p [] [ text "Define target configuration using the generic methods from the list on the right as building blocks."]
                      , if (not model.hasWriteRights) then text "" else
                        button [ class "btn btn-success btn-lg" , onClick (GenerateId (\s -> NewTechnique (TechniqueId s) ))] [
                          text "Create technique "
                        , i [ class "fa fa-plus-circle" ] []
                        ]
                      ]
                    ]

                TechniqueDetails technique state uiInfo editInfo ->
                  showTechnique model technique state uiInfo editInfo

                TechniqueErrorDetails error ->
                  showTechniqueError error

    classes = "rudder-template " ++ if model.genericMethodsOpen then "show-right" else "show-left"


  in
    div [ id "technique-editor", class classes] [
      techniqueList model model.techniques
    , div [ class "template-main" ] [central]
    , methodsList model
    , case model.modal of
        Nothing -> text ""
        Just (DeletionValidation technique) ->
          div [class "modal fade show", style "display" "block" ]
          [ div [class "modal-backdrop fade show"][]
          , div [ class "modal-dialog" ] [
              div [ class "modal-content" ] [
                div [ class "modal-header" ] [
                  h5 [ class "modal-title" ] [ text "Delete Technique"]
                ]
              , div [ class "modal-body" ] [
                  text ("Are you sure you want to Delete Technique '")
                , b [][text technique.name]
                , text "' ?"
                ]
              , div [ class "modal-footer" ] [
                  button [ class "btn btn-primary", onClick (ClosePopup Ignore) ] [ --ng-click="cancel()"></button>
                    text "Cancel "
                  , i [ class "fa fa-arrow-left" ] []
                  ]
                , button [ class "btn btn-danger", onClick (ClosePopup (CallApi (deleteTechnique technique))) ] [--ng-click="confirm()"></button>
                    text "Delete "
                  , i [ class "fa fa-times-circle" ] []
                  ]
                ]
              ]
            ]
          ]
    ]


onContentEditableInput : (String -> msg) -> Attribute msg
onContentEditableInput tagger =
    Html.Events.stopPropagationOn "input"
        (Json.Decode.map (\x -> ( x, True )) (Json.Decode.map tagger innerText))

innerText : Json.Decode.Decoder String
innerText =
  Json.Decode.at ["target", "innerText"] Json.Decode.string
