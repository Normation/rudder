module View exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h4, ul, li, input, a, p, form, label, textarea, select, option, table, thead, tbody, tr, th, td, small)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import String exposing ( fromFloat)
import NaturalOrdering exposing (compareOn)
import ApiCalls exposing (..)

view : Model -> Html Msg
view model =
  let

    getListRules : Category Rule -> List (Rule)
    getListRules r = getAllElems r

    getListCategories : Category Rule  -> List (Category Rule)
    getListCategories r = getAllCats r

    rulesList      = getListRules model.rulesTree
    categoriesList = getListCategories model.rulesTree

    buildRulesTable : List(Html Msg)
    buildRulesTable =
      let
        getCategoryName : String -> String
        getCategoryName id =
          let
            cat = List.Extra.find (.id >> (==) id  ) categoriesList
          in
            case cat of
              Just c -> c.name
              Nothing -> id

        rowTable : Rule -> Html Msg
        rowTable r =
          let
            compliance =
                case List.Extra.find (\c -> c.ruleId == r.id) model.rulesCompliance of
                  Just co ->
                    let
                      complianceDetails = co.complianceDetails

                      buildComplianceBar : Float -> String -> Html msg
                      buildComplianceBar val t =
                        div[class ("progress-bar progress-bar-" ++ t), style "flex" (fromFloat val)][text ((fromFloat val) ++ "%")]

                      getValueCompliance : Maybe Float -> Float
                      getValueCompliance f =
                        case f of
                          Just v  -> v
                          Nothing -> 0

                      valSuccessNotApplicable       = getValueCompliance complianceDetails.successNotApplicable       -- 0
                      valSuccessAlreadyOK           = getValueCompliance complianceDetails.successAlreadyOK           -- 0
                      valSuccessRepaired            = getValueCompliance complianceDetails.successRepaired            -- 0
                      valAuditCompliant             = getValueCompliance complianceDetails.auditCompliant             -- 0
                      valAuditNotApplicable         = getValueCompliance complianceDetails.auditNotApplicable         -- 0

                      valAuditNonCompliant          = getValueCompliance complianceDetails.auditNonCompliant          -- 1

                      valError                      = getValueCompliance complianceDetails.error                      -- 2
                      valAuditError                 = getValueCompliance complianceDetails.auditError                 -- 2

                      valUnexpectedUnknownComponent = getValueCompliance complianceDetails.unexpectedUnknownComponent -- 3
                      valUnexpectedMissingComponent = getValueCompliance complianceDetails.unexpectedMissingComponent -- 3
                      valBadPolicyMode              = getValueCompliance complianceDetails.badPolicyMode              -- 3

                      valApplying                   = getValueCompliance complianceDetails.applying                   -- 4

                      valReportsDisabled            = getValueCompliance complianceDetails.reportsDisabled            -- 5

                      valNoReport                   = getValueCompliance complianceDetails.noReport                   -- 6

                      okStatus        = valSuccessNotApplicable + valSuccessAlreadyOK + valSuccessRepaired + valAuditCompliant + valAuditNotApplicable
                      nonCompliant    = valAuditNonCompliant
                      error           = valError + valAuditError
                      unexpected      = valUnexpectedUnknownComponent + valUnexpectedMissingComponent + valBadPolicyMode
                      pending         = valApplying
                      reportsDisabled = valReportsDisabled
                      noreport        = valNoReport


                    in
                      if ( okStatus + nonCompliant + error + unexpected + pending + reportsDisabled + noreport == 0 ) then
                        div[ class "text-muted"][text "No data available"]
                      else
                        div[ class "progress progress-flex"]
                        [ buildComplianceBar okStatus        "success"
                        , buildComplianceBar nonCompliant    "audit-noncompliant"
                        , buildComplianceBar error           "error"
                        , buildComplianceBar unexpected      "unknown"
                        , buildComplianceBar pending         "pending"
                        , buildComplianceBar reportsDisabled "reportsdisabled"
                        , buildComplianceBar noreport        "no-report"
                        ]

                  Nothing -> text "No report"
          in
                tr[onClick (OpenRuleDetails r.id)]
                [ td[][ text r.name ]
                , td[][ text (getCategoryName r.categoryId) ]
                , td[][ text (if r.enabled == True then "Enabled" else "Disabled") ]
                , td[][ compliance ]
                , td[][ text ""   ]
                ]
      in
        List.map rowTable rulesList

    badgePolicyMode : Directive -> Html Msg
    badgePolicyMode d =
      let
        policyMode = if d.policyMode == "default" then model.policyMode else d.policyMode
      in
        span [class ("rudder-label label-sm label-" ++ policyMode)][]

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

    buildTagsContainer : Rule -> Html Msg
    buildTagsContainer rule =
      let
        tagsList = List.map (\t ->
          div [class "btn-group btn-group-xs"]
            [ button [class "btn btn-default tags-label", type_ "button"]
              [ i[class "fa fa-tag"][]
              , span[class "tag-key"][text t.key]
              , span[class "tag-separator"][text "="]
              , span[class "tag-value"][text t.value]
              , span[class "fa fa-search-plus"][]
              ]
            ]
          ) rule.tags
      in
        div [class "tags-container form-group"](tagsList)


    ruleTreeElem : Rule -> Html Msg
    ruleTreeElem item =
          li [class "jstree-node jstree-leaf"]
          [ i[class "jstree-icon jstree-ocl"][] 
          , a[href "#", class "jstree-anchor", onClick (OpenRuleDetails item.id)]
            [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
            , span [class "treeGroupName tooltipable"][text item.name]
            ]
          ]

    ruleTreeCategory : (Category Rule) -> Html Msg
    ruleTreeCategory item =
          let
            categories = List.map ruleTreeCategory (getSubElems item)
            rules = List.map ruleTreeElem item.elems
            childsList  = ul[class "jstree-children"](categories ++ rules)
          in
            li[class "jstree-node jstree-open"]
            [ i[class "jstree-icon jstree-ocl"][]
            , a[href "#", class "jstree-anchor"]
              [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
              , span [class "treeGroupCategoryName tooltipable"][text item.name]
              ]
            , childsList
            ]

    editionTemplate : EditRuleDetails -> Bool -> Html Msg
    editionTemplate details isNewRule =
      let
        originRule = details.originRule
        rule = details.rule
        ruleTitle = if (String.isEmpty originRule.name && isNewRule) then
            span[style "opacity" "0.4"][text "New rule"]
          else
             text originRule.name
        topButtons =
          [ li [] [
              a [ class "action-success disabled"] [
                i [ class "fa fa-clone"] []
              , text "Clone (Not yet implemented)"
              ]
            ]
          , li [] [
              a [ class "action-primary disabled"] [
                i [ class "fa fa-ban"] []
              , text "Disable (Not yet implemented)"
              ]
            ]
          , li [class "divider"][]
          , li [] [
              a [ class "action-danger disabled"] [
                i [ class "fa fa-times-circle"] []
              , text "Delete (Not yet implemented)"
              ]
            ]
          ]

        isNotMember : List a -> a -> Bool
        isNotMember listIds id =
          List.Extra.notMember id listIds

        getDiffList : List a -> List a -> (Int, Int)
        getDiffList listA listB =
          let
            originLength   = List.length listA
            selectedLength = List.length listB
          in
            if selectedLength == originLength then
              let
                diff = List.length (List.Extra.findIndices (isNotMember listB) listA)
              in
                (diff, diff)
            else if selectedLength > originLength then
              let
                diff = List.length (List.Extra.findIndices (isNotMember listB) listA)
                lengthDiff = selectedLength - originLength
              in
                (lengthDiff + diff, diff)
            else
              let
                diff = List.length (List.Extra.findIndices (isNotMember listA) listB)
                lengthDiff = originLength - selectedLength
              in
                (diff, lengthDiff + diff)

        (diffDirectivesPos, diffDirectivesNeg) = getDiffList originRule.directives rule.directives

      in
        div [class "main-container"]
        [ div [class "main-header "]
          [ div [class "header-title"]
            [ h1[][ruleTitle]
            , div[class "header-buttons"]
              [ div [ class "btn-group" ]
                [ button [ class "btn btn-default dropdown-toggle" , attribute "data-toggle" "dropdown" ] [
                    text "Actions "
                  , i [ class "caret" ] []
                  ]
                , ul [ class "dropdown-menu" ] topButtons
                ]
              , button [class "btn btn-default", type_ "button", onClick CloseRuleDetails][text "Close", i [ class "fa fa-times"][]]
              , button [class "btn btn-success", type_ "button", onClick (CallApi (saveRuleDetails rule isNewRule))][text "Save", i [ class "fa fa-download"] []]
              ]
            ]
          , div [class "header-description"]
            [ p[][text originRule.shortDescription] ]
          ]
        , div [class "main-navbar" ]
          [ ul[class "ui-tabs-nav "]
            [ li[class ("ui-tabs-tab" ++ (if details.tab == Information   then " ui-tabs-active" else ""))]
              [ a[onClick (ChangeTabFocus Information  )]
                [ text "Information" ]
              ]
            , li[class ("ui-tabs-tab" ++ (if details.tab == Directives    then " ui-tabs-active" else ""))]
              [ a[onClick (ChangeTabFocus Directives   )]
                [ text "Directives"
                , span[class "badge badge-secondary badge-resources tooltip-bs"]
                  [ span [class "nb-resources"] [ text (String.fromInt(List.length rule.directives))]
                  , ( if diffDirectivesPos /= 0 then span [class "nb-resources new"] [ text (String.fromInt diffDirectivesPos)] else text "")
                  , ( if diffDirectivesNeg /= 0 then span [class "nb-resources del"] [ text (String.fromInt diffDirectivesNeg)] else text "")
                  ]
                ]
              ]
            , li[class ("ui-tabs-tab" ++ (if details.tab == Groups        then " ui-tabs-active" else ""))]
              [ a[onClick (ChangeTabFocus Groups       )]
                [ text "Groups"
                , span[class "badge badge-secondary badge-resources tooltip-bs"]
                  [ span [class "nb-resources"] [ text (String.fromInt(List.length rule.targets))]
                  ]
                ]
              ]
            , li[class ("ui-tabs-tab" ++ (if details.tab == TechnicalLogs then " ui-tabs-active" else ""))]
              [ a[onClick (ChangeTabFocus TechnicalLogs)]
                [ text "Technical logs"]
              ]
            ]
          ]
        , div [class "main-details"]
          [ tabContent details isNewRule ]
        ]

    templateMain = case model.mode of
      Loading -> text "loading"
      RuleTable   ->
        div [class "main-details"]
        [ div [class "main-table"]
          [ table [ class "no-footer dataTable"]
            [ thead []
              [ tr [class "head"]
                [ th [class "sorting_asc", rowspan 1, colspan 1][text "Name"          ]
                , th [class "sorting"    , rowspan 1, colspan 1][text "Category"      ]
                , th [class "sorting"    , rowspan 1, colspan 1][text "Status"        ]
                , th [class "sorting"    , rowspan 1, colspan 1][text "Compliance"    ]
                , th [class "sorting"    , rowspan 1, colspan 1][text "Recent changes"]
                ]
              ]
            , tbody [] buildRulesTable
            ]
          ]
        ]

      EditRule details ->
        (editionTemplate details False)

      CreateRule details ->
        (editionTemplate details True)

    tabContent details isNewRule=
      let
          rule       = details.rule
          originRule = details.originRule
          newTag     = details.newTag
      in

        case details.tab of
          Information   ->
            let
              rightCol = if isNewRule == True then
                  div [class "col-xs-12 col-sm-6 col-lg-5"]
                  [ div [class "callout-fade callout-info"]
                    [ div [class "marker"][span [class "glyphicon glyphicon-info-sign"][]]
                    , div []
                      [ p[][text "You are creating a new rule. You may already want to apply directives and groups to it."]
                      , p[][text "To do so, please go to their corresponding tab, or use the shortcuts below:"]
                      , div[class "action-btn"]
                        [ button [class "btn btn-default", onClick (EditDirectives True)][text "Select directives", span[class "fa fa-plus"][]]
                        , button [class "btn btn-default", onClick (EditGroups True    )][text "Select groups"    , span[class "fa fa-plus"][]]
                        ]
                      ]
                    ]
                  ]
                else
                  text ""
            in
              div[class "row"][
                form[class "col-xs-12 col-sm-6 col-lg-7"]
                  [ div [class "form-group"]
                    [ label[for "rule-name"][text "Name"]
                    , div[]
                      [ input[ id "rule-name", type_ "text", value rule.name, class "form-control", onInput (\s -> UpdateRule {rule | name = s} ) ][] ]
                    ]
                  , div [class "form-group"]
                    [ label[for "rule-category"][text "Category"]
                    , div[]
                      [ select[ id "rule-category", class "form-control", onInput (\s -> UpdateRule {rule | categoryId = s} ) ]
                        (buildListCategories  "" model.rulesTree)
                      ]
                    ]
                  , div [class "tags-container"]
                    [ label[for "rule-tags-key"][text "Tags"]
                    , div[class "form-group"]
                      [ div[class "input-group"]
                        [ input[ id "rule-tags-key", type_ "text", placeholder "key", class "form-control", onInput (\s -> UpdateNewTag {newTag | key = s} ), value newTag.key][]
                        , span [ class "input-group-addon addon-json"][ text "=" ]
                        , input[ type_ "text", placeholder "value", class "form-control", onInput (\s -> UpdateNewTag {newTag | value = s} ), value newTag.value][]
                        , span [ class "input-group-btn"][ button [ class "btn btn-success", type_ "button", onClick  (UpdateRule {rule | tags = newTag :: rule.tags }) ][ span[class "fa fa-plus"][]] ]
                        ]
                      ]
                    , buildTagsContainer rule
                    ]
                  , div [class "form-group"]
                    [ label[for "rule-short-description"][text "Short description"]
                    , div[]
                      [ input[ id "rule-short-description", type_ "text", value rule.shortDescription, placeholder "There is no short description", class "form-control", onInput (\s -> UpdateRule {rule | shortDescription = s} )  ][] ]
                    ]
                  , div [class "form-group"]
                    [ label[for "rule-long-description"][text "Long description"]
                    , div[]
                      [ textarea[ id "rule-long-description", value rule.longDescription, placeholder "There is no long description", class "form-control", onInput (\s -> UpdateRule {rule | longDescription = s} ) ][] ]
                    ]
                  ]
                , rightCol
                ]
          Directives    ->
            let
              buildTableRow : DirectiveId -> Html Msg
              buildTableRow id =
                let
                  directive = List.Extra.find (.id >> (==) id) model.directives
                  rowDirective = case directive of
                    Nothing -> [td[][text ("Cannot find details of Directive " ++ id.value)]]
                    Just d  ->
                      [ td[]
                        [ badgePolicyMode d
                        , text d.displayName
                        ]
                      , td[][]
                      ]
                in
                  tr[](rowDirective)

              buildListRow : List DirectiveId -> List (Html Msg)
              buildListRow ids =
                let
                  --Get more information about directives, to correctly sort them by displayName
                  directives = model.directives
                    |> List.filter (\d -> List.member d.id ids)
                    |> List.sortWith (compareOn .displayName)

                  rowDirective  : Directive -> Html Msg
                  rowDirective directive =
                    li[]
                    [ a[href ("/rudder/secure/configurationManager/directiveManagement#" ++ directive.id.value)]
                      [ badgePolicyMode directive
                      , span [class "target-name"][text directive.displayName]
                      ]
                    , span [class "target-remove", onClick (UpdateRule {rule | directives = List.Extra.remove directive.id rule.directives})][ i [class "fa fa-times"][] ]
                    , span [class "border"][]
                    ]
                in
                    List.map rowDirective directives
            in

              if details.editDirectives == False then
                div[class "tab-table-content"]
                [ div [class "table-title"]
                  [ h4 [][text "Compliance by Directives"]
                  , button [class "btn btn-default btn-sm", onClick (EditDirectives True)][text "Edit"]
                  ]
                , div [class "table-header"]
                  [ input [type_ "text", placeholder "Filter", class "input-sm form-control"][]
                  , button [class "btn btn-primary btn-sm"][text "Refresh"]
                  ]
                , div[class "table-container"]
                  [ table [class "dataTable"]
                    [ thead[]
                      [ tr[class "head"]
                        [ th [class "sorting_asc"][text "Directive" ]
                        , th [class "sorting"    ][text "Compliance"]
                        ]
                      ]
                    , tbody[]
                      ( if(List.length rule.directives > 0) then
                          List.map buildTableRow rule.directives
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
                      UpdateRule {rule | directives = newDirectives}

                  directiveTreeElem : Technique -> Html Msg
                  directiveTreeElem item =
                        let
                          directivesList =
                            List.map  (\d ->
                              let
                                selectedClass = if (List.member d.id rule.directives) then " item-selected" else ""
                              in
                                li [class "jstree-node jstree-leaf"]
                                [ i[class "jstree-icon jstree-ocl"][]
                                , a[href "#", class ("jstree-anchor" ++ selectedClass)]
                                  [ badgePolicyMode d
                                  , span [class "treeGroupName tooltipable"][text d.displayName]
                                  , div [class "treeActions-container"]
                                    [ span [class "treeActions"][ span [class "tooltipable fa action-icon accept", onClick (addDirectives d.id)][]]
                                    ]
                                  ]
                                ]) item.directives
                        in
                          if List.length directivesList > 0 then
                            li [class "jstree-node jstree-open"]
                            [ i[class "jstree-icon jstree-ocl"][]
                            , a[href "#", class "jstree-anchor"]
                              [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
                              , span [class "treeGroupName tooltipable"][text item.name]
                              ]
                            , ul[class "jstree-children"](directivesList)
                            ]
                          else
                            text ""

                  directiveTreeCategory : Category Technique -> List  (Html Msg)
                  directiveTreeCategory item =
                        let
                          categories = List.concatMap directiveTreeCategory (getSubElems item)
                          techniques = List.map directiveTreeElem (List.filter (\t -> not (List.isEmpty t.directives) ) item.elems)
                          children = techniques ++ categories


                        in
                          if(not (List.isEmpty children) ) then
                            [ li[class "jstree-node jstree-open"]
                            [ i[class "jstree-icon jstree-ocl"][]
                            , a[href "#", class "jstree-anchor"]
                              [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
                              , span [class "treeGroupCategoryName tooltipable"][text item.name]
                              ]
                            , ul[class "jstree-children"] children
                            ] ]
                          else
                            []

                in
                  div[class "row flex-container"]
                  [ div[class "list-edit col-xs-12 col-sm-6 col-lg-7"]
                    [ div[class "list-container"]
                      [ div[class "list-heading"]
                        [ h4[][text "Apply these directives"]
                        , div [class "btn-actions"]
                          [ button[class "btn btn-sm btn-default", onClick (EditDirectives False)][text "Cancel"]
                          , button[class "btn btn-sm btn-success", onClick (CallApi (saveRuleDetails rule isNewRule))][text "Save"]
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
                        , div [class "jstree jstree-default"]
                          [ ul[class "jstree-container-ul jstree-children"](directiveTreeCategory model.techniquesTree)
                          ]
                      ]
                    ]
                  ]
          Groups        ->
            let
              badgePolicyModeGroup : String -> Html Msg
              badgePolicyModeGroup p =
                let
                  policyMode = if p == "default" then model.policyMode else p
                in
                  span [class ("rudder-label label-sm label-" ++ policyMode)][]

              buildIncludeList : RuleTarget -> Html Msg
              buildIncludeList ruleTarget =
                let
                  id = case ruleTarget of
                    NodeGroupId groupId -> groupId
                    Composition _ _ -> "compo"
                    Special spe -> spe
                    Node node -> node
                    Or _ -> "or"
                    And _ -> "and"


                  rowIncludeGroup = li[]
                    [ span[class "fa fa-file-text"][]
                    , a[href ("/rudder/secure/configurationManager/#" ++ "")]
                      [ badgePolicyModeGroup "default"
                      , span [class "target-name"][text id]
                      ]
                    , span [class "target-remove", onClick (SelectGroup (NodeGroupId id) True)][ i [class "fa fa-times"][] ]
                    , span [class "border"][]
                    ]
                in
                  rowIncludeGroup
            in

              if details.editGroups == False then
                div[class "tab-table-content"]
                [ div [class "table-title"]
                  [ h4 [][text "Compliance by Nodes"]
                  , button [class "btn btn-default btn-sm", onClick (EditGroups True)][text "Edit"]
                  ]
                , div [class "table-header"]
                  [ input [type_ "text", placeholder "Filter", class "input-sm form-control"][]
                  , button [class "btn btn-primary btn-sm"][text "Refresh"]
                  ]
                , div[class "table-container"]
                  [ table [class "dataTable"]
                    [ thead[]
                      [ tr[class "head"]
                        [ th [class "sorting_asc"][text "Node" ]
                        , th [class "sorting"    ][text "Compliance"]
                        ]
                      ]
                    , tbody[]
                      [tr[][]]
                    ]
                  ]
                ]
              
              else
                let
                  groupTreeElem : Group -> Html Msg
                  groupTreeElem item =
                        li [class "jstree-node jstree-leaf"]
                        [ i[class "jstree-icon jstree-ocl"][] 
                        , a[href "#", class "jstree-anchor"]
                          [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
                          , span [class "treeGroupName tooltipable"][text item.name, (if item.dynamic then (small [class "greyscala"][text "- Dynamic"]) else (text ""))]
                          , div [class "treeActions-container"]
                            [ span [class "treeActions"][ span [class "tooltipable fa action-icon accept", onClick (SelectGroup (NodeGroupId item.id) True)][]]
                            , span [class "treeActions"][ span [class "tooltipable fa action-icon except", onClick (SelectGroup (NodeGroupId item.id) False)][]]
                            ]
                          ]
                        ]

                  groupTreeCat : Category Group -> Html Msg
                  groupTreeCat item =
                        let
                          categories = List.map groupTreeCat (getSubElems item)
                          groups = List.map groupTreeElem item.elems
                          childsList  = ul[class "jstree-children"](categories ++ groups)
                        in
                          li[class "jstree-node jstree-open"]
                          [ i[class "jstree-icon jstree-ocl"][]
                          , a[href "#", class "jstree-anchor"]
                            [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
                            , span [class "treeGroupCategoryName tooltipable"][text item.name]
                            ]
                          , childsList
                          ]


                  (includedTargets, excludedTargets) =
                    case rule.targets of
                      [Composition (Or include) (Or exclude)] -> (include, exclude)
                      _ -> (rule.targets, [])

                in
                  div[class "row flex-container"]
                  [ div[class "list-edit col-xs-12 col-sm-6 col-lg-7"]
                    [ div[class "list-container"]
                      [ div[class "list-heading"]
                        [ h4[][text "Apply to Nodes in any of these Groups"]
                        , div [class "btn-actions"]
                          [ button[class "btn btn-sm btn-default", onClick (EditGroups False)][text "Cancel"]
                          , button[class "btn btn-sm btn-success", onClick (CallApi (saveRuleDetails rule isNewRule))][text "Save"]
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
                             List.map (buildIncludeList) includedTargets
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
                            List.map (buildIncludeList) excludedTargets

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
                      , div [class "jstree jstree-default"]
                        [ ul[class "jstree-container-ul jstree-children"][(groupTreeCat model.groupsTree)]
                        ]
                      ]
                    ]
                  ]
          TechnicalLogs ->
            div[][text "Technical Logs"]

  in
    div [class "rudder-template"]
    [ div [class "template-sidebar sidebar-left"]
      [ div [class "sidebar-header"]
        [ div [class "header-title"]
          [ h1[]
            [ span[][text "Rules"]
            ]
          , div [class "header-buttons"]
            [ button [class "btn btn-default", type_ "button"][text "Add Category"]
            , button [class "btn btn-success", type_ "button", onClick (GenerateId (\s -> NewRule (RuleId s) ))][text "Create"]
            ]
          ]
        , div [class "header-filter"]
          [ div [class "input-group"]
            [ div [class "input-group-btn"]
              [ button [class "btn btn-default", type_ "button"][span [class "fa fa-folder fa-folder-open"][]]
              ]
            , input[type_ "text", placeholder "Filter", class "form-control"][]
            , div [class "input-group-btn"]
              [ button [class "btn btn-default", type_ "button"][span [class "fa fa-times"][]]
              ]
            ]
          ]
        ]
      , div [class "sidebar-body"]
        [ div [class "sidebar-list"]
          [ div [class "jstree jstree-default"]
            [ ul[class "jstree-container-ul jstree-children"][(ruleTreeCategory model.rulesTree) ]
            ]
          ]
        ]
      ]
    , div [class "template-main"]
      [ templateMain ]
    ]