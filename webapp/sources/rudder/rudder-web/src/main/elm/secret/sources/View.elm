module View exposing (..)
import ApiCalls exposing (deleteSecret)
import DataTypes exposing (Model, Msg(..), Secret, WriteAction(..))
import Html exposing (Html, b, button, div, h4, i, input, label, span, table, tbody, td, text, textarea, th, thead, tr, ul)
import Html.Attributes exposing (attribute, class, colspan, disabled, hidden, id, placeholder, style, tabindex, type_)
import Html.Events exposing (onClick, onInput)
import List exposing (any, concat, foldr, isEmpty, map, member)
import Markdown

constructTableLine : List Secret -> List String -> Html Msg
constructTableLine secrets openedDescription =
  let
    lines =
      map (
        \s ->
        let
          isFocused = if (member s.name openedDescription) then "focused" else "secretLine"
          chevronSide = if (member s.name openedDescription) then "fa-chevron-down" else "fa-chevron-right"
        in
        [
          tr [ class ("curspoint " ++ isFocused), attribute "role" "row", onClick (OpenDescription s) ]
          [
            td [class "name"][i [class ("chevron fas fa-xs " ++ chevronSide)][],b[][text s.name]]
          , td [class "description"][text s.value]
          , td [class "change"]
            [
                button [style "min-width" "50px", class "btn-edit btn btn-default btn-sm", onClick (OpenEditModal s)] [text "Edit"]
              , button [style "min-width" "50px", style "margin-left" "5px", class "btn-del btn btn-danger btn-sm", onClick (CallApi (deleteSecret s.name))] [text "Delete"]

            ]
          ]
          , if isEmpty openedDescription then
              div [][]
            else if (member s.name openedDescription) then
              displayDescriptionDetails s
            else
              div [][]
          ]
     ) secrets
 in
 tbody [] (concat lines)

displayTable : Model -> Html Msg
displayTable model =
  let
    secrets =  [(DataTypes.Secret "toto" "# Test title"), (DataTypes.Secret "tata" "value2"),( DataTypes.Secret "cqjdiueq" "value2"), (DataTypes.Secret "tv2rv2revwrata" "value2") ]
  in
  div [id "secretsGrid_wrapper", class "dataTables_wrapper no-footer"]
  [
    div [class "dataTables_wrapper_top"]
    [
      div [id "secretsGrid_filter", class "dataTables_filter"]
      [
        label []
        [
          input [type_ "search", placeholder "Filter", attribute "aria-controls" "secretsGrid"][]
        ]
      ]
    ]
    , table [id "secretsGrid", class "dataTable no-footer" ]
      [
        thead []
        [
          tr [class "head", attribute "role" "row"]
          [
            th [style "width" "300px;"][text "Name"]
          , th [][text "Description"]
          , th [style "width" "140px;"][text "Change"]
          ]
        ]
      , constructTableLine secrets model.openedDescription

      ]
  ]

displayActionZone : Html Msg
displayActionZone =
  div [id "actions_zone"]
  [
    div [class "createSecret"]
    [
      button [class "btn btn-success new-icon space-bottom space-top", onClick OpenCreateModal][ text "Create Secret" ]
    ]
  ]

displayModal : Model -> Html Msg
displayModal model =
    let
      focusedSecret =
        case model.focusOn of
          Just s  -> s.name
          Nothing -> "UNKNOWN"
      title =
        if model.isOpenCreateModal then
          "Add a Secret"
        else if model.isOpenEditModal then
          "Update Secret " ++ focusedSecret
        else
          "Unknown action"
      buttonText =
        if model.isOpenCreateModal then
          "Create"
        else if model.isOpenEditModal then
          "Update"
        else
          ""
      inputName =
        if model.isOpenCreateModal then
          input [class "rudderBaseFieldClassName form-control vresize col-lg-12 col-sm-12", onInput InputName][]
        else if model.isOpenEditModal then
          input [class "rudderBaseFieldClassName form-control vresize col-lg-12 col-sm-12", disabled True, onInput InputName, placeholder focusedSecret][text focusedSecret]
        else
          div [][]
   in
   if model.isOpenEditModal || model.isOpenCreateModal then
       div [class "modal-dialog"]
       [
         div [class "modal-content"]
         [
           div [class "modal-header"]
           [
             div [class "close", attribute "data-dismiss" "modal", onClick CloseModal]
             [
               i [class "fas fa-times"][]
             ]
           , h4 [class "modal-title"][text title]
           ]
         , div [class "modal-body"]
           [
             div [class "row wbBaseField form-group name"]
             [
               label [class "col-lg-3 col-sm-12 col-xs-12 text-right wbBaseFieldLabel"]
               [
                 span [class "text-fit"][text "Name"]
               ]
             , div [class "col-lg-9 col-sm-12 col-xs-12"]
               [
                 inputName
               ]
             ]
           , div [class "row wbBaseField form-group value"]
             [
               label [class "col-lg-3 col-sm-12 col-xs-12 text-right wbBaseFieldLabel"]
               [
                 span [class "text-fit"][text "Value"]
               ]
             , div [class "col-lg-9 col-sm-12 col-xs-12"]
               [
                 textarea [style "height" "4em", tabindex 2, class "rudderBaseFieldClassName form-control vresize col-lg-12 col-sm-12", onInput InputValue][]
               , div [class "text-muted small"]
                 [
                   text "The value will not be displayed in the interface, make sure to provide to description and a name to precisely identify the secret."
                 ]
               ]
             ]
           , div [class "row wbBaseField form-group description"]
             [
               label [class "col-lg-3 col-sm-12 col-xs-12 text-right wbBaseFieldLabel"]
               [
                 span [class "text-fit"][text "Description"]
               ]
             , div [class "col-lg-9 col-sm-12 col-xs-12"]
               [
                 textarea [style "height" "4em", tabindex 2, class "rudderBaseFieldClassName form-control vresize col-lg-12 col-sm-12", onInput InputDescription][]
               ]
             ]
           ]
         , div [class "modal-footer"]
           [
             button [id "cancel", class "btn btn-default", onClick CloseModal][text "Cancel"]
           , if(model.isOpenCreateModal) then
               button [id "saveSecretButton", class "btn btn-success", onClick (SubmitSecret Add)][text buttonText]
             else
               button [id "saveSecretButton", class "btn btn-success", onClick (SubmitSecret Edit)][text buttonText]

           ]
         ]
     ]
   else
     div[][]

displayDescriptionDetails : Secret -> Html Msg
displayDescriptionDetails s =
  tr [class "details secretsDescription"]
  [
    td [class "details", colspan 3]
    [
      span [class "secretsDescriptionDetails"]
      [
        span []
        [
          ul [class "evlogviewpad"] (
            Markdown.toHtml Nothing s.value
          )
        ]
      ]
    ]
  ]

view : Model -> Html Msg
view model =
  let
    modal =
      if model.isOpenEditModal || model.isOpenCreateModal then
        div  [id "secretForm", class "modal-backdrop fade in"] []
      else
        div [][]
  in
  div []
  [
    modal
  , displayModal model
  , div [class "header-secret "][]
  , div [class "content-block"]
    [
      displayActionZone
    , displayTable model
    ]
  ]