module Editor.ViewTechniqueTabs exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)

import Editor.AgentValueParser exposing (..)
import Editor.DataTypes exposing (..)


--
-- This deals with the technique tabs UI (general info/parameters/resources)
--

techniqueResource : Resource -> Html Msg
techniqueResource  resource =
  let
    stateClass =
      case resource.state of
        New -> "new"
        Deleted -> "deleted"
        Modified -> "modified"
        Untouched -> "untouched"
  in
    li [ class ("state-"++stateClass) ] [
      span [ class "fa fa-file" ] []
    , span [ class "target-name" ] [ text resource.name ]
    , span [ class "border" ] []
    , div [ class "use-with" ] [
        span [] [ text ( "${resources_dir}/"++ resource.name) ]
      , button [ class "btn btn-outline-secondary clipboard", title "Copy to clipboard" , onClick (Copy ("${resources_dir}/"++ resource.name)) ] [
          i [  class "ion ion-clipboard" ] []
        ]
      ]
    ]


techniqueParameter :  Model -> Technique -> TechniqueParameter -> Bool -> Html Msg
techniqueParameter model technique param opened =
  let
    param_name =
      if (String.isEmpty param.name) then
        [  div [ class "empty-name"] [ text "Set a parameter name" ]]

      else
        [ div [ class "use-with" ] [
            span [] [ text ("${"++(canonifyHelper (Value param.name))++"}") ]
          ]
        , div [ class "full-name" ] [
            span [] [ text (technique.id.value ++"." ++ (canonifyHelper (Value param.name)))]
          ]

        ]
    (beEmptyTitle, beEmptyClass) =
      if (param.mayBeEmpty) then
        ( "Parameter value can be empty.\nWhen adding a parameter to an existing technique, policy will be generated with an empty value and automatically deployed to your nodes, so be careful when adding one"
        , "btn-outline-primary"
        )
      else
        ( "Parameter cannot be empty and needs a value.\nIf you add a parameter to an existing technique, policy generation will fail and you will need to update all directives with the new parameter value"
        , "btn-info"
        )
  in
    li [] [
      span [ class "border" ] []
    , div [ class "param" ] [
        div [ class "input-group" ] [
          input [readonly (not model.hasWriteRights), type_ "text",  class "form-control", value param.name, placeholder "Parameter name", onInput (\s -> TechniqueParameterModified param.id {param | name = s }), required True] []
        , div [ class "input-group-btn" ] [
            button [ class ("btn btn-outline " ++ beEmptyClass), title beEmptyTitle, onClick (TechniqueParameterModified param.id {param | mayBeEmpty = not param.mayBeEmpty }) ] [
              text ( if param.mayBeEmpty then "May be empty" else "Required" )
            ]
          ]
        , div [ class "input-group-btn" ] [
            button [ class "btn btn-outline-secondary clipboard", title "Copy to clipboard" , onClick (Copy ("${" ++ (canonifyHelper (Value param.name)) ++ "}")) ] [
              i [ class "ion ion-clipboard" ] []
            ]
          ]
        ]
      , div [] param_name
      , button [ class "btn btn-sm btn-outline-primary",  style "margin-top" "5px" , onClick (TechniqueParameterToggle param.id)] [
          text "Description "
        , i [ class (if opened then "fa fa-times" else "ion ion-edit") ] []
        ]
      , if opened then textarea [ readonly (not model.hasWriteRights), style "margin-top" "10px",  class "form-control",  rows 1,  value param.description, onInput (\s -> TechniqueParameterModified param.id {param | description = s })] [] else text "" -- msd-elastic
      ]
    , div [ class "remove-item", onClick (TechniqueParameterRemoved param.id) ] [
        i [ class "fa fa-times"] []
      ]
    ]

getSubElems: TechniqueCategory -> List TechniqueCategory
getSubElems cat =
  case cat.subCategories of
    SubCategories subs -> subs

buildListCategoriesWithoutRoot : String -> String -> TechniqueCategory -> List(Html Msg)
buildListCategoriesWithoutRoot sep category c =
    let
      newList =
        let
          blankSpace     = String.repeat 2 (String.fromChar (Char.fromCode 8199))
          currentOption  = if (c.name == "/") then [] else [ option [ selected (c.path == category), value c.path] [ text (sep ++ c.name) ] ]
          separator      =
            if String.isEmpty sep then
              "└─ "
            else
               blankSpace ++ sep
          listCategories = List.concatMap (buildListCategoriesWithoutRoot separator category) (getSubElems c)
        in
          List.append currentOption listCategories
    in
      newList

techniqueTab : Model -> Technique -> Bool -> TechniqueUiInfo -> Html Msg
techniqueTab model technique creation ui =
  let
    searchForCategory = List.head ( List.filter (\c -> c.path == technique.category) (allCategorieswithoutRoot model) )
    categoryName =
      case searchForCategory of
        Just c -> c.name
        _      -> "Error: unknown category name"
    disableCategory =
      if creation then
        select [class "form-control", name "category", id "category", value technique.category, onInput (\s -> UpdateTechnique {technique | category = s}) ]
          (buildListCategoriesWithoutRoot "" technique.category model.categories)
      else
        input [readonly True, class "form-control", id "category", value categoryName][]
    classErrorInputId =
      case ui.idState of
        InvalidState errors -> " error "
        _ -> if String.length technique.id.value > 100 then " error " else ""
    classErrorName =
      case ui.nameState of
        InvalidState errors ->
          if (List.member (AlreadyTakenName) errors || List.member (EmptyName) errors) then " error " else ""
        _ -> ""
  in
  case ui.tab of
    General -> div [ class "tab tab-general" ] [
                         {-div [ class "col-xs-12" ] [
                           div [ class "alert alert-warning" ] [ -- ng-if="conflictFlag">
                             text """Your changes have been kept. If you save this Technique, your version will replace the current one.
                                   You can still reset it or click on the following button to update it."""
                           , div [ class "btn-group-alert" ] [
                               button [ class "btn btn-sm btn-default" ] [-- ng-click="resetTechnique()">Update</button>
                                 text "Update"
                               ]
                             ]
                           ]
                         ]
                       ,-} div [ class "row form-group" ] [--ng-class="{'has-error':ui.editForm.name.$dirty && (ui.editForm.name.$error.required || ui.editForm.name.$error.techniqueName)}">
                           label [ for "techniqueName", class "col-xs-12 control-label" ] [ text "Name"  ]
                         , div  [ class "col-sm-8" ] [
                             input [readonly (not model.hasWriteRights), type_ "text" , id "techniqueName",  name "name",  class ("form-control" ++ classErrorName) , placeholder "Technique Name", value technique.name
                              , onInput (\newName -> UpdateTechnique {technique | name = newName, id = TechniqueId(if creation then canonifyHelper (Value (String.toLower newName)) else technique.id.value) })
                              ] []
                           ]
                         , case ui.nameState of
                             InvalidState errors -> ul []
                                                      (List.map (
                                                        \err -> case err of
                                                          AlreadyTakenName -> li [ class "text-danger col-sm-8" ] [ text "Technique name must be unique" ]
                                                          EmptyName -> li [ class "text-danger col-sm-8" ] [ text "Technique name is required" ]
                                                      ) errors)
                             _ -> text ""
                         ]
                       , div [ class "row form-group" ] [
                             label [ for "techniqueDescription", class "col-xs-12 control-label" ] [ text "Description"  ]
                           , div  [ class "col-sm-8" ] [
                               input [readonly (not model.hasWriteRights), type_ "text" , id "techniqueDescription",  name "description",  class ("form-control") , placeholder "Technique description", value technique.description
                                 , onInput (\newDesc -> UpdateTechnique {technique | description = newDesc })
                               ] []
                             ]
                         ]
                       , div [ class "row form-group" ] [
                           label [ for "techniqueDocumentation", class "col-xs-12 control-label" ] [
                             span [ class "text-fit" ] [ text "Documentation" ]
                           , img  [ class "markdown-icon tooltip-icon popover-bs",  src ( model.contextPath ++ "/images/markdown-mark-solid.svg" ) ] []
                                     --data-toggle="popover"
                                    -- data-trigger="hover"
                                    -- data-container="body"
                                    -- data-placement="right"
                                    -- data-content="This content will be displayed as <b>Markdown</b> in <b>directives</b> using this technique"
                                   --  data-html="true"
                           ]
                         , div [ class "col-sm-8" ] [
                             textarea [  readonly (not model.hasWriteRights), name "documentation",  class "form-control technique-description", id "techniqueDocumentation", rows  4, value technique.documentation, placeholder "documentation"
                             , onInput (\desc -> UpdateTechnique {technique | documentation = desc })
                             ] []--msd-elastic
                           ]
                         ]
                       , div [ class "row form-group" ] [ -- show-errors>
                           label [ for "bundleName", class "col-xs-12 control-label"] [ text "Technique ID" ]
                         , div [ class "col-sm-8" ] [
                             input [ readonly True,  id "bundleName", name "bundle_name", class ("form-control" ++ classErrorInputId), value technique.id.value ] [] -- bundlename ng-model="selectedTechnique.bundle_name" ng-maxlength="252" ng-pattern="/^[^_].*$/">
                           ]
                         , case ui.idState of
                             InvalidState errors -> ul []
                                                      (List.map (
                                                        \err -> case err of
                                                          TooLongId -> li [ class "text-danger col-sm-8" ] [text "Technique IDs longer than 255 characters won't work on most filesystems." ]
                                                          AlreadyTakenId -> li [ class "text-danger col-sm-8" ] [ text "Technique ID must be unique." ]
                                                          InvalidStartId -> li [ class "text-danger col-sm-8"] [ text "Technique ID should start with an alphanumeric character." ]
                                                      ) errors)
                             _ -> if String.length technique.id.value > 100 then
                                    span [ class "rudder-text-warning col-sm-8 col-sm-offset-3" ] [
                                      b [] [ span [ class "glyphicon glyphicon-exclamation-sign" ] [] ]
                                    , text "Technique IDs longer than 100 characters may not work on some filesystems (Windows, in particular)."
                                    ]
                                  else text ""
                         ]
                       , div [ class "row form-group" ] [ -- show-errors>
                           label [ for "category",  class "col-xs-12 control-label"] [ text "Category" ]
                         , div [ class "col-sm-8" ] [
                             disableCategory
                           -- Used to be a else on creation with a readonly input a tried a readonly select <input  ng-if="originalTechnique.bundle_name !== undefined" readonly id="category" bundlename name="category" class="form-control" ng-model="getCategory(selectedTechnique.category).value">
                           ]
                         ]
                       ]
    None -> text ""
    Parameters ->
      let
        paramList =
          if List.isEmpty technique.parameters then
            [ li [ class "empty"] [
                span [] [ text "There is no parameter defined." ]
              , span [ class "warning-sign" ] [
                  i [ class "fa fa-info-circle" ] []
                ]
              ]
            ]
          else
            List.map (\p -> techniqueParameter model technique p (List.member p.id ui.openedParameters ) ) technique.parameters
      in
        div [ class "tab tab-parameters" ] [
          ul [ class "files-list parameters" ]
            paramList
        , div [ class "text-center btn-manage" ] [
            div [ class "btn btn-success btn-outline", onClick (GenerateId (\s -> TechniqueParameterAdded (ParameterId s)))] [
              text "Add parameter "
            , i [ class  "fa fa-plus-circle"] []
            ]
          ]
        ]
    Resources ->
      let
        resourceList =
          if List.isEmpty technique.resources then
            [ li [class "empty" ] [
              span [] [ text "There is no resource files."]
            , span [ class "warning-sign" ] [
                i [ class "fa fa-info-circle" ] []
              ]
            ] ]
          else
            List.map techniqueResource technique.resources
      in
        div [ class "tab tab-resources" ] [
          ul [ class "files-list" ]
            resourceList
        , if (List.any (\s -> (s.state == New) || (s.state == Deleted)) technique.resources) then
            let
              number = List.length (List.filter (\s -> (s.state == New) || (s.state == Deleted)) technique.resources)
            in
              div [ class "resources-uncommitted" ] [ -- ng-if="getResourcesByState(selectedTechnique.resources, 'new').length>0 || getResourcesByState(selectedTechnique.resources, 'deleted').length > 0">
                span [] [
                  i [ class "fa fa-exclamation-triangle"] []
                , text ("There " ++ (if(number == 1) then "is " else "are "))
                , b [] [ text (String.fromInt number) ]
                , text (" unsaved " ++ ((if(number == 1) then "file " else "files")) ++ ", save your changes to complete upload.")
                ]
              ]
          else text ""
        , div [ class "text-center btn-manage" ] [
            div [ class "btn btn-default", onClick OpenFileManager ] [
              text "Manage resources "
            , i [ class "fa fa-folder" ] []
            ]
          ]
        ]
