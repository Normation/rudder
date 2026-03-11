module Rules.ViewTabDirectives exposing (..)

import Dict
import Dict.Extra
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput)
import Html.Events.Extra exposing (onClickPreventDefault)
import List.Extra
import List
import Maybe.Extra
import Set
import NaturalOrdering as N exposing (compareOn)
import Tuple3

import Rules.DataTypes exposing (..)
import Rules.ViewUtils exposing (..)

import Compliance.DataTypes exposing (..)
import Compliance.Utils exposing (badgePolicyMode, displayComplianceFilters, filterDetailsByCompliance)
import Ui.Datatable exposing (SortOrder(..), filterSearch, Category, getSubElems, getAllElems, generateLoadingTable)
import Utils.TooltipUtils exposing (buildTooltipContent)


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
      |> List.filter (\d -> (filterSearch filter (searchFieldDirectiveCompliance d)))
      |> List.filter (filterDetailsByCompliance complianceFilters)
      |> List.sortWith sort
    (directivesChildren, order, newOrder) = case sortOrder of
       Asc -> (childrenSort, "asc", Desc)
       Desc -> (List.reverse childrenSort, "desc", Asc)

    ruleDirectives = directives
      |> List.filter (\d -> List.member d.id ruleDirectivesId)
    noDirectives = List.isEmpty ruleDirectives
    hasDirectivesButNoCompliance = List.isEmpty childs && not noDirectives
    noCompliance = not rule.enabled || hasDirectivesButNoCompliance

    disabledRuleDirectives = ruleDirectives
      |> List.filter (\d -> not d.enabled)

    noNodes = (Maybe.withDefault 1 (getRuleNbNodes details))  <= 0


    noComplianceMsg =
      if noNodes || rule.enabled && hasDirectivesButNoCompliance then
        let
          msg =
            if noNodes then
              "This rule is not applied on any node."
            else
              "There is no directive compliance yet."
        in
          div[ class "callout-fade callout-warning"]
          [ i[class "fa fa-warning me-2"][]
          , text msg
          ]
      else
        text ""
    (complianceFilterBtn, complianceFilterContainer) =
      if noCompliance then
        ( text ""
        , text ""
        )
      else
        ( button [class "btn btn-default btn-sm btn-icon", onClick (UpdateComplianceFilters {complianceFilters | showComplianceFilters = not complianceFilters.showComplianceFilters}), style "min-width" "170px"]
          [ text ((if complianceFilters.showComplianceFilters then "Hide " else "Show ") ++ "compliance filters")
          , i [class ("fa " ++ (if complianceFilters.showComplianceFilters then "fa-minus" else "fa-plus"))][]
          ]
        , displayComplianceFilters complianceFilters UpdateComplianceFilters
        )

    directiveComplianceTable =
      if Maybe.Extra.isNothing details.compliance then -- Compliance is not loaded yet
        [ generateLoadingTable True 2 ]
      else
        [ div [class "table-header extra-filters"]
          [ div [class "main-filters"]
            [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value filter
            , onInput (\s -> UpdateDirectiveFilters {directiveFilters | tableFilters = {tableFilters | filter = s}} )][]
            , complianceFilterBtn
            , button [class "btn btn-default btn-sm btn-refresh", onCustomClick (RefreshComplianceTable rule.id)][i [class "fa fa-refresh"][]]
            ]
          , complianceFilterContainer
          ]
        , div[class "table-container"]
          [(
          let
            filteredDirectives = ruleDirectives
              |> List.filter (\d -> d.enabled && (filterSearch filter (searchFieldDirectives d)))
              |> List.sortWith (\d1 d2 -> N.compare d1.displayName d2.displayName)
            sortedDirectives   = case tableFilters.sortOrder of
              Asc  -> filteredDirectives
              Desc -> List.reverse filteredDirectives
            toggleSortOrder o = if o == Asc then Desc else Asc
          in
            if noCompliance then
              table [class "dataTable"]
              [ thead []
                [ tr [ class "head" ]
                  [ th [onClick (UpdateDirectiveFilters {directiveFilters | tableFilters = {tableFilters | sortOrder = toggleSortOrder tableFilters.sortOrder}}), class ("sorting_" ++ (if tableFilters.sortOrder == Asc then "asc" else "desc"))] [ text "Directive" ]
                  ]
                ]
              , tbody [] (
                if noDirectives then
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
                        [ span [class "treeActions"][ span [class "fa action-icon ms-1 accept"][]]
                        ]
                        , goToBtn (getDirectiveLink model.contextPath  d.id)
                        ]
                      ]
                    ]
                  )
                )
              ]
            else
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
            )
          ]
        ]
  in
    if not details.ui.editDirectives then
      div[class "tab-table-content"]
      ( List.append
        [ div [class "table-title mb-3"]
          [ h4 [class "mb-0"][text "Compliance by directives"]
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
        , noComplianceMsg
        ]
        directiveComplianceTable
      )

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
                  , a [class ("jstree-anchor" ++ selectedClass), onClickPreventDefault (addDirectives d.id)]
                    [ badgePolicyMode model.policyMode d.policyMode
                    , unusedWarning
                    , span [class "item-name"][text d.displayName]
                    , disabledLabel
                    , buildTagsTree d.tags
                    , div [class "treeActions-container"]
                      [ span [class "treeActions"][ span [class "fa action-icon ms-1 accept"][]]
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
        cancelDirectives = case details.originRule of
          Just oR -> oR.directives
          Nothing -> []

        (cancelUi, confirmBtn) =
          if isNewRule then
            ( ui
            , text ""
            )
          else
            ( {ui | editDirectives = False}
            , button[class "btn btn-default btn-icon", onClick (UpdateRuleForm {details | ui = {ui | editDirectives = False}} )][text "Confirm", i[class "fa fa-check"][]]
            )
      in
        div[class "row flex-container"]
        [ div[class "list-edit d-flex flex-column col-sm-12 col-md-6 col-xl-7"]
          [ div[class "list-container"]
            [ div[class "list-heading align-items-center"]
              [ h4[class "mb-0"][text "Apply these directives"]
              , div [class "btn-actions"]
                [ button[class "btn btn-default btn-icon", onClick (UpdateRuleForm { details | rule = {rule | directives = cancelDirectives}, ui = cancelUi })]
                  [text "Cancel", i[class "fa fa-undo-alt"][]]
                , confirmBtn
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
        , div [class "tree-edit col-sm-12 col-md-6 col-xl-5"]
          [ div [class "tree-container"]
            [ div [class "tree-heading"]
              [ h4 [][ text "Select directives" ]
              , i [class "fa fa-bars"][]
              ]
            , div [class "header-filter"]
              [ div [class "input-group"]
                [ button [class "btn btn-default", type_ "button"][span [class "fa fa-folder fa-folder-open"][]]
                , input[type_ "text", placeholder "Filter", class "form-control"
                  , onInput (\s -> UpdateDirectiveFilters {directiveFilters | treeFilters = {treeFilters | filter = s}} )][]
                , button [class "btn btn-default", type_ "button"
                , onClick ( UpdateDirectiveFilters {directiveFilters | treeFilters = {treeFilters | filter = ""}} )]
                  [span [class "fa fa-times"][]]
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
