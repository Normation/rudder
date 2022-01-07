module ViewTabContent exposing (..)

import DataTypes exposing (..)
import Dict
import Dict.Extra
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput, onSubmit)
import List.Extra
import List
import Maybe.Extra
import Set
import NaturalOrdering exposing (compareOn)
import ComplianceUtils exposing (..)
import Tuple3
import ViewRepairedReports
import ViewUtils exposing (..)


--
-- This file contains all methods to display the details of the selected rule.
--
buildListCategories : String -> String -> String -> (Category Rule) -> List(Html Msg)
buildListCategories sep categoryId parentId c =
  let
    missingRootCategory = List.filter (\sub -> sub.id /= missingCategoryId) (getSubElems c)
  in
  if categoryId == c.id then
    []
  else
    let
      newList =
        let
          blankSpace     = String.repeat 2 (String.fromChar (Char.fromCode 8199))
          currentOption  = [option [value c.id, selected (parentId == c.id)][text (sep ++ c.name)]]
          separator      =
            if String.isEmpty sep then
              "└─ "
            else
               blankSpace ++ sep

          listCategories = List.concatMap (buildListCategories separator categoryId parentId) missingRootCategory
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

informationTab: Model -> RuleDetails  -> Html Msg
informationTab model details =

  let
    isNewRule = Maybe.Extra.isNothing details.originRule
    rule       = details.rule
    ui = details.ui
    newTag     = ui.newTag
    compliance =
      case getRuleCompliance model rule.id of
       Just co ->
          buildComplianceBar co.complianceDetails
       Nothing -> text "No report"
    rightCol =
      if isNewRule then
        div [class "callout-fade callout-info"]
        [ div [class "marker"][span [class "glyphicon glyphicon-info-sign"][]]
        , div []
          [ p[][text "You are creating a new rule. You may already want to apply directives and groups to it."]
          , p[][text "To do so, please go to their corresponding tab, or use the shortcuts below:"]
          , div[class "action-btn"]
            [ button [class "btn btn-default", onClick (UpdateRuleForm {details | ui = { ui | editDirectives = True }, tab = Directives})][text "Select directives", span[class "fa fa-plus"][]]
            , button [class "btn btn-default", onClick (UpdateRuleForm {details | ui = { ui | editGroups     = True }, tab = Groups    })][text "Select groups"    , span[class "fa fa-plus"][]]
            ]
          ]
        ]
      else
        div [class "form-group show-compliance"]
        [ label[][text "Compliance"]
        , compliance
        ]
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
    allMissingCategoriesRulesId = List.map (\r -> r.id.value) (getAllMissingCatsRules model.rulesTree)
    originRuleMissingCatId = case details.originRule of
      Just oR -> oR.categoryId
      Nothing -> "<Error: category ID not found>"
    isMissingCatRule = List.member rule.id.value allMissingCategoriesRulesId
    defaultOptForMissingCatRule =
      if(isMissingCatRule) then
        option [disabled True, selected True][text "-- select a category --"]
      else
        div[][]
    msgMissingCat =
      if(isMissingCatRule) then
        div [class "msg-missing-cat callout-fade callout-warning"]
        [
          text "This rule is in a missing category which has for ID: "
        , b [] [text  originRuleMissingCatId]
        , br [][]
        , text "Please move this rule under a valid category"
        ]
      else
        div [][]
    ruleForm =
      ( if model.ui.hasWriteRights then
        Html.form[class "col-xs-12 col-sm-6 col-lg-7"]
        [ div [class "form-group"]
          [ label[for "rule-name"][text "Name"]
          , div[]
            [ input[ id "rule-name", type_ "text", value rule.name, class "form-control", onInput (\s -> UpdateRuleForm {details | rule = {rule | name = s}} ) ][] ]
          ]
        , div [class "form-group"]
          [ label[for "rule-category"][text "Category"]
          , msgMissingCat
          , div[]
            [ select[ id "rule-category", class "form-control", onInput (\s -> UpdateRuleForm {details | rule = {rule | categoryId = s}} ) ]
               ( defaultOptForMissingCatRule :: (buildListCategories "" "" rule.categoryId model.rulesTree ))
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
        Html.form [class "col-xs-12 col-sm-6 col-lg-7 readonly-form"]
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
    , div [class "col-xs-12 col-sm-6 col-lg-5"][ rightCol ]
    ]

tabContent: Model -> RuleDetails  -> Html Msg
tabContent model details =
    case details.tab of
      Information   -> informationTab model details
      Directives    -> directivesTab model details
      Nodes         -> nodesTab model details
      Groups        -> groupsTab model details
      TechnicalLogs -> ViewRepairedReports.showTab model details

directivesTab: Model -> RuleDetails -> Html Msg
directivesTab model details =
  let
    isNewRule = Maybe.Extra.isNothing details.originRule
    rule       = details.rule
    ui = details.ui
    buildListRow : List DirectiveId -> List (Html Msg)
    buildListRow ids =
      let
        --Get more information about directives, to correctly sort them by displayName
        directives =
          let
            knownDirectives = Dict.Extra.keepOnly (Set.fromList (List.map .value ids)) model.directives
              |> Dict.values
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
          [ a[href ( model.contextPath ++ "/secure/configurationManager/directiveManagement#" ++ directive.id.value)]
            [ badgePolicyMode model.policyMode directive.policyMode
            , span [class "target-name"][text directive.displayName]
            , buildTagsList directive.tags
            ]
          , span [class "target-remove", onClick (UpdateRuleForm {details | rule = {rule | directives = List.Extra.remove directive.id rule.directives}})][ i [class "fa fa-times"][] ]
          , span [class "border"][]
          ]
      in
        List.map rowDirective directives

    nbDirectives = case details.originRule of
      Just oR -> List.length oR.directives
      Nothing -> 0
    fun = byDirectiveCompliance model.policyMode nodeValueCompliance
    directiveRows = List.map Tuple3.first fun.rows
    rowId = "byDirectives/"
    (sortId, sortOrder) = Dict.get rowId ui.openedRows |> Maybe.withDefault ("Directive",Asc)
    sort =   case List.Extra.find (Tuple3.first >> (==) sortId) fun.rows of
      Just (_,_,sortFun) -> (\i1 i2 -> sortFun (fun.data model i1) (fun.data model i2))
      Nothing -> (\_ _ -> EQ)
    filter       = model.ui.directiveFilters.tableFilters.filter
    childs       = Maybe.withDefault [] (Maybe.map .directives details.compliance)
    childrenSort = childs |> List.filter (\d -> (String.contains filter d.name) || (String.contains filter d.directiveId.value) ) |> List.sortWith sort
    (directivesChildren, order, newOrder) = case sortOrder of
       Asc -> (childrenSort, "asc", Desc)
       Desc -> (List.reverse childrenSort, "desc", Asc)
  in
    if not details.ui.editDirectives then
      div[class "tab-table-content"]
      [ div [class "table-title"]
        [ h4 [][text "Compliance by directives"]
        , ( if model.ui.hasWriteRights then
            button [class "btn btn-primary btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editDirectives = True }})][text "Select ", i[class "fa fa-plus-circle"][]]
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
        , button [class "btn btn-primary btn-sm", onCustomClick Ignore][text "Refresh"]
        ]
      , div[class "table-container"] [
          table [class "dataTable compliance-table"] [
            thead [] [
              tr [ class "head" ] (List.map (\row -> th [onClick (ToggleRowSort rowId row (if row == sortId then newOrder else Asc)), class ("sorting" ++ (if row == sortId then "_"++order else ""))] [ text row ]) directiveRows)
            ]
          , tbody [] (
            if List.length childs <= 0 then
              [ tr[]
                [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "This rule is not applied on any Directive."] ]
              ]
            else if List.length directivesChildren == 0 then
              [ tr[]
                [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "No directives match your filter."] ]
              ]
            else
              List.concatMap (\d ->  showComplianceDetails fun d "" ui.openedRows model) directivesChildren
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
                  [ i [class "jstree-icon jstree-ocl"][]
                  , a [class ("jstree-anchor" ++ selectedClass)]
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
              Just(li [class ("jstree-node" ++ foldedClass model.ui.directiveFilters.treeFilters item.name)]
              [ i [class "jstree-icon jstree-ocl", onClick (UpdateDirectiveFilters (foldUnfoldCategory model.ui.directiveFilters item.name))][]
              , a [class "jstree-anchor"]
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
              Just (li[class ("jstree-node" ++ foldedClass model.ui.directiveFilters.treeFilters item.id)]
              [ i [class "jstree-icon jstree-ocl", onClick (UpdateDirectiveFilters (foldUnfoldCategory model.ui.directiveFilters item.name))][]
              , a [class "jstree-anchor"]
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
      in
        div[class "row flex-container"]
        [ div[class "list-edit col-xs-12 col-sm-6 col-lg-7"]
          [ div[class "list-container"]
            [ div[class "list-heading"]
              [ h4[][text "Apply these directives"]
              , div [class "btn-actions"]
                [ button[class "btn btn-default btn-icon", onClick (UpdateRuleForm { details | rule = {rule | directives = cancelDirectives} }), disabled noChange]
                  [text "Cancel", i[class "fa fa-undo-alt"][]]
                , ( if isNewRule then
                    text ""
                  else
                    button[class "btn btn-success btn-outline btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editDirectives = False}} )][text "Confirm", i[class "fa fa-check"][]]
                )
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
                  [ h4 [][ text "Select directives" ]
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

nodesTab : Model -> RuleDetails -> Html Msg
nodesTab model details =
  let
    ui = details.ui
    fun = byNodeCompliance model.policyMode
    nodeRows =  List.map Tuple3.first fun.rows
    rowId = "byNodes/"
    (sortId, sortOrder) = Dict.get rowId ui.openedRows |> Maybe.withDefault ("Node",Asc)
    sort =   case List.Extra.find (Tuple3.first >> (==) sortId) fun.rows of
               Just (_,_,sortFun) -> (\i1 i2 -> sortFun (fun.data model i1) (fun.data model i2))
               Nothing -> (\_ _ -> EQ)
    filter = model.ui.groupFilters.tableFilters.filter
    childs       = Maybe.withDefault [] (Maybe.map .nodes details.compliance)
    childrenSort = childs |> List.filter (\d -> (String.contains filter d.name) || (String.contains filter d.nodeId.value) )  |> List.sortWith sort
    (nodesChildren, order, newOrder) = case sortOrder of
       Asc -> (childrenSort, "asc", Desc)
       Desc -> (List.reverse childrenSort, "desc", Asc)
    groupsList = getAllElems model.groupsTree
    includedTargets =
      case details.rule.targets of
        [Composition (Or include) _] -> include
        _ -> details.rule.targets
    specialTargets = includedTargets
      |> List.concatMap (\t ->
        case t of
          Special spe ->
            case List.Extra.find (\g -> g.target == spe) groupsList of
              Just gr -> [gr]
              Nothing -> []
          _ -> []
        )
    infoSpecialTarget =
      if List.isEmpty specialTargets then
        text ""
      else
        div[class "callout-fade callout-info"]
        [ text "This rule applies to some special targets: "
        , ul[]
          ( List.map (\t -> li[][b[][text t.name, text ": "], text t.description]) specialTargets )
        , text "The nodes of these targets ", strong[][text "are not displayed "], text "in the following table."
        ]

  in
    div[class "tab-table-content"]
      [ div [class "table-title"]
        [ h4 [][text "Compliance by Nodes"]
        , ( if model.ui.hasWriteRights then
            button [class "btn btn-primary btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editGroups = True}, tab = Groups})]
            [ text "Select groups", i[class "fa fa-plus-circle" ][]]
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
      , div[class "table-container"] [
          table [class "dataTable compliance-table"] [
            thead [] [
              tr [ class "head" ] (List.map (\row -> th [onClick (ToggleRowSort rowId row (if row == sortId then newOrder else Asc)), class ("sorting" ++ (if row == sortId then "_"++order else ""))] [ text row ]) nodeRows)
            ]
          , tbody []
            (
              if (List.length childs) <= 0 then
                [ tr[]
                  [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "This rule is not applied on any Node."] ]
                ]
              else if List.length nodesChildren == 0 then
                [ tr[]
                  [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "No nodes match your filter."] ]
                ]
              else
                List.concatMap (\d ->  showComplianceDetails fun d rowId ui.openedRows model)  nodesChildren
              )
          ]
        ]
      ]

groupsTab : Model -> RuleDetails -> Html Msg
groupsTab model details =
  let
    isNewRule = Maybe.Extra.isNothing details.originRule
    rule       = details.rule
    ui = details.ui
    (includedTargets, excludedTargets) =
      case rule.targets of
        [Composition (Or include) (Or exclude)] -> (include, exclude)
        _ -> (rule.targets, [])
  in
    if not details.ui.editGroups then
      div [class "row lists"]
      [ div[class "list-edit col-xs-12 col-sm-6"]
        [ div[class "list-container"]
          [ div[class "list-heading"]
            [ h4[][text "Applied to Nodes in any of these Groups"]
            , ( if model.ui.hasWriteRights then
                button [class "btn btn-primary btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editGroups = True}})]
                [ text "Select", i[class "fa fa-plus-circle" ][]]
              else
                text ""
              )
            ]
          , ul[class "groups applied-list"]
            ( if(List.isEmpty includedTargets ) then
              [ li [class "empty"]
                [ span [] [text "There is no group included."]
                , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                ]
              ]
            else
              List.map (buildIncludeList model.groupsTree model details.ui.editGroups True) includedTargets
            )
          ]
        ]
      , div[class "list-edit col-xs-12 col-sm-6"]
        [ div[class "list-container"]
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
              List.map (buildIncludeList model.groupsTree model details.ui.editGroups False) excludedTargets
            )
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
            [ i [class "jstree-icon jstree-ocl"][]
            , a [class ("jstree-anchor" ++ includeClass)]
              [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
              , span [class "treeGroupName tooltipable"][text item.name, (if item.dynamic then (small [class "greyscala"][text "- Dynamic"]) else (text ""))]
              , div [class "treeActions-container"]
                [ span [class "treeActions"][ span [class "tooltipable fa action-icon accept", onClick (SelectGroup item.target True)][]]
                , span [class "treeActions"][ span [class "tooltipable fa action-icon except", onClick (SelectGroup item.target False)][]]
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
              Just (li[class ("jstree-node" ++ foldedClass model.ui.groupFilters.treeFilters item.id)]
              [ i [class "jstree-icon jstree-ocl", onClick (UpdateGroupFilters (foldUnfoldCategory model.ui.groupFilters item.id))][]
              , a [class "jstree-anchor"]
                [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
                , span [class "treeGroupCategoryName tooltipable"][text item.name]
                ]
              , ul[class "jstree-children"](children)
              ])
            else
              Nothing
        (noChange, cancelTargets) = case details.originRule of
          Just oR -> (rule.targets == oR.targets, oR.targets)
          Nothing -> (rule.targets == [], [])
      in
        div[class "row flex-container"]
        [ div[class "list-edit col-xs-12 col-sm-6 col-lg-7"]
          [ div[class "list-container"]
            [ div[class "list-heading"]
              [ h4[][text "Apply to Nodes in any of these Groups"]
              , div [class "btn-actions"]
                [ button[class "btn btn-default btn-icon", onClick (UpdateRuleForm { details | rule = {rule | targets = cancelTargets} }), disabled noChange]
                  [text "Cancel", i[class "fa fa-undo-alt"][]]
                , ( if isNewRule then
                    text ""
                  else
                    button[class "btn btn-success btn-outline btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editGroups = False}} )][text "Confirm", i[class "fa fa-check"][]]
                )
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
                List.map (buildIncludeList model.groupsTree model details.ui.editGroups True) includedTargets
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
                List.map (buildIncludeList  model.groupsTree model details.ui.editGroups False) excludedTargets
              )
            ]
          ]
        , div [class "tree-edit col-xs-12 col-sm-6 col-lg-5"]
          [ div [class "tree-container"]
            [ div [class "tree-heading"]
              [ h4 [][ text "Select groups" ]
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
                        treeFilters  = groupFilters.treeFilters
                      in
                        UpdateGroupFilters {groupFilters | treeFilters = {treeFilters | filter = s}}
                    )][]
                  , div [class "input-group-btn"]
                    [ button [class "btn btn-default", type_ "button"
                    , onClick (
                      let
                        groupFilters = model.ui.groupFilters
                        treeFilters  = groupFilters.treeFilters
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

