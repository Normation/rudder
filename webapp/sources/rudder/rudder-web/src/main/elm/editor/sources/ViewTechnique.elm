module ViewTechnique exposing (..)

import ApiCalls exposing (..)
import DataTypes exposing (..)
import Dict exposing (Dict)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)
import ViewMethod exposing (..)
import ViewBlock exposing (..)
import ViewMethodsList exposing (..)
import ViewTechniqueTabs exposing (..)
import ViewTechniqueList exposing (..)
import Dom exposing (..)
import Dom.DragDrop as DragDrop
import MethodElemUtils exposing (..)

--
-- This file deals with the UI of one technique
--

checkTechniqueId origin technique model =
  case origin of
    Edit _ -> ValidState
    _ -> if (List.any (.id >> (==) technique.id) model.techniques) then
           InvalidState AlreadyTakenId
         else if String.length technique.id.value > 255 then
           InvalidState TooLongId
         else if String.startsWith "_" technique.id.value then
           InvalidState InvalidStartId
         else
           ValidState

checkTechniqueName technique model =
  if String.isEmpty technique.name then
   InvalidState EmptyName
  else
   if List.any (.name >> (==) technique.name) (List.filter (.id >> (/=) technique.id ) model.techniques) then
     InvalidState AlreadyTakenName
   else
     ValidState

isValidState state =
  case state of
    Unchanged -> True
    ValidState -> True
    InvalidState _ -> False

isValid: TechniqueUiInfo -> Bool
isValid ui =
  (isValidState ui.idState )  && ( isValidState ui.nameState ) && (List.all (isValidState) (List.concatMap (.validation >> Dict.values ) (Dict.values ui.callsUI)))


showTechnique : Model -> Technique ->  TechniqueState -> TechniqueUiInfo -> Html Msg
showTechnique model technique origin ui =
  let
    activeTabClass = (\tab -> "ui-tabs-tab " ++ (if ui.tab == tab then "active" else ""))
    creation = case origin of
                 Creation _ -> True
                 Clone _ _ -> True
                 Edit _ -> False
    isUnchanged = case origin of
                    Edit t -> t == technique
                    Creation _ -> False
                    Clone t _ -> t == technique
    deleteAction = if creation then .id >> Ok >> DeleteTechnique else OpenDeletionPopup
    topButtons =  [ li [] [
                      a [ class "action-success", disabled creation , onClick (GenerateId (\s -> CloneTechnique technique (TechniqueId s))) ] [
                        text "Clone "
                      , i [ class "fa fa-clone"] []
                      ]
                    ]
                  , li [] [
                      a [ class "action-primary" , onClick Export] [ --ng-disabled="isNotSaved()"  ng-click=""exportTechnique(selectedTechnique)
                        text "Export "
                      , i [ class "fa fa-download"] []
                      ]
                    ]
                  , li [] [
                      a [ class "action-danger", onClick (deleteAction technique)] [ --ng-disabled="isNotSaved()"  ng-click="confirmPopup('Delete','Technique', deleteTechnique, selectedTechnique, selectedTechnique.name)"
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
             |> addAttribute (id "no-methods")
             |> appendChildList
                [ element "i"
                  |> addClass "fas fa-sign-in-alt"
                  |> addStyle ("transform", "rotate(90deg)")
                , element "span"
                  |> appendText " Drag and drop generic methods here from the list on the right to build target configuration for this technique."
                ]
             |> DragDrop.makeDroppable model.dnd StartList dragDropMessages
             |> addAttribute (hidden (not (List.isEmpty technique.elems)))
           )
      |> appendChild
           ( element "li"
             |> addAttribute (id "no-methods")
             |> addStyle ("text-align", "center")
             |> addStyle ("opacity", (if (DragDrop.isCurrentDropTarget model.dnd StartList) then "1" else  "0.4"))
             |> appendChild
                ( element "i"
                  |> addClass "fas fa-sign-in-alt"
                  |> addStyle ("transform", "rotate(90deg)")
                )
             |> addStyle ("padding", "3px 15px")
             |> DragDrop.makeDroppable model.dnd StartList dragDropMessages
             |> addAttribute (hidden (case DragDrop.currentlyDraggedObject model.dnd of
                                                   Nothing -> True
                                                   Just (Move x) ->Maybe.withDefault True (Maybe.map (\c->  (getId x) == (getId c)) (List.head technique.elems))
                                                   Just _ -> False
                             ) )
           )
      |> appendChildList
           ( List.concatMap ( \ call ->
               case call of
                 Call parentId c ->
                   let
                     methodUi = Maybe.withDefault (MethodCallUiInfo Closed Nothing Dict.empty True) (Dict.get c.id.value ui.callsUI)
                     currentDrag = case DragDrop.currentlyDraggedObject model.dnd of
                                     Nothing -> True
                                     Just (Move x) ->(getId x) == c.id
                                     Just _ -> False
                     base =     [ showMethodCall model methodUi parentId c ]
                     dropElem = AfterElem Nothing (Call parentId c)
                     dropTarget =  element "li"
                                   |> addAttribute (id "no-methods") |> addStyle ("padding", "3px 15px")
                                   |> addStyle ("text-align", "center")
                                   |> addStyle ("opacity", (if (DragDrop.isCurrentDropTarget model.dnd dropElem) then "1" else  "0.4"))
                                   |> DragDrop.makeDroppable model.dnd dropElem dragDropMessages
                                   |> addAttribute (hidden currentDrag)
                                   |> appendChild
                                      ( element "i"
                                        |> addClass "fas fa-sign-in-alt"
                                        |> addStyle ("transform", "rotate(90deg)")
                                      )
                   in
                      List.reverse (dropTarget :: base)
                 Block parentId b ->
                   let
                     methodUi = Maybe.withDefault (MethodCallUiInfo Closed Nothing Dict.empty True) (Dict.get b.id.value ui.callsUI)
                   in
                     [ showMethodBlock model ui methodUi parentId b ]
             ) technique.elems
           )

  in
    div [ class "main-container" ] [
      div [ class "main-header" ] [
        div [ class "header-title" ] [
          h1 [] title
        , div [ class "header-buttons btn-technique", hidden (not model.hasWriteRights) ] [
            div [ class "btn-group" ] [
              button [ class "btn btn-default dropdown-toggle" , attribute "data-toggle" "dropdown" ] [
                text "Actions "
              , i [ class "caret" ] []
              ]
            , ul [ class "dropdown-menu" ] topButtons
            ]
          , button [ class "btn btn-primary", disabled (isUnchanged || creation) , onClick ResetTechnique ] [
              text "Reset "
            , i [ class "fa fa-undo"] []
            ]
          , button [ class "btn btn-success btn-save", disabled (isUnchanged || (not (isValid ui)) || ui.saving || String.isEmpty technique.name), onClick (CallApi (saveTechnique technique creation)) ] [ --ng-disabled="ui.editForm.$pending || ui.editForm.$invalid || CForm.form.$invalid || checkSelectedTechnique() || saving"  ng-click="saveTechnique()">
              text "Save "
            , i [ class ("fa fa-download " ++ (if ui.saving then "glyphicon glyphicon-cog fa-spin" else "")) ] []
            ]
          ]
        ]
      ]
    , div [ class "main-navbar" ] [
        ul [ class "ui-tabs-nav nav nav-tabs" ] [
          li [ class (activeTabClass General) , onClick (SwitchTab General)] [
            a [] [ text "General information" ]
          ]
        , li [ class (activeTabClass Parameters), onClick (SwitchTab Parameters) ] [
            a [] [
              text "Parameters "
            , span [ class ( "badge badge-secondary badge-resources " ++ if List.isEmpty technique.parameters then "empty" else "") ] [
                span [] [ text (String.fromInt (List.length technique.parameters)) ]
              ]
            ]
          ]
        , li [ class (activeTabClass Resources)  , onClick (SwitchTab Resources)] [
            a [] [
              text "Resources "
            , span [  class  ( "badge badge-secondary badge-resources tooltip-bs " ++ if List.isEmpty technique.resources then "empty" else "") ] [
                 -- ng-class="{'empty' : selectedTechnique.resources.length <= 0}"
                 -- data-toggle="tooltip"
                 -- data-trigger="hover"
                 -- data-container="body"
                --  data-placement="right"
                --  data-title="{{getResourcesInfo()}}"
                 -- data-html="true"
                 -- data-delay='{"show":"400", "hide":"100"}'
                 -- >
                if ((List.isEmpty technique.resources)|| (List.any (\s -> (s.state == Untouched) || (s.state == Modified)) technique.resources) ) then span [ class "nb-resources" ] [text (String.fromInt (List.length (List.filter  (\s -> s.state == Untouched || s.state == Modified) technique.resources ) ))] else text ""
              , if not (List.isEmpty (List.filter (.state >> (==) New) technique.resources)) then  span [class "nb-resources new"] [ text ((String.fromInt (List.length (List.filter (.state >> (==) New) technique.resources))) ++ "+")] else text ""
              , if not (List.isEmpty (List.filter (.state >> (==) Deleted) technique.resources)) then  span [class "nb-resources del"] [ text ((String.fromInt (List.length  (List.filter (.state >> (==) Deleted) technique.resources)) ++ "-"))] else text ""
              ]
            ]
          ]
        ]
      ]
    , div [ class "main-details", id "details"] [
        div [ class "editForm",  name "ui.editForm" ] [
          techniqueTab model technique creation ui
        , h5 [] [
            text "Generic Methods"
          , span [ class "badge badge-secondary" ] [
              span [] [ text (String.fromInt (List.length technique.elems ) ) ]
            ]
          , if (model.genericMethodsOpen || (not model.hasWriteRights) ) then text "" else
                button [class "btn-sm btn btn-success", type_ "button", onClick OpenMethods] [
                  text "Add "
                , i [ class "fa fa-plus-circle" ] []
                ]
          ]
       ,  div [ class "row"] [
            render methodsList
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
                      , button [ class "btn btn-success btn-lg" , onClick (GenerateId (\s -> NewTechnique (TechniqueId s) ))] [
                          text "Create Technique "
                        , i [ class "fa fa-plus-circle" ] []
                        ]
                      ]
                    ]

                TechniqueDetails technique state uiInfo ->
                  showTechnique model technique state uiInfo
    classes = "rudder-template " ++ if model.genericMethodsOpen then "show-methods" else "show-techniques"


  in
    div [ id "technique-editor", class classes] [
      techniqueList model model.techniques
    , div [ class "template-main" ] [central]
    , methodsList model
    , case model.modal of
        Nothing -> text ""
        Just (DeletionValidation technique) ->
          div [ tabindex -1, class "modal fade ng-isolate-scope in", style "z-index" "1050", style "display"  "block" ]  [ -- modal-render="true" tabindex="-1" role="dialog" uib-modal-animation-class="fade" modal-in-class="in" ng-style="{'z-index': 1050 + index*10, display: 'block'}" uib-modal-window="modal-window" index="0" animate="animate" modal-animation="true" style="z-index: 1050; display: block;">
            div [ class "modal-dialog" ] [
              div [ class "modal-content" ]  [-- uib-modal-transclude="">
                div [ class "modal-header ng-scope" ] [
                  h3 [ class "modal-title" ] [ text "Delete Technique"]
                ]
              , div [ class "modal-body" ] [
                  text "Are you sure you want to Delete Technique 'ressource test'?"
                ]
              , div [ class "modal-footer" ] [
                  button [ class "btn btn-primary btn-outline pull-left", onClick (ClosePopup Ignore) ] [ --ng-click="cancel()"></button>
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

