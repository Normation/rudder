module Rules.ViewTabContent exposing (..)

import Dict
import Dict.Extra
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput, onCheck)
import List.Extra
import List
import Maybe.Extra
import Set
import NaturalOrdering as N exposing (compareOn, compare)
import NaturalOrdering as N exposing (compareOn, compare)
import Tuple3

import Rules.DataTypes exposing (..)
import Rules.ViewRepairedReports
import Rules.ViewUtils exposing (..)
import Compliance.DataTypes exposing (..)
import Compliance.Utils exposing (displayComplianceFilters, filterDetailsByCompliance, defaultComplianceFilter)
import Compliance.Html exposing (buildComplianceBar)


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
          buildComplianceBar defaultComplianceFilter co.complianceDetails
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
        , label[class "id-label"][text "Rule ID"]
        , div [class "id-container"]
          [ p [class "id-value", onClick (Copy rule.id.value)][text rule.id.value]
          , i [class "ion ion-clipboard clipboard-icon", onClick (Copy rule.id.value)][]
          ]
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
              , span [ class "input-group-btn"][ button [ class "btn btn-default", type_ "button", onClick  (UpdateRuleForm {details | rule = {rule |  tags = newTag :: rule.tags }, ui = {ui | newTag = Tag "" ""}}), disabled (String.isEmpty details.ui.newTag.key || String.isEmpty details.ui.newTag.value) ][ span[class "fa fa-plus"][]] ]
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
      TechnicalLogs -> Rules.ViewRepairedReports.showTab model details
      Rules         -> div [] []

directivesTab: Model -> RuleDetails -> Html Msg
directivesTab model details =
  let
    isNewRule  = Maybe.Extra.isNothing details.originRule
    rule       = details.rule
    originRule = details.originRule
    ui         = details.ui

    directiveFilters  = model.ui.directiveFilters
    tableFilters      = directiveFilters.tableFilters
    treeFilters       = directiveFilters.treeFilters
    complianceFilters = model.ui.complianceFilters

    --Get more information about directives, to correctly sort them by displayName
    directives =
      let
        knownDirectives = Dict.Extra.keepOnly (Set.fromList (List.map .value rule.directives)) model.directives
          |> Dict.values
          |> List.sortWith (compareOn .displayName)
        -- add missing directives
        knonwIds = List.map .id knownDirectives
      in
        List.append
          knownDirectives
          ( rule.directives
            |> List.filter (\id -> not (List.member id knonwIds) )
            |> List.map (\id -> (Directive id ("Missing directive with ID "++id.value) "" "" "" False False "" []))
          )
    buildListRow : List (Html Msg)
    buildListRow =
      let
        rowDirective  : Directive -> Html Msg
        rowDirective directive =
          let
            newClass = case originRule of
              Nothing -> "new"
              Just oR ->
                if List.member directive.id oR.directives then
                  ""
                else
                  "new"
            (disabledClass, disabledLabel) =
              if directive.enabled then
                ("", text "")
              else
                (" is-disabled ", span[class "badge-disabled"][])
          in
            li[class (newClass ++ disabledClass)]
            [ a[href ( getDirectiveLink model.contextPath directive.id )]
              [ badgePolicyMode model.policyMode directive.policyMode
              , span [class "target-name"][text directive.displayName]
              , disabledLabel
              , buildTagsList directive.tags
              , goToIcon
              ]
            , span [class "target-remove", onClick (UpdateRuleForm {details | rule = {rule | directives = List.Extra.remove directive.id rule.directives}})][ i [class "fa fa-times"][] ]
            , span [class "border"][]
            ]
      in
        List.map rowDirective directives

    ruleDirectivesId = case details.originRule of
      Just oR -> oR.directives
      Nothing -> []
    nbDirectives = details.numberOfDirectives
    fun = byDirectiveCompliance model complianceFilters (nodeValueCompliance model complianceFilters)
    directiveRows = List.map Tuple3.first fun.rows
    rowId = "byDirectives/"
    (sortId, sortOrder) = Dict.get rowId ui.openedRows |> Maybe.withDefault ("Directive",Asc)
    sort =   case List.Extra.find (Tuple3.first >> (==) sortId) fun.rows of
      Just (_,_,sortFun) -> (\i1 i2 -> sortFun (fun.data model i1) (fun.data model i2))
      Nothing -> (\_ _ -> EQ)
    filter       = model.ui.directiveFilters.tableFilters.filter
    childs       = Maybe.withDefault [] (Maybe.map .directives details.compliance)

    childrenSort = childs
      |> List.filter (\d -> (filterSearch model.ui.directiveFilters.tableFilters.filter (searchFieldDirectiveCompliance d)))
      |> List.filter (filterDetailsByCompliance complianceFilters)
      |> List.sortWith sort
    (directivesChildren, order, newOrder) = case sortOrder of
       Asc -> (childrenSort, "asc", Desc)
       Desc -> (List.reverse childrenSort, "desc", Asc)

    ruleDirectives = directives
      |> List.filter (\d -> List.member d.id ruleDirectivesId)

    disabledRuleDirectives = ruleDirectives
      |> List.filter (\d -> not d.enabled)

    noNodes = (Maybe.withDefault 1 (getRuleNbNodes details))  <= 0
    noNodesInfo =
      if noNodes then
        div[ class "callout-fade callout-warning"]
        [ i[class "fa fa-warning"][]
        , text "This rule is not applied on any node"
        ]
      else
        text ""
  in
    if not details.ui.editDirectives then
      div[class "tab-table-content"]
      [ div [class "table-title"]
        [ h4 [][text "Compliance by directives"]
        , ( if model.ui.hasWriteRights then
            button [class "btn btn-default btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editDirectives = True }})][text "Select ", i[class "fa fa-plus-circle"][]]
          else
            text ""
          )
        ]
      , ( if List.isEmpty disabledRuleDirectives && rule.enabled then
          text ""
        else
          let
            warningMessage = case (rule.enabled, List.isEmpty disabledRuleDirectives) of
              ( True  , False ) -> span[]
                [ text "This rule has"
                , b [class "badge"][ text (String.fromInt (List.length disabledRuleDirectives))]
                , text ("disabled directive" ++ (if List.length disabledRuleDirectives > 1 then "s" else "") ++ " that will not be applied. ")
                ]
              ( False , False ) -> span[]
                [ text "This rule is "
                , b[][text "disabled"]
                , text ", none of its directives will be applied. Plus, it has"
                , b [class "badge"][ text (String.fromInt (List.length disabledRuleDirectives))]
                , text ("disabled directive" ++ (if List.length disabledRuleDirectives > 1 then "s" else "") ++ ". ")
                ]
              ( False , True  ) -> span[][text "This rule is ", b[][text "disabled"], text ", none of its directives will be applied"]
              _ -> text ""

            (checkbox, caret, list) = (
              if List.isEmpty disabledRuleDirectives then
                ( text ""
                , text ""
                , text ""
                )
              else
                ( input[type_ "checkbox", id "disabled-directives", class "toggle-checkbox"][]
                , i[class "fa fa-caret-down"][]
                , ul[](List.map (\d -> li[]
                  [ a[ href (getDirectiveLink model.contextPath d.id) ]
                    [ badgePolicyMode model.policyMode d.policyMode
                    , text d.displayName
                    , goToIcon
                    ]
                  ]) disabledRuleDirectives)
                )
              )
          in
            div[class "toggle-checkbox-container"]
            [ checkbox
            , div[ class "callout-fade callout-warning"]
              [ label[for "disabled-directives"]
                [ i[class "fa fa-warning"][]
                , warningMessage
                , caret
                ]
              , list
              ]
            ]
        )
      , noNodesInfo
      , div [class "table-header extra-filters"]
        [ div [class "main-filters"]
          [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value model.ui.directiveFilters.tableFilters.filter
          , onInput (\s -> UpdateDirectiveFilters {directiveFilters | tableFilters = {tableFilters | filter = s}} )][]
          , button [class "btn btn-default btn-sm btn-icon", onClick (UpdateComplianceFilters {complianceFilters | showComplianceFilters = not complianceFilters.showComplianceFilters}), style "min-width" "170px"]
            [ text ((if complianceFilters.showComplianceFilters then "Hide " else "Show ") ++ "compliance filters")
            , i [class ("fa " ++ (if complianceFilters.showComplianceFilters then "fa-minus" else "fa-plus"))][]
            ]
          , button [class "btn btn-default btn-sm btn-refresh", onCustomClick (RefreshComplianceTable rule.id)][i [class "fa fa-refresh"][]]
          ]
        , displayComplianceFilters complianceFilters UpdateComplianceFilters
        ]
      , div[class "table-container"] [(
        let
          filteredDirectives = ruleDirectives
            |> List.filter (\d -> d.enabled && (filterSearch model.ui.directiveFilters.tableFilters.filter (searchFieldDirectives d)))
            |> List.sortWith (\d1 d2 -> N.compare d1.displayName d2.displayName)
          sortedDirectives   = case tableFilters.sortOrder of
            Asc  -> filteredDirectives
            Desc -> List.reverse filteredDirectives
          toggleSortOrder o = if o == Asc then Desc else Asc
        in
          if rule.enabled && not noNodes then
            table [class "dataTable compliance-table"]
            [ thead []
              [ tr [ class "head" ] (List.map (\row -> th [onClick (ToggleRowSort rowId row (if row == sortId then newOrder else Asc)), class ("sorting" ++ (if row == sortId then "_"++order else ""))] [ text row ]) directiveRows)
              ]
            , tbody [] (
              if List.length childs <= 0 then
                [ tr[]
                  [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "This rule does not apply any directive."] ]
                ]
              else if List.length directivesChildren == 0 then
                [ tr[]
                  [ td[class "empty", colspan 2][i [class"fa fa-exclamation-triangle"][], text "No directives match your filter."] ]
                ]
              else
                List.concatMap (\d ->  showComplianceDetails fun d "" ui.openedRows model) directivesChildren
              )
            ]
          else
            table [class "dataTable"]
            [ thead []
              [ tr [ class "head" ]
                [ th [onClick (UpdateDirectiveFilters {directiveFilters | tableFilters = {tableFilters | sortOrder = toggleSortOrder tableFilters.sortOrder}}), class ("sorting_" ++ (if tableFilters.sortOrder == Asc then "asc" else "desc"))] [ text "Directive" ]
                ]
              ]
            , tbody [] (
              if List.length ruleDirectives <= 0 then
                [ tr[]
                  [ td[class "empty"][i [class"fa fa-exclamation-triangle"][], text "This rule does not apply any directive."] ]
                ]
              else if List.length filteredDirectives == 0 then
                [ tr[]
                  [ td[class "empty"][i [class"fa fa-exclamation-triangle"][], text "No directives match your filter."] ]
                ]
              else
                sortedDirectives
                |> List.map (\d ->
                tr []
                [ td[]
                  [ a []
                    [ badgePolicyMode model.policyMode d.policyMode
                    , span [class "item-name"][text d.displayName]
                    , buildTagsTree d.tags
                    , div [class "treeActions-container"]
                    [ span [class "treeActions"][ span [class "fa action-icon accept"][]]
                    ]
                    , goToBtn (getDirectiveLink model.contextPath  d.id)
                    ]
                  ]
                ])
              )
            ]
        )]
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
              |> List.sortWith (\d1 d2 -> N.compare d1.displayName d2.displayName)
              |> List.map  (\d ->
                let
                  selectedClass = if (List.member d.id rule.directives) then " item-selected" else ""
                  (disabledClass, disabledLabel) =
                    if d.enabled then
                      ("", text "")
                    else
                      (" is-disabled", span[class "badge-disabled"][])

                  isUsed = (getAllElems model.rulesTree)
                    |> List.concatMap (\ r -> r.directives)
                    |> List.member d.id

                  unusedWarning =
                    if isUsed then text ""
                    else
                      span
                      [ class "fa fa-warning text-warning-rudder"
                      , attribute "data-bs-toggle" "tooltip"
                      , attribute "data-bs-placement" "bottom"
                      , title (buildTooltipContent "Unused directive" "This directive is not used in any rule")
                      ][]
                in
                  li [class ("jstree-node jstree-leaf directiveNode" ++ disabledClass)]
                  [ i [class "jstree-icon jstree-ocl"][]
                  , a [class ("jstree-anchor" ++ selectedClass), onClick (addDirectives d.id)]
                    [ badgePolicyMode model.policyMode d.policyMode
                    , unusedWarning
                    , span [class "item-name"][text d.displayName]
                    , disabledLabel
                    , buildTagsTree d.tags
                    , div [class "treeActions-container"]
                      [ span [class "treeActions"][ span [class "fa action-icon accept"][]]
                      ]
                    , goToBtn (getDirectiveLink model.contextPath d.id)
                    ]
                  ])
          in
            if not (List.isEmpty directivesList) then
              Just(li [class ("jstree-node" ++ foldedClass model.ui.directiveFilters.treeFilters item.name)]
              [ i [class "jstree-icon jstree-ocl", onClick (UpdateDirectiveFilters (foldUnfoldCategory model.ui.directiveFilters item.name))][]
              , a [class "jstree-anchor"]
                [ i [class "jstree-icon jstree-themeicon fa fa-gear jstree-themeicon-custom"][]
                , span [class "item-name"][text item.name]
                ]
              , ul[class "jstree-children"](directivesList)
              ])
            else
              Nothing
        directiveTreeCategory : Category Technique -> Maybe (Html Msg)
        directiveTreeCategory item =
          let
            categories = getSubElems item
              |> List.sortWith (\c1 c2 -> N.compare c1.name c2.name)
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
                , span [class "treeGroupCategoryName"][text item.name]
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
                 buildListRow
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
                      , onInput (\s -> UpdateDirectiveFilters {directiveFilters | treeFilters = {treeFilters | filter = s}} )][]
                    , div [class "input-group-btn"]
                      [ button [class "btn btn-default", type_ "button"
                      , onClick ( UpdateDirectiveFilters {directiveFilters | treeFilters = {treeFilters | filter = ""}} )]
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

    groupFilters = model.ui.groupFilters
    tableFilters = groupFilters.tableFilters
    complianceFilters = model.ui.complianceFilters

    fun = byNodeCompliance model complianceFilters
    nodeRows =  List.map Tuple3.first fun.rows
    rowId = "byNodes/"
    (sortId, sortOrder) = Dict.get rowId ui.openedRows |> Maybe.withDefault ("Node",Asc)
    sort =   case List.Extra.find (Tuple3.first >> (==) sortId) fun.rows of
               Just (_,_,sortFun) -> (\i1 i2 -> sortFun (fun.data model i1) (fun.data model i2))
               Nothing -> (\_ _ -> EQ)
    filter = tableFilters.filter
    childs       = Maybe.withDefault [] (Maybe.map .nodes details.compliance)
    childrenSort = childs
      |> List.filter (\d -> (String.contains filter d.name) || (String.contains filter d.nodeId.value) )
      |> List.filter (filterDetailsByCompliance complianceFilters)
      |> List.sortWith sort
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
        [ h4 [][text "Compliance by nodes"]
        , ( if model.ui.hasWriteRights then
            button [class "btn btn-default btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editGroups = True}, tab = Groups})]
            [ text "Select groups", i[class "fa fa-plus-circle" ][]]
          else
            text ""
          )
        ]
      , if details.rule.enabled then
        text ""
      else
        div[class "toggle-checkbox-container"]
        [ div[ class "callout-fade callout-warning"]
          [ label[]
            [ i[class "fa fa-warning"][]
            , text "This rule is "
            , b[][ text "disabled"]
            , text ", it will not be applied on any node."
            ]
          ]
        ]
      , div [class "table-header extra-filters"]
        [ div [class "main-filters"]
          [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value tableFilters.filter
            , onInput (\s -> UpdateGroupFilters {groupFilters | tableFilters = {tableFilters | filter = s}} )][]
          , button [class "btn btn-default btn-sm btn-icon", onClick (UpdateComplianceFilters {complianceFilters | showComplianceFilters = not complianceFilters.showComplianceFilters}), style "min-width" "170px"]
            [ text ((if complianceFilters.showComplianceFilters then "Hide " else "Show ") ++ "compliance filters")
            , i [class ("fa " ++ (if complianceFilters.showComplianceFilters then "fa-minus" else "fa-plus"))][]
            ]
          , button [class "btn btn-default btn-sm btn-refresh", onCustomClick (RefreshComplianceTable details.rule.id)][i [class "fa fa-refresh"][]]
          ]
        , displayComplianceFilters complianceFilters UpdateComplianceFilters
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
                List.concatMap (\d -> showComplianceDetails fun d rowId ui.openedRows model)  nodesChildren
              )
          ]
        ]
      ]

groupsTab : Model -> RuleDetails -> Html Msg
groupsTab model details =
  let
    isNewRule  = Maybe.Extra.isNothing details.originRule
    rule       = details.rule
    originRule = details.originRule
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
                button [class "btn btn-default btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editGroups = True}})]
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
              List.map (buildIncludeList originRule model.groupsTree model details.ui.editGroups True) includedTargets
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
              List.map (buildIncludeList originRule model.groupsTree model details.ui.editGroups False) excludedTargets
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
            (disabledClass, disabledLabel) =
              if item.enabled then
                ("", text "")
              else
                (" is-disabled", span[class "badge-disabled"][])
          in
            li [class ("jstree-node jstree-leaf" ++ disabledClass)]
            [ i [class "jstree-icon jstree-ocl"][]
            , a [class ("jstree-anchor" ++ includeClass), onClick (SelectGroup item.target True)]
              [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
              , span [class "item-name"][text item.name, (if item.dynamic then (small [class "greyscala"][text "- Dynamic"]) else (text ""))]
              , disabledLabel
              , div [class "treeActions-container"]
                [ span [class "treeActions"][ span [class "fa action-icon accept"][]]
                , span [class "treeActions"][ span [class "fa action-icon except", onCustomClick (SelectGroup item.target False)][]]
                ]
              , goToBtn (getGroupLink model.contextPath item.id)
              ]
            ]
        groupTreeCategory : Category Group -> Maybe (Html Msg)
        groupTreeCategory item =
          let
            categories = getSubElems item
              |> List.sortWith (\c1 c2 -> N.compare c1.name c2.name)
              |> List.filterMap groupTreeCategory
            groups = item.elems
              |> List.filter (\g -> (filterSearch model.ui.groupFilters.treeFilters.filter (searchFieldGroups g)))
              |> List.sortWith (\g1 g2 -> N.compare g1.name g2.name)
              |> List.map groupTreeElem
            children  = categories ++ groups
          in
            if not (List.isEmpty children) then
              Just (li[class ("jstree-node" ++ foldedClass model.ui.groupFilters.treeFilters item.id)]
              [ i [class "jstree-icon jstree-ocl", onClick (UpdateGroupFilters (foldUnfoldCategory model.ui.groupFilters item.id))][]
              , a [class "jstree-anchor"]
                [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
                , span [class "treeGroupCategoryName"][text item.name]
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
                List.map (buildIncludeList originRule model.groupsTree model details.ui.editGroups True) includedTargets
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
                List.map (buildIncludeList originRule model.groupsTree model details.ui.editGroups False) excludedTargets
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
            , div [class "jstree jstree-default jstree-groups"]
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