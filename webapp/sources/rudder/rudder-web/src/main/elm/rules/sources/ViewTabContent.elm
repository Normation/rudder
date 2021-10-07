module ViewTabContent exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h4, ul, li, input, a, p, form, label, textarea, select, option, table, thead, tbody, tr, th, td, small)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import Maybe.Extra
import String exposing ( fromFloat)
import NaturalOrdering exposing (compareOn)
import ApiCalls exposing (..)
import ComplianceUtils exposing (buildComplianceBar, getRuleCompliance, getDirectiveComputedCompliance, toNodeCompliance)
import ViewUtils exposing (thClass, sortTable, getDirectivesSortFunction, getNodesSortFunction, filterSearch, searchFieldRules, searchFieldDirectives, searchFieldGroups, searchFieldNodes, buildTagsList, buildTagsTree, badgePolicyMode)


--
-- This file contains all methods to display the details of the selected rule.
--
buildListCategories : String -> (Category Rule) -> List(Html Msg)
buildListCategories sep c =
  let
    newList =
      let
        currentOption  = [option [value c.id][text (sep ++ c.name)]]
        separator      = sep ++ "└─ "
        listCategories = List.concatMap (buildListCategories separator) (getSubElems c)
      in
        List.append currentOption listCategories
  in
    newList

buildTagsContainer : Rule -> Bool -> RuleDetails -> Html Msg
buildTagsContainer rule editMode details =
  let
    tagsList = List.map (\t ->
      div [class "btn-group btn-group-xs delete-action"]
        [ button [class "btn btn-default tags-label", type_ "button"]
          [ i[class "fa fa-tag"][]
          , span[class "tag-key"][text t.key]
          , span[class "tag-separator"][text "="]
          , span[class "tag-value"][text t.value]
          ]
        , (if editMode then
            button [class "btn btn-default btn-delete", type_ "button", onClick  (UpdateRuleForm {details | rule = {rule |  tags = List.Extra.remove t rule.tags }})]
            [ span [class "fa fa-times text-danger"][] ]
          else
            text ""
          )
        ]
      ) rule.tags
  in
    div [class "tags-container form-group"](tagsList)

tabContent: Model -> RuleDetails  -> Html Msg
tabContent model details =
  let
      isNewRule = Maybe.Extra.isNothing details.originRule
      rule       = details.rule
      ui = details.ui
      newTag     = ui.newTag

  in
    case details.tab of
      Information   ->
        let
          rightCol = if isNewRule then
              div [class "col-xs-12 col-sm-6 col-lg-5"]
              [ div [class "callout-fade callout-info"]
                [ div [class "marker"][span [class "glyphicon glyphicon-info-sign"][]]
                , div []
                  [ p[][text "You are creating a new rule. You may already want to apply directives and groups to it."]
                  , p[][text "To do so, please go to their corresponding tab, or use the shortcuts below:"]
                  , div[class "action-btn"]
                    [ button [class "btn btn-default", onClick (UpdateRuleForm {details | ui = { ui | editDirectives = True }})][text "Select directives", span[class "fa fa-plus"][]]
                    , button [class "btn btn-default", onClick (UpdateRuleForm {details | ui = { ui | editGroups     = True }}    )][text "Select groups"    , span[class "fa fa-plus"][]]
                    ]
                  ]
                ]
              ]
            else
              text ""

          getCategoryName : String -> String
          getCategoryName cId =
            let
              concatCategories : (Category a) -> List (Category a)
              concatCategories c =
                c :: (List.concatMap concatCategories (getSubElems c))

              findCategory = List.Extra.find (\c -> c.id == cId) (concatCategories model.rulesTree)
            in
              case findCategory of
                Just c  -> c.name
                Nothing -> "Category not found"

          ruleForm =
            ( if model.ui.hasWriteRights then
              form[class "col-xs-12 col-sm-6 col-lg-7"]
              [ div [class "form-group"]
                [ label[for "rule-name"][text "Name"]
                , div[]
                  [ input[ id "rule-name", type_ "text", value rule.name, class "form-control", onInput (\s -> UpdateRuleForm {details | rule = {rule | name = s}} ) ][] ]
                ]
              , div [class "form-group"]
                [ label[for "rule-category"][text "Category"]
                , div[]
                  [ select[ id "rule-category", class "form-control", onInput (\s -> UpdateRuleForm {details | rule = {rule | categoryId = s}} ) ]
                    (buildListCategories  "" model.rulesTree)
                  ]
                ]
              , div [class "tags-container"]
                [ label[for "rule-tags-key"][text "Tags"]
                , div[class "form-group"]
                  [ div[class "input-group"]
                    [ input[ id "rule-tags-key", type_ "text", placeholder "key", class "form-control", onInput (\s -> UpdateRuleForm {details | ui = {ui | newTag = {newTag | key = s}}} ), value newTag.key][]
                    , span [ class "input-group-addon addon-json"][ text "=" ]
                    , input[ type_ "text", placeholder "value", class "form-control", onInput (\s -> UpdateRuleForm {details | ui = {ui | newTag = {newTag | value = s}}}), value newTag.value][]
                    , span [ class "input-group-btn"][ button [ class "btn btn-success", type_ "button", onClick  (UpdateRuleForm {details | rule = {rule |  tags = newTag :: rule.tags }, ui = {ui | newTag = Tag "" ""}}), disabled (String.isEmpty details.ui.newTag.key || String.isEmpty details.ui.newTag.value) ][ span[class "fa fa-plus"][]] ]
                    ]
                  ]
                , buildTagsContainer rule True details
                ]
              , div [class "form-group"]
                [ label[for "rule-short-description"][text "Short description"]
                , div[]
                  [ input[ id "rule-short-description", type_ "text", value rule.shortDescription, placeholder "There is no short description", class "form-control", onInput (\s -> UpdateRuleForm {details | rule = {rule | shortDescription = s}} )  ][] ]
                ]
              , div [class "form-group"]
                [ label[for "rule-long-description"][text "Long description"]
                , div[]
                  [ textarea[ id "rule-long-description", value rule.longDescription, placeholder "There is no long description", class "form-control", onInput (\s -> UpdateRuleForm {details | rule = {rule | longDescription = s}} ) ][] ]
                ]
              ]
              else
              form[class "col-xs-12 col-sm-6 col-lg-7 readonly-form"]
              [ div [class "form-group"]
                [ label[for "rule-name"][text "Name"]
                , div[][text rule.name]
                ]
              , div [class "form-group"]
                [ label[for "rule-category"][text "Category"]
                , div[][text (getCategoryName rule.categoryId), span[class "half-opacity"][text (" (id: "++ rule.categoryId ++")")]]
                ]
              , div [class "tags-container"]
                [ label[for "rule-tags-key"][text "Tags"]
                , ( if List.length rule.tags > 0 then
                    buildTagsContainer rule False details
                  else
                    div[class "half-opacity"][text "There is no tags"]
                  )
                ]
              , div [class "form-group"]
                [ label[for "rule-short-description"][text "Short description"]
                , div[]
                  ( if String.isEmpty rule.shortDescription then
                    [ span[class "half-opacity"][text "There is no short description"] ]
                  else
                    [ text rule.shortDescription ]
                  )
                ]
              , div [class "form-group"]
                [ label[for "rule-long-description"][text "Long description"]
                , div[]
                  ( if String.isEmpty rule.longDescription then
                    [ span[class "half-opacity"][text "There is no long description"] ]
                  else
                    [ text rule.longDescription ]
                  )
                ]
              ]
            )
        in
          div[class "row"]
          [ ruleForm
          , rightCol
          ]
      Directives ->
        let
          buildTableRow : Directive -> Html Msg
          buildTableRow d =
            let
              compliance = case List.Extra.find (\c -> c.ruleId == rule.id) model.rulesCompliance of
                Just co ->
                  case List.Extra.find (\dir -> dir.directiveId == d.id) co.directives of
                    Just com -> buildComplianceBar com.complianceDetails
                    Nothing  -> text "No report"
                Nothing -> text "No report"
            in
              tr[]
              [ td[]
                [ badgePolicyMode model.policyMode d.policyMode
                , text d.displayName
                ]
              , td[][compliance]
              ]

          buildListRow : List DirectiveId -> List (Html Msg)
          buildListRow ids =
            let
              --Get more information about directives, to correctly sort them by displayName
              directives =
                let
                  knownDirectives = model.directives
                    |> List.filter (\d -> List.member d.id ids)
                    |> List.sortWith (compareOn .displayName)

                  -- add missing directives
                  knonwIds = List.map .id knownDirectives
                in
                  List.append
                    knownDirectives
                    ( ids
                      |> List.filter (\id -> not (List.member id knonwIds) )
                      |> List.map (\id -> (Directive id ("Missing directive with ID "++id.value) "" "" "" False False "" []))
                    )

              rowDirective  : Directive -> Html Msg
              rowDirective directive =
                li[]
                [ a[href ("/rudder/secure/configurationManager/directiveManagement#" ++ directive.id.value)]
                  [ badgePolicyMode model.policyMode directive.policyMode
                  , span [class "target-name"][text directive.displayName]
                  , buildTagsList directive.tags
                  ]
                , span [class "target-remove", onClick (UpdateRuleForm {details | rule = {rule | directives = List.Extra.remove directive.id rule.directives}})][ i [class "fa fa-times"][] ]
                , span [class "border"][]
                ]
            in
                List.map rowDirective directives

          sortedDirectives = model.directives
            |> List.filter (\d -> List.member d.id rule.directives && (filterSearch model.ui.directiveFilters.tableFilters.filter (searchFieldDirectives d)))
            |> List.sortWith (getDirectivesSortFunction model.rulesCompliance rule.id model.ui.directiveFilters.tableFilters)

        in

          if not details.ui.editDirectives then
            div[class "tab-table-content"]
            [ div [class "table-title"]
              [ h4 [][text "Compliance by directives"]
              , ( if model.ui.hasWriteRights then
                  button [class "btn btn-default btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editDirectives = True }})][text "Select directives", i[class "fa fa-plus-circle"][]]
                else
                  text ""
                )
              ]
            , div [class "table-header"]
              [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value model.ui.directiveFilters.tableFilters.filter
              , onInput (\s ->
                let
                  directiveFilters = model.ui.directiveFilters
                  tableFilters = directiveFilters.tableFilters
                in
                  UpdateDirectiveFilters {directiveFilters | tableFilters = {tableFilters | filter = s}}
              )][]
              , button [class "btn btn-primary btn-sm"][text "Refresh"]
              ]
            , div[class "table-container"]
              [ table [class "dataTable"]
                [ thead[]
                  [ tr[class "head"]
                    [ th [class (thClass model.ui.directiveFilters.tableFilters Name      ), onClick (UpdateDirectiveFilters (sortTable model.ui.directiveFilters Name       ))][text "Directive" ]
                    , th [class (thClass model.ui.directiveFilters.tableFilters Compliance), onClick (UpdateDirectiveFilters (sortTable model.ui.directiveFilters Compliance ))][text "Compliance"]
                    ]
                  ]
                , tbody[]
                  ( if(List.length sortedDirectives > 0) then
                      List.map buildTableRow sortedDirectives
                    else
                      [ tr[]
                        [ td[colspan 2, class "dataTables_empty"][text "There is no directive applied"]
                        ]
                      ]
                  )
                ]
              ]
            ]

          else
            let
              addDirectives : DirectiveId -> Msg
              addDirectives id =
                let
                  newDirectives =
                    if List.Extra.notMember id rule.directives then
                      id :: rule.directives
                    else
                      List.Extra.remove id rule.directives
                in
                  UpdateRuleForm {details | rule = {rule | directives = newDirectives} }

              directiveTreeElem : Technique -> Maybe (Html Msg)
              directiveTreeElem item =
                let
                  directivesList = item.directives
                    |> List.filter (\d -> (filterSearch model.ui.directiveFilters.treeFilters.filter (searchFieldDirectives d)))
                    |> List.sortBy .displayName
                    |> List.map  (\d ->
                      let
                        selectedClass = if (List.member d.id rule.directives) then " item-selected" else ""
                      in
                        li [class "jstree-node jstree-leaf"]
                        [ i[class "jstree-icon jstree-ocl"][]
                        , a[href "#", class ("jstree-anchor" ++ selectedClass)]
                          [ badgePolicyMode model.policyMode d.policyMode
                          , span [class "treeGroupName tooltipable"][text d.displayName]
                          , buildTagsTree d.tags
                          , div [class "treeActions-container"]
                            [ span [class "treeActions"][ span [class "tooltipable fa action-icon accept", onClick (addDirectives d.id)][]]
                            ]
                          ]
                        ])
                in

                  if not (List.isEmpty directivesList) then
                    Just(li [class "jstree-node jstree-open"]
                    [ i[class "jstree-icon jstree-ocl"][]
                    , a[href "#", class "jstree-anchor"]
                      [ i [class "jstree-icon jstree-themeicon fa fa-gear jstree-themeicon-custom"][]
                      , span [class "treeGroupName tooltipable"][text item.name]
                      ]
                    , ul[class "jstree-children"](directivesList)
                    ])
                  else
                    Nothing

              directiveTreeCategory : Category Technique -> Maybe (Html Msg)
              directiveTreeCategory item =
                let
                  categories = getSubElems item
                    |> List.sortBy .name
                    |> List.filterMap directiveTreeCategory

                  techniques = item.elems
                    |> List.filterMap directiveTreeElem

                  children = techniques ++ categories
                in
                  if not (List.isEmpty children) then
                    Just (li[class "jstree-node jstree-open"]
                    [ i[class "jstree-icon jstree-ocl"][]
                    , a[href "#", class "jstree-anchor"]
                      [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
                      , span [class "treeGroupCategoryName tooltipable"][text item.name]
                      ]
                    , ul[class "jstree-children"] children
                    ])
                  else
                    Nothing

              (noChange, cancelDirectives) = case details.originRule of
                Just oR -> (rule.directives == oR.directives, oR.directives)
                Nothing -> (rule.directives == [], [])

              cancelBtn =
                if noChange then
                  text ""
                else
                  button[class "btn btn-default btn-icon", onClick (UpdateRuleForm { details | rule = {rule | directives = cancelDirectives} })]
                  [text "Cancel", i[class "fa fa-undo-alt"][]]

            in
              div[class "row flex-container"]
              [ div[class "list-edit col-xs-12 col-sm-6 col-lg-7"]
                [ div[class "list-container"]
                  [ div[class "list-heading"]
                    [ h4[][text "Apply these directives"]
                    , div [class "btn-actions"]
                      [ cancelBtn
                      , button[class "btn btn-default btn-icon", onClick ( UpdateRuleForm { details | ui = {ui | editDirectives = False}} )][text "Close", i[class "fa fa-times"][]]
                      , button[class "btn btn-success btn-icon", onClick ( CallApi (saveRuleDetails rule isNewRule))][text "Save", i[class "fa fa-download"][]]
                      ]
                    ]
                  , ul[class "directives applied-list"]
                    ( if(List.length rule.directives > 0) then
                       (buildListRow rule.directives)
                      else
                       [ li [class "empty"]
                         [ span [] [text "There is no directive applied."]
                         , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                         ]
                       ]
                    )
                  ]
                ]
                  , div [class "tree-edit col-xs-12 col-sm-6 col-lg-5"]
                    [ div [class "tree-container"]
                      [ div [class "tree-heading"]
                        [ h4 []
                          [ i [class "fa fa-check"][]
                          , text "Select directives"
                          ]
                        , i [class "fa fa-bars"][]
                        ]
                      , div [class "header-filter"]
                        [ div [class "input-group"]
                          [ div [class "input-group-btn"]
                            [ button [class "btn btn-default", type_ "button"][span [class "fa fa-folder fa-folder-open"][]]
                            ]
                          , input[type_ "text", placeholder "Filter", class "form-control"
                            , onInput (\s ->
                              let
                                directiveFilters = model.ui.directiveFilters
                                treeFilters = directiveFilters.treeFilters
                              in
                                UpdateDirectiveFilters {directiveFilters | treeFilters = {treeFilters | filter = s}}
                            )][]
                          , div [class "input-group-btn"]
                            [ button [class "btn btn-default", type_ "button"
                            , onClick (
                              let
                                directiveFilters = model.ui.directiveFilters
                                treeFilters = directiveFilters.treeFilters
                              in
                                UpdateDirectiveFilters {directiveFilters | treeFilters = {treeFilters | filter = ""}}
                            )]
                              [span [class "fa fa-times"][]]
                            ]
                          ]
                        ]
                      , div [class "jstree jstree-default"]
                        [ ul[class "jstree-container-ul jstree-children"]
                          [(case directiveTreeCategory model.techniquesTree of
                            Just html -> html
                            Nothing   -> div [class "alert alert-warning"]
                              [ i [class "fa fa-exclamation-triangle"][]
                              , text  "No directives match your filter."
                              ]
                          )]
                        ]
                      ]
                    ]
                  ]
      Groups        ->
        let
          buildIncludeList : Bool -> RuleTarget -> Html Msg
          buildIncludeList includeBool ruleTarget =
            let
              groupsList = getAllElems model.groupsTree

              id = case ruleTarget of
                NodeGroupId groupId -> groupId
                Composition _ _ -> "compo"
                Special spe -> spe
                Node node -> node
                Or _ -> "or"
                And _ -> "and"

              groupName = case List.Extra.find (\g -> g.id == id) groupsList of
                Just gr -> gr.name
                Nothing -> id

              rowIncludeGroup = li[]
                [ span[class "fa fa-sitemap"][]
                , a[href ("/rudder/secure/configurationManager/#" ++ "")]
                  [ span [class "target-name"][text groupName]
                  ]
                , span [class "target-remove", onClick (SelectGroup (NodeGroupId id) includeBool)][ i [class "fa fa-times"][] ]
                , span [class "border"][]
                ]
            in
              rowIncludeGroup

          buildNodesTable : RuleId -> List (Html Msg)
          buildNodesTable rId =
            let
              ruleCompliance = getRuleCompliance model rId
              nodesList = case ruleCompliance of
                Nothing -> [tr[][td[colspan 2, class "dataTables_empty"][text "This rule is not applied on any node"]]]
                Just rc ->
                  let
                    nodeItem : NodeComplianceByNode -> Html Msg
                    nodeItem node =
                      let
                        nodeInfo = List.Extra.find (\n -> n.id == node.nodeId.value) model.nodes
                        nodeName = case nodeInfo of
                          Just nn -> nn.hostname
                          Nothing -> "Cannot find node details"
                      in
                        tr[]
                        [ td[][ text nodeName ]
                        , td[][ buildComplianceBar node.complianceDetails ] -- Here goes the compliance bar
                        ]
                    nodesCompliance = toNodeCompliance rc

                    sortedNodes = nodesCompliance.nodes
                      |> List.filter (\n -> filterSearch model.ui.groupFilters.tableFilters.filter (searchFieldNodes n model.nodes))
                      |> List.sortWith (getNodesSortFunction model.ui.groupFilters.tableFilters model.nodes)
                      |> List.map nodeItem
                  in
                    sortedNodes

            in
              nodesList
        in

          if not details.ui.editGroups then
            div[class "tab-table-content"]
            [ div [class "table-title"]
              [ h4 [][text "Compliance by Nodes"]
              , ( if model.ui.hasWriteRights then
                  button [class "btn btn-default btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editGroups = True}})][text "Select targets", i[class "fa fa-plus-circle"][]]
                else
                  text ""
                )
              ]
            , div [class "table-header"]
              [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value model.ui.groupFilters.tableFilters.filter
                , onInput (\s ->
                  let
                    groupFilters = model.ui.groupFilters
                    tableFilters = groupFilters.tableFilters
                  in
                    UpdateGroupFilters {groupFilters | tableFilters = {tableFilters | filter = s}}
                )][]
              , button [class "btn btn-primary btn-sm"][text "Refresh"]
              ]
            , div[class "table-container"]
              [ table [class "dataTable"]
                [ thead[]
                  [ tr[class "head"]
                    [ th [class (thClass model.ui.groupFilters.tableFilters Name       ), onClick (UpdateGroupFilters (sortTable model.ui.groupFilters Name       ))][text "Node"      ]
                    , th [class (thClass model.ui.groupFilters.tableFilters Compliance ), onClick (UpdateGroupFilters (sortTable model.ui.groupFilters Compliance ))][text "Compliance"]
                    ]
                  ]
                , tbody[]
                  (buildNodesTable details.rule.id)
                ]
              ]
            ]

          else
            let
              groupTreeElem : Group -> Html Msg
              groupTreeElem item =
                let
                  checkIncludeOrExclude : List RuleTarget -> Bool
                  checkIncludeOrExclude lst =
                    let
                      getTargetIds : List RuleTarget -> List String
                      getTargetIds listTargets =
                        List.concatMap (\target -> case target of
                           NodeGroupId groupId -> [groupId]
                           Composition i e ->
                             let
                               included = getTargetIds[ i ]
                               excluded = getTargetIds[ e ]
                             in
                               included |>
                                 List.filter (\t -> List.member t excluded)
                           Special spe -> [spe]
                           Node node -> [node]
                           Or t  -> getTargetIds t
                           And t -> getTargetIds t -- TODO : Need to be improved - we need to keep targets that are member of all targets list in t
                        ) listTargets
                    in
                      List.member item.id (getTargetIds lst)

                  includeClass =
                    if checkIncludeOrExclude includedTargets then " item-selected"
                    else if checkIncludeOrExclude excludedTargets then " item-selected excluded"
                    else ""

                in
                  li [class "jstree-node jstree-leaf"]
                  [ i[class "jstree-icon jstree-ocl"][]
                  , a[href "#", class ("jstree-anchor" ++ includeClass)]
                    [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
                    , span [class "treeGroupName tooltipable"][text item.name, (if item.dynamic then (small [class "greyscala"][text "- Dynamic"]) else (text ""))]
                    , div [class "treeActions-container"]
                      [ span [class "treeActions"][ span [class "tooltipable fa action-icon accept", onClick (SelectGroup (NodeGroupId item.id) True)][]]
                      , span [class "treeActions"][ span [class "tooltipable fa action-icon except", onClick (SelectGroup (NodeGroupId item.id) False)][]]
                      ]
                    ]
                  ]

              groupTreeCategory : Category Group -> Maybe (Html Msg)
              groupTreeCategory item =
                let
                  categories = getSubElems item
                    |> List.sortBy .name
                    |> List.filterMap groupTreeCategory

                  groups = item.elems
                    |> List.filter (\g -> (filterSearch model.ui.groupFilters.treeFilters.filter (searchFieldGroups g)))
                    |> List.sortBy .name
                    |> List.map groupTreeElem

                  children  = categories ++ groups

                in
                  if not (List.isEmpty children) then
                    Just (li[class "jstree-node jstree-open"]
                    [ i[class "jstree-icon jstree-ocl"][]
                    , a[href "#", class "jstree-anchor"]
                      [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
                      , span [class "treeGroupCategoryName tooltipable"][text item.name]
                      ]
                    , ul[class "jstree-children"](children)
                    ])
                  else
                    Nothing

              (includedTargets, excludedTargets) =
                case rule.targets of
                  [Composition (Or include) (Or exclude)] -> (include, exclude)
                  _ -> (rule.targets, [])

              (noChange, cancelTargets) = case details.originRule of
                Just oR -> (rule.targets == oR.targets, oR.targets)
                Nothing -> (rule.targets == [], [])

              cancelBtn =
                if noChange then
                  text ""
                else
                  button[class "btn btn-default btn-icon", onClick (UpdateRuleForm { details | rule = {rule | targets = cancelTargets} })]
                  [text "Cancel", i[class "fa fa-undo-alt"][]]

            in
              div[class "row flex-container"]
              [ div[class "list-edit col-xs-12 col-sm-6 col-lg-7"]
                [ div[class "list-container"]
                  [ div[class "list-heading"]
                    [ h4[][text "Apply to Nodes in any of these Groups"]
                    , div [class "btn-actions"]
                      [ cancelBtn
                      , button[class "btn btn-default btn-icon"  , onClick (UpdateRuleForm {details | ui = {ui | editGroups = False}} )]
                        [text "Close", i[class "fa fa-times"][]]
                      , button[class "btn btn-success btn-icon", onClick (CallApi (saveRuleDetails rule isNewRule))]
                        [text "Save", i[class "fa fa-download"][]]
                      ]
                    ]
                  , ul[class "groups applied-list"]
                    ( if(List.isEmpty includedTargets ) then
                      [ li [class "empty"]
                        [ span [] [text "There is no group included."]
                        , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                        ]
                      ]
                    else
                      List.map (buildIncludeList True) includedTargets
                    )
                  ]
                , div[class "list-container"]
                  [ div[class "list-heading except"]
                    [ h4[][text "Except to Nodes in any of these Groups"]
                    ]
                  , ul[class "groups applied-list"]
                    ( if(List.isEmpty excludedTargets) then
                      [ li [class "empty"]
                        [ span [] [text "There is no group excluded."]
                        , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                        ]
                      ]
                    else
                      List.map (buildIncludeList False) excludedTargets
                    )
                  ]
                ]
              , div [class "tree-edit col-xs-12 col-sm-6 col-lg-5"]
                [ div [class "tree-container"]
                  [ div [class "tree-heading"]
                    [ h4 []
                      [ i [class "fa fa-check"][]
                      , text "Select groups"
                      ]
                    , i [class "fa fa-bars"][]
                    ]
                    , div [class "header-filter"]
                      [ div [class "input-group"]
                        [ div [class "input-group-btn"]
                          [ button [class "btn btn-default", type_ "button"][span [class "fa fa-folder fa-folder-open"][]]
                          ]
                        , input [type_ "text", placeholder "Filter", class "form-control", value model.ui.groupFilters.treeFilters.filter
                          , onInput (\s ->
                            let
                              groupFilters = model.ui.groupFilters
                              treeFilters = groupFilters.treeFilters
                            in
                              UpdateGroupFilters {groupFilters | treeFilters = {treeFilters | filter = s}}
                          )][]
                        , div [class "input-group-btn"]
                          [ button [class "btn btn-default", type_ "button"
                          , onClick (
                            let
                              groupFilters = model.ui.groupFilters
                              treeFilters = groupFilters.treeFilters
                            in
                              UpdateGroupFilters {groupFilters | treeFilters = {treeFilters | filter = ""}}
                          )]
                            [span [class "fa fa-times"][]]
                          ]
                        ]
                      ]
                  , div [class "jstree jstree-default"]
                    [ ul[class "jstree-container-ul jstree-children"]
                      [(case groupTreeCategory model.groupsTree of
                        Just html -> html
                        Nothing   -> div [class "alert alert-warning"]
                          [ i [class "fa fa-exclamation-triangle"][]
                          , text  "No groups match your filter."
                          ]
                      )]
                    ]
                  ]
                ]
              ]
      TechnicalLogs ->
        div[][text "Technical Logs"]
