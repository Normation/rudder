module Editor.ViewTechniqueTabs exposing (..)

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)

import Editor.AgentValueParser exposing (..)
import Editor.DataTypes exposing (..)
import List.Extra
import Maybe.Extra
import Regex
import String.Extra


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


techniqueParameter :  Model -> Technique -> TechniqueParameter -> Html Msg
techniqueParameter model technique param =
  let
    param_var_name = if (String.isEmpty param.name) then canonifyString (Maybe.withDefault "" param.description) else param.name
    param_name =
      if (String.isEmpty param.name && Maybe.Extra.isNothing param.description ) then
        [  div [ class "empty-name"] [ text "Set a parameter name" ]]

      else
        [ div [ class "use-with" ] [
            span [] [ text ("${"++param_var_name++"}") ]
          ]
        , div [ class "full-name" ] [
            span [] [ text ( technique.id.value ++"." ++ param_var_name )]
          ]

        ]

    beEmptyTitle =
      if (param.mayBeEmpty) then
        "Parameter value can be empty.\nWhen adding a parameter to an existing technique, policy will be generated with an empty value and automatically deployed to your nodes, so be careful when adding one"
      else
        "Parameter cannot be empty and needs a value.\nIf you add a required parameter to an existing technique, policy generation will fail and you will need to update all directives with the new parameter value"

    (invalidParamClass, invalidParamElem) =
      if (Regex.contains ((Regex.fromString >> Maybe.withDefault Regex.never) "[^_a-zA-Z\\d]") param.name) then
        ("error",  ul [class "row"] [ li [ class "text-danger col-md-8" ] [ text "The variable name is invalid, only alphanumerical characters and _ are accepted" ] ] )
      else
        ("", text "")

    (invalidNameClass, invalidNameElem) =
      if (String.isEmpty param.name && Maybe.Extra.isNothing param.description) then
        ("error",  ul [class "row"] [ li [ class "text-danger col-md-8" ] [ text "Parameter name cannot be empty" ] ] )
      else
        ("", text "")

    checkboxId = "paramRequired-" ++ param.id.value
    nameId  = "name-"++param.id.value
    varId  = "var-"++param.id.value
    typeId  = "type-"++param.id.value
    descriptionId = "description-"++param.id.value

    selectTypeValue = if param.constraints.select == Nothing then "Text" else "Enum"
    onInputType = \s ->
              let newConstraint = {constraint | select =  if s == "Text" then Nothing else Just [ SelectOption "" Nothing ] }
                in
              TechniqueParameterModified param.id {param | constraints = newConstraint }

    constraint = param.constraints
    (invalidEnumTypeClass, invalidEnumTypeElem) =
        if ("Enum" == selectTypeValue) then
          case param.constraints.select of
            Nothing ->
              ("error",  ul [class "row"] [ li [ class "text-danger col-md-8" ] [ text "Enum type should contain at least one element" ] ] )
            Just opts ->
              if(List.isEmpty opts) then
                ("error",  ul [class "row"] [ li [ class "text-danger col-md-8" ] [ text "Enum type should contain at least one element" ] ] )
              else
                ("", text "")
        else
          ("", text "")


  in
    li [] [
      span [ class "border" ] []
    , div [ class "param" ] [
        div [ class "gm-labels left"] [
          div [ class "gm-label rudder-label gm-label-name "] [ text "Parameter" ]
        ]

      , div [ class " form-group" ] [
          label [  for varId] [ text "Variable name"]
        , div [class "input-group" ] [
            input [id  varId, readonly (not model.hasWriteRights), type_ "text",  class ("form-control "++invalidParamClass), value param.name, placeholder (if (Maybe.Extra.isNothing param.description) then "Variable name" else (canonifyString (Maybe.withDefault "" param.description))) , onInput (\s -> TechniqueParameterModified param.id {param | name = s }), required True] []
          , button [ class "btn btn-outline-secondary clipboard", title "Copy to clipboard" , onClick (Copy ("${" ++ param_var_name ++ "}")) ] [
              i [ class "ion ion-clipboard" ] []
            ]
          ]
        , invalidNameElem
        , invalidParamElem
        ]

      , div [ class "form-group" ] [
          label [  for nameId] [ text "Display name"]
        , input [ id nameId, readonly (not model.hasWriteRights), type_ "text",  class ("col-xs-8 form-control " ++ invalidNameClass), value (Maybe.withDefault "" param.description), placeholder "Parameter name", onInput (\s -> TechniqueParameterModified param.id {param | description = if (String.isEmpty s) then Nothing else Just s }), required True] []

        ]

      , div [ class " form-group" ] [
          label [  for descriptionId] [ text "Description"]
        , div [class "input-group" ] [
            textarea [ id  descriptionId, readonly (not model.hasWriteRights),  class ("form-control")
                     , rows 1,  value (Maybe.withDefault "" param.documentation), placeholder "description" , onInput (\s -> TechniqueParameterModified param.id {param | documentation = Just s }), required True] []
          ]
        ]

      , div [ class " form-group" ] [
          label [ for typeId] [ text "Type"]
        , div [ class "input-group" ] [
            select [id  typeId, readonly (not model.hasWriteRights), class ("form-select " ++ invalidEnumTypeClass), value selectTypeValue, onInput onInputType] [
              option [ value "Text", selected ("Text" == selectTypeValue) ] [ text "Text"]
            , option [ value "Enum", selected ("Enum" == selectTypeValue) ] [ text "Enum"]
            ]
          , label [ class "input-group-text", for checkboxId]
            [ input[type_ "checkbox", id checkboxId, checked (not param.mayBeEmpty), onCheck (\c ->
            let newConstraint = {constraint | allowEmpty = Just c}
            in
              (TechniqueParameterModified param.id {param | mayBeEmpty = not c, constraints = newConstraint }))][]
          , span [][text " Required "]
          , span
            [ class "cursor-help"
            , attribute "data-bs-toggle" "tooltip"
            , attribute "data-bs-placement" "top"
            , attribute "data-bs-html" "true"
            , title beEmptyTitle
            ] [ i [ class "text-info fa fa-question-circle" ] []]
          ]
        ]
      , invalidEnumTypeElem
      ]
      , ( case constraint.select of
            Just l ->
             let
               displayValue =
                 \index currentValue ->
                    let
                       (invalidEnumNameClass, invalidEnumName) =
                         if (String.isEmpty (Maybe.withDefault "" currentValue.name)) then
                           ("error",  ul [class "row"] [ li [ class "text-danger col-md-8" ] [ text "Enum name cannot be empty" ] ] )
                         else
                           ("", text "")
                       (invalidEnumValueClass, invalidEnumValue) =
                         if (String.isEmpty (currentValue.value) && not param.mayBeEmpty ) then
                           ("error",  ul [class "row"] [ li [ class "text-danger col-md-8" ] [ text "Required is checked so the value cannot be left empty" ] ] )
                         else
                           ("", text "")
                    in
                    li[] [
                      span [ class "border" ] []
                    , div [ class "param" ] [
                        div [ class "gm-labels left"] [
                          div [ class "gm-label rudder-label gm-label-name "] [ text "Enum" ]
                        ]
                      , div [ class "form-group" ] [
                          label [] [ text "Display name"]
                        , input [ readonly (not model.hasWriteRights), type_ "text",  class ("col-xs-8 form-control " ++ invalidEnumNameClass), placeholder "Name"
                                , value (Maybe.withDefault "" currentValue.name), placeholder "Name" , onInput (\s ->
                                    let
                                      newConstraint = { constraint | select = Just (List.Extra.updateAt index (\val -> {val | name = if String.isEmpty s then Nothing else Just s}) l  ) }
                                    in
                                      TechniqueParameterModified param.id {param | constraints = newConstraint }), required True] []
                        , invalidEnumName
                        ]
                      , div [ class "form-group" ] [
                          label [ ] [ text "Value"]
                        , div [class "form-group" ] [
                            input [ readonly (not model.hasWriteRights), type_ "text",  class ("col-xs-8 form-control " ++ invalidEnumValueClass), value currentValue.value, placeholder "Value"
                                  , onInput (\s ->
                                      let
                                        newConstraint = { constraint | select = Just (List.Extra.updateAt index (\val -> {val | value = s}) l  ) }
                                      in
                                        TechniqueParameterModified param.id {param | constraints = newConstraint }), required True] []
                          , invalidEnumValue
                          ]
                        ]
                      ]
                    , div [ class "remove-item", onClick(
                              let
                                newConstraint = { constraint | select = Just (List.Extra.removeAt index l  ) }
                              in
                                TechniqueParameterModified param.id {param | constraints = newConstraint }) ] [
                        i [ class "fa fa-times"] []
                      ]
                    ]
             in
             ul [ class "files-list parameters"]
              ( List.concat [
                List.indexedMap displayValue l
              , [ button [
                    class "btn btn-outline-primary", title "add a new value"
                  , onClick (
                      let
                        newConstraint = { constraint | select = Just (List.concat [l , [ SelectOption "" Nothing] ]  ) }
                      in
                        TechniqueParameterModified param.id {param | constraints = newConstraint }
                    ), required True
                  ] [
                    text "Add value"
                  , i [ class "fa fa-plus" ] []
                  ]
                ]
              ])
            Nothing -> text ""
        )
      , div [] param_name
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
        select [class "form-select", name "category", id "category", value technique.category, onInput (\s -> UpdateTechnique {technique | category = s}) ]
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
                         div [ class "row form-group", style "margin-top" "15px" ] [
                           label [ for "techniqueName", class "col-sm-12" ]
                           [ text "Name"
                           , span [class "mandatory-param"] [text " *"]
                           ]
                         , div  [ class "col-md-8" ] [
                             input [readonly (not model.hasWriteRights), type_ "text" , id "techniqueName",  name "name",  class ("form-control" ++ classErrorName) , placeholder "Technique name", value technique.name
                              , onInput (\newName -> UpdateTechnique {technique | name = newName, id = TechniqueId(if creation then canonifyHelper (Value (String.toLower newName)) else technique.id.value) })
                              ] []
                           ]
                         , case ui.nameState of
                             InvalidState errors -> ul []
                                                      (List.map (
                                                        \err -> case err of
                                                          AlreadyTakenName -> li [ class "text-danger col-md-8" ] [ text "Technique name must be unique" ]
                                                          EmptyName -> li [ class "text-danger col-md-8" ] [ text "Technique name is required" ]
                                                      ) errors)
                             _ -> text ""
                         ]
                       , div [ class "row form-group" ] [
                             label [ for "techniqueDescription", class "col-sm-12 control-label" ] [ text "Description"  ]
                           , div  [ class "col-md-8" ] [
                               input [readonly (not model.hasWriteRights), type_ "text" , id "techniqueDescription",  name "description",  class ("form-control") , placeholder "Technique description", value technique.description
                                 , onInput (\newDesc -> UpdateTechnique {technique | description = newDesc })
                               ] []
                             ]
                         ]
                       , div [ class "row form-group" ] [
                           label [ for "techniqueDocumentation", class "col-sm-12 control-label" ] [
                             span [ class "fw-normal" ] [ text "Documentation" ]
                           , img  [ class "markdown-icon tooltip-icon",  src ( model.contextPath ++ "/images/markdown-mark-solid.svg" ) ] []
                           ]
                         , div [ class "col-md-8" ] [
                             textarea [  readonly (not model.hasWriteRights), name "documentation",  class "form-control technique-description", id "techniqueDocumentation", rows  4, value technique.documentation, placeholder "Documentation"
                             , onInput (\desc -> UpdateTechnique {technique | documentation = desc })
                             ] []
                           ]
                         ]
                       , div [ class "row form-group" ] [
                           label [ for "bundleName", class "col-sm-12 control-label"] [ text "Technique ID" ]
                         , div [ class "col-md-8" ] [
                             input [ readonly True,  id "bundleName", name "bundle_name", class ("form-control" ++ classErrorInputId), value technique.id.value ] [] -- bundlename ng-model="selectedTechnique.bundle_name" ng-maxlength="252" ng-pattern="/^[^_].*$/">
                           ]
                         , case ui.idState of
                             InvalidState errors -> ul []
                                                      (List.map (
                                                        \err -> case err of
                                                          TooLongId -> li [ class "text-danger col-md-8" ] [text "Technique IDs longer than 255 characters won't work on most filesystems." ]
                                                          AlreadyTakenId -> li [ class "text-danger col-md-8" ] [ text "Technique ID must be unique." ]
                                                      ) errors)
                             _ -> if String.length technique.id.value > 100 then
                                    span [ class "rudder-text-warning col-md-8 col-md-offset-3" ] [
                                      b [] [ span [ class "fa fa-exclamation-triangle" ] [] ]
                                    , text "Technique IDs longer than 100 characters may not work on some filesystems (Windows, in particular)."
                                    ]
                                  else text ""
                         ]
                       , div [ class "row form-group" ] [
                           label [ for "category", class "col-sm-12 control-label"][ text "Category" ]
                         , div [ class "col-md-8" ] [
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
            List.map (\p -> techniqueParameter model technique p ) technique.parameters
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
              div [ class "resources-uncommitted" ] [
                span [] [
                  i [ class "fa fa-exclamation-triangle me-2"] []
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
    Output ->
      case technique.output of
        Nothing -> text ""
        Just out ->
         div [ class "tab tab-general" ] [
           div [ class "row form-group" ] [
             label [ class "col-sm-12 control-label" ] [ text "Compiled by"  ]
           , div  [ class "col-md-8" ] [
               input [readonly True, type_ "text", class "form-control", value out.compiler ] []
             ]
           ]
         , div [ class "row form-group" ] [
             label [ class "col-sm-12 control-label" ] [ text "Result code"  ]
           , div  [ class "col-md-8" ] [
               input [readonly True, type_ "text", class "form-control", value (String.fromInt out.resultCode) ] []
             ]
           ]
         , if String.Extra.isBlank out.msg then
             text ""
           else
             div [ class "row form-group" ] [
               label [ class "col-sm-12 control-label" ] [ text "Message"  ]
             ,  div  [ class "col-sm-12" ] [
                  pre [class "command-output pre-scrollable"] [  text out.msg ]
                ]
             ]
         , if String.Extra.isBlank out.stdout then
             text ""
           else
             div [ class "row form-group" ] [
               label [ class "col-sm-12 control-label" ] [ text "Standard out"  ]
             , div  [ class "col-sm-12" ] [
                 pre [class "command-output pre-scrollable"] [ text out.stdout ]
               ]
             ]
         , if String.Extra.isBlank out.stderr then
             text ""
           else
             div [ class "row form-group" ] [
               label [ class "col-sm-12 control-label" ] [ text "Error out"  ]
             , div  [ class "col-sm-12" ] [
                 pre [class "command-output pre-scrollable"] [ text out.stderr ]
               ]
             ]
         ]
