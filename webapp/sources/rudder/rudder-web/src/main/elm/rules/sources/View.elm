module View exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h4, ul, li, input, a, p, form, label, textarea, select, option, table, thead, tbody, tr, th, td, small)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected)
import Html.Events exposing (onClick, onInput)
import List exposing (any, intersperse, map, sortWith)
import List.Extra exposing (minimumWith, find)
import String exposing (lines, fromFloat)
import ApiCalls exposing (getRuleDetails)
import NaturalOrdering exposing (compare, compareOn)
import ApiCalls exposing (..)

view : Model -> Html Msg
view model =
  let
    ruleUI = model.ruleUI
    getListRules : RulesTreeItem -> List (RulesTreeItem)
    getListRules r = 
      case r of
        Rule _ _ _ _-> [ r ]
        Category _ _ lCat lRules ->
          let
            rulesChild = List.concat (List.map getListRules lRules)
            categChild = List.concat (List.map getListRules lCat  )
            finalList  = List.append rulesChild categChild
          in
            List.sortWith compareRulesTreeItem finalList

    getListCategories : RulesTreeItem -> List (RulesTreeItem)
    getListCategories r = 
      case r of
        Rule _ _ _ _-> []
        Category _ _ lCat _ ->
          let
            current    = [ r ]
            categChild = List.concat (List.map getListCategories lCat  )
            finalList  = List.append current categChild
          in
            List.sortWith compareRulesTreeItem finalList

    rulesList      = getListRules model.rulesTree
    categoriesList = getListRules model.rulesTree

    buildRulesTable : List(Html Msg)
    buildRulesTable =
      let
        getCategoryName : String -> String
        getCategoryName id =
          let
            cat = List.Extra.find (\c -> case c of 
                Rule _ _ _ _ -> False
                Category cid _ _ _ -> cid == id
              ) categoriesList
          in
            case cat of
              Just (Category _ cname _ _) -> cname
              Just (Rule _ _ _ _) -> id 
              Nothing -> id

        rowTable : RulesTreeItem -> Html Msg
        rowTable r =
          let
            compliance = case r of
              Rule id _ _ _ ->
                case find (\c -> c.ruleId == id) model.rulesCompliance of
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

                      okStatusBar        = buildComplianceBar okStatus        "success"
                      nonCompliantBar    = buildComplianceBar nonCompliant    "audit-noncompliant"
                      errorBar           = buildComplianceBar error           "error"
                      unexpectedBar      = buildComplianceBar unexpected      "unknown"
                      pendingBar         = buildComplianceBar pending         "pending"
                      reportsDisabledBar = buildComplianceBar reportsDisabled "reportsdisabled"
                      noreportBar        = buildComplianceBar noreport        "no-report"

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
              _ -> text "No report"
          in
            case r of
              Rule id name parent enabled->
                tr[onClick (OpenRuleDetails id)]
                [ td[][ text name ]
                , td[][ text (getCategoryName parent) ]
                , td[][ text (if enabled == True then "Enabled" else "Disabled") ]
                , td[][ compliance ]
                , td[][ text ""   ]
                ]
              Category _ _ _ _ -> text ""
      in
        List.map rowTable rulesList

    badgePolicyMode : Directive -> Html Msg
    badgePolicyMode d =
      let
        policyMode = if d.policyMode == "default" then model.policyMode else d.policyMode
      in
        span [class ("rudder-label label-sm label-" ++ policyMode)][]

    compareRulesTreeItem: RulesTreeItem -> RulesTreeItem -> Order
    compareRulesTreeItem a b =
      let
        ca = case a of
          Category rid rname llCat llRules  -> rname
          Rule rid rname rparent renabled   -> rname
        cb = case b of
          Category rid rname llCat llRules  -> rname
          Rule rid rname rparent renabled   -> rname
      in
        if ca > cb then GT else if ca < cb then LT else EQ 

    buildListCategories : RuleDetails -> String -> RulesTreeItem -> List(Html Msg)
    buildListCategories rule sep c =
      let
        newList = case c of
          Rule _ _ _ _ -> []
          Category id name lCat _ ->
            let
              currentOption  = [option [value id, selected (id == rule.categoryId)][text (sep ++ name)]]
              separator      = sep ++ "└─ "
              listCategories = List.concat (List.map (buildListCategories rule separator) (List.sortWith compareRulesTreeItem (lCat)))
            in
              List.append currentOption listCategories
      in
        newList

    buildTagsContainer : RuleDetails -> Html Msg
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


    buildRulesTree : RulesTreeItem -> Html Msg
    buildRulesTree item =
      case item of
        Rule id name parent enabled ->
          li [class "jstree-node jstree-leaf"]
          [ i[class "jstree-icon jstree-ocl"][] 
          , a[href "#", class "jstree-anchor", onClick (OpenRuleDetails id)]
            [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
            , span [class "treeGroupName tooltipable"][text name]
            ]
          ]

        Category id name lCat lRules ->
          let
            childsItem  = List.sortWith compareRulesTreeItem (List.append lRules lCat)
            childsList  = ul[class "jstree-children"](List.map buildRulesTree childsItem)
          in
            li[class "jstree-node jstree-open"]
            [ i[class "jstree-icon jstree-ocl"][]
            , a[href "#", class "jstree-anchor"]
              [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
              , span [class "treeGroupCategoryName tooltipable"][text name]
              ]
            , childsList
            ]

    templateMain = case model.selectedRule of
      Nothing   -> 
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

      Just rule ->
        div [class "main-container"]
          [ div [class "main-header "]
            [ div [class "header-title"]
              [ h1[][text rule.displayName]
              , div[class "header-buttons"]
                [ button [class "btn btn-default", type_ "button"][text "Actions"]
                , button [class "btn btn-default", type_ "button", onClick CloseRuleDetails][text "Close"  ]
                , button [class "btn btn-success", type_ "button", onClick (CallApi (saveRuleDetails rule False))][text "Save"   ]
                ]
              ]
            , div [class "header-description"]
              [ p[][text rule.shortDescription] ]
            ]
          , div [class "main-navbar" ]
            [ ul[class "ui-tabs-nav "]
              [ li[class ("ui-tabs-tab" ++ (if model.tab == Information   then " ui-tabs-active" else ""))]
                [ a[onClick (ChangeTabFocus Information  )]
                  [ text "Information" ]
                ]
              , li[class ("ui-tabs-tab" ++ (if model.tab == Directives    then " ui-tabs-active" else ""))]
                [ a[onClick (ChangeTabFocus Directives   )]
                  [ text "Directives"
                  , span[class "badge"][text (String.fromInt(List.length rule.directives))]
                  ]
                ]
              , li[class ("ui-tabs-tab" ++ (if model.tab == Groups        then " ui-tabs-active" else ""))]
                [ a[onClick (ChangeTabFocus Groups       )]
                  [ text "Groups"
                  , span[class "badge"][text (String.fromInt(List.length rule.targets.include))]
                  ]
                ]
              , li[class ("ui-tabs-tab" ++ (if model.tab == TechnicalLogs then " ui-tabs-active" else ""))]
                [ a[onClick (ChangeTabFocus TechnicalLogs)]
                  [ text "Technical logs"]
                ]
              ]
            ]
          , div [class "main-details"]
            [ tabContent ]
          ]
    tabContent = case model.selectedRule of
      Nothing   -> 
        div [class "alert alert-danger"] [text "Error while fetching rule details"]
      Just rule ->
        case model.tab of
          Information   ->
            div[class "row"][
              form[class "col-xs-12 col-sm-6 col-lg-7"]
                [ div [class "form-group"]
                  [ label[for "rule-name"][text "Name"]
                  , div[]
                    [ input[ id "rule-name", type_ "text", value rule.displayName, class "form-control", onInput UpdateRuleName ][] ]
                  ]
                , div [class "form-group"]
                  [ label[for "rule-category"][text "Category"]
                  , div[]
                    [ select[ id "rule-category", class "form-control", onInput UpdateRuleCategory ]
                      ((buildListCategories rule "") model.rulesTree)
                    ]
                  ]
                , div [class "tags-container"]
                  [ label[for "rule-tags-key"][text "Tags"]
                  , div[class "form-group"]
                    [ div[class "input-group"]
                      [ input[ id "rule-tags-key", type_ "text", placeholder "key", class "form-control", onInput UpdateTagKey, value ruleUI.newTag.key][]
                      , span [ class "input-group-addon addon-json"][ text "=" ] 
                      , input[ type_ "text", placeholder "value", class "form-control", onInput UpdateTagVal, value ruleUI.newTag.value][]
                      , span [ class "input-group-btn"][ button [ class "btn btn-success", type_ "button", onClick AddTag][ span[class "fa fa-plus"][]] ]
                      ]
                    ]
                  , buildTagsContainer rule
                  ]
                , div [class "form-group"]
                  [ label[for "rule-short-description"][text "Short description"]
                  , div[]
                    [ input[ id "rule-short-description", type_ "text", value rule.shortDescription, placeholder "There is no short description", class "form-control", onInput UpdateRuleShortDesc  ][] ]
                  ]
                , div [class "form-group"]
                  [ label[for "rule-long-description"][text "Long description"]
                  , div[]
                    [ textarea[ id "rule-long-description", value rule.longDescription, placeholder "There is no long description", class "form-control", onInput UpdateRuleLongDesc ][] ]
                  ]
                ]
              ]
          Directives    ->
            let
              buildTableRow : String -> Html Msg
              buildTableRow id =
                let
                  directive = find (\dir -> dir.id == id) model.directives
                  rowDirective = case directive of
                    Nothing -> [td[][text ("Cannot find details of Directive " ++ id)]]
                    Just d  ->
                      [ td[]
                        [ badgePolicyMode d
                        , text d.displayName
                        ]
                      , td[][]
                      ]
                in
                  tr[](rowDirective)

              buildListRow : List String -> List (Html Msg)
              buildListRow ids =
                let
                  --Get more information about directives, to correctly sort them by displayName
                  directives = model.directives
                    |> List.sortWith (compareOn .displayName)
                    |> List.filter (\d -> List.member d.id ids)

                  rowDirective  : Directive -> Html Msg
                  rowDirective directive =
                    li[]
                    [ a[href ("/rudder/secure/configurationManager/directiveManagement#" ++ directive.id)]
                      [ badgePolicyMode directive
                      , span [class "target-name"][text directive.displayName, text "-  ", text (String.fromInt (List.length directives))]
                      ]
                    , span [class "target-remove", onClick (SelectDirective directive.id)][ i [class "fa fa-times"][] ]
                    , span [class "border"][]
                    ]
                in
                    List.map rowDirective directives
            in

              if model.editDirectives == False then
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
                  buildDirectivesTree : TechniquesTreeItem -> Html Msg
                  buildDirectivesTree item =
                    case item of
                      Technique id ->
                        let
                          directivesList = model.directives
                            |> List.filter (\d -> d.techniqueName == id)
                            |> List.sortWith (compareOn .displayName)
                            |> List.map    (\d ->
                              let
                                selectedClass = if (List.member d.id rule.directives) then " item-selected" else ""
                              in
                                li [class "jstree-node jstree-leaf"]
                                [ i[class "jstree-icon jstree-ocl"][]
                                , a[href "#", class ("jstree-anchor" ++ selectedClass)]
                                  [ badgePolicyMode d
                                  , span [class "treeGroupName tooltipable"][text d.displayName]
                                  , div [class "treeActions-container"]
                                    [ span [class "treeActions"][ span [class "tooltipable fa action-icon accept", onClick (SelectDirective d.id)][]]
                                    ]
                                  ]
                                ])
                        in
                          if List.length directivesList > 0 then
                            li [class "jstree-node jstree-open"]
                            [ i[class "jstree-icon jstree-ocl"][]
                            , a[href "#", class "jstree-anchor"]
                              [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
                              , span [class "treeGroupName tooltipable"][text id]
                              ]
                            , ul[class "jstree-children"](directivesList)
                            ]
                          else
                            text ""

                      TechniqueCat name description lCat lTec ->
                        let
                          checkNbDirectives : TechniquesTreeItem -> Int
                          checkNbDirectives itm =
                            case itm of
                              Technique tid ->
                                let
                                  nbDirectives = model.directives
                                    |> List.filter (\d -> d.techniqueName == tid)
                                    |> List.length
                                in
                                  nbDirectives

                              TechniqueCat _ _ tlCat tlTec ->
                                let
                                  groupList    = (List.append tlCat tlTec)
                                  nbDirectives = List.sum (List.map checkNbDirectives groupList)
                                in
                                  nbDirectives

                          compareTechniquesTreeItem: TechniquesTreeItem -> TechniquesTreeItem -> Order
                          compareTechniquesTreeItem a b =
                            let
                              ca = case a of
                                TechniqueCat gname _ _ _-> gname
                                Technique gname         -> gname
                              cb = case b of
                                TechniqueCat gname _ _ _-> gname
                                Technique gname         -> gname
                            in
                              if ca > cb then GT else if ca < cb then LT else EQ


                          childsItem   = List.sortWith compareTechniquesTreeItem (List.append lCat lTec)
                          childsList   = ul[class "jstree-children"](List.map buildDirectivesTree childsItem)

                        in
                          if(checkNbDirectives item > 0) then
                            li[class "jstree-node jstree-open"]
                            [ i[class "jstree-icon jstree-ocl"][]
                            , a[href "#", class "jstree-anchor"]
                              [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
                              , span [class "treeGroupCategoryName tooltipable"][text name]
                              ]
                            , childsList
                            ]
                          else
                            text ""

                in
                  div[class "row flex-container"]
                  [ div[class "list-edit col-xs-12 col-sm-6 col-lg-7"]
                    [ div[class "list-container"]
                      [ div[class "list-heading"]
                        [ h4[][text "Apply these directives"]
                        , div [class "btn-actions"]
                          [ button[class "btn btn-sm btn-default", onClick (EditDirectives False)][text "Cancel"]
                          , button[class "btn btn-sm btn-success", onClick (CallApi (saveRuleDetails rule False))][text "Save"]
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
                          [ ul[class "jstree-container-ul jstree-children"][(buildDirectivesTree model.techniquesTree)]
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

              buildIncludeList : Bool -> String -> Html Msg
              buildIncludeList includeBool id =
                let
                  rowIncludeGroup = li[]
                    [ span[class "fa fa-file-text"][]
                    , a[href ("/rudder/secure/configurationManager/#" ++ "")]
                      [ badgePolicyModeGroup "default"
                      , span [class "target-name"][text id]
                      ]
                    , span [class "target-remove", onClick (SelectGroup id includeBool)][ i [class "fa fa-times"][] ]
                    , span [class "border"][]
                    ]
                in
                  rowIncludeGroup
            in

              if model.editGroups == False then
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
                  buildGroupsTree : GroupsTreeItem -> Html Msg
                  buildGroupsTree item =
                    case item of
                      Group id name description nodeIds dynamic enabled  ->
                        li [class "jstree-node jstree-leaf"]
                        [ i[class "jstree-icon jstree-ocl"][] 
                        , a[href "#", class "jstree-anchor"]
                          [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
                          , span [class "treeGroupName tooltipable"][text name, (if dynamic then (small [class "greyscala"][text "- Dynamic"]) else (text ""))]
                          , div [class "treeActions-container"]
                            [ span [class "treeActions"][ span [class "tooltipable fa action-icon accept", onClick (SelectGroup id True)][]]
                            , span [class "treeActions"][ span [class "tooltipable fa action-icon except", onClick (SelectGroup id False)][]]
                            ]
                          ]
                        ]

                      GroupCat id name parent description lCat lGrp ->
                        let
                          compareGroupsTreeItem: GroupsTreeItem -> GroupsTreeItem -> Order
                          compareGroupsTreeItem a b =
                            let
                              ca = case a of
                                GroupCat gid gname gparent gdescription llCat llGrp  -> gname
                                Group gid gname gdescription nodeIds dynamic enabled -> gname
                              cb = case b of
                                GroupCat gid gname gparent gdescription llCat llGrp  -> gname
                                Group gid gname gdescription nodeIds dynamic enabled -> gname
                            in
                              if ca > cb then GT else if ca < cb then LT else EQ 
                          

                          childsItem  = List.sortWith compareGroupsTreeItem (List.append lGrp lCat)
                          childsList  = ul[class "jstree-children"](List.map buildGroupsTree childsItem)
                        in
                          li[class "jstree-node jstree-open"]
                          [ i[class "jstree-icon jstree-ocl"][]
                          , a[href "#", class "jstree-anchor"]
                            [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
                            , span [class "treeGroupCategoryName tooltipable"][text name]
                            ]
                          , childsList
                          ]

                  ruleTargets = rule.targets

                in
                  div[class "row flex-container"]
                  [ div[class "list-edit col-xs-12 col-sm-6 col-lg-7"]
                    [ div[class "list-container"]
                      [ div[class "list-heading"]
                        [ h4[][text "Apply to Nodes in any of these Groups"]
                        , div [class "btn-actions"]
                          [ button[class "btn btn-sm btn-default", onClick (EditGroups False)][text "Cancel"]
                          , button[class "btn btn-sm btn-success", onClick (CallApi (saveRuleDetails rule False))][text "Save"]
                          ]
                        ]
                      , ul[class "groups applied-list"]
                        ( if(List.length ruleTargets.include > 0) then
                           (List.map (buildIncludeList True) ruleTargets.include)
                          else
                           [ li [class "empty"]
                             [ span [] [text "There is no group included."]
                             , span [class "warning-sign"][i [class "fa fa-info-circle"][]]
                             ]
                           ]
                        )
                      ]
                    , div[class "list-container"]
                      [ div[class "list-heading except"]
                        [ h4[][text "Except to Nodes in any of these Groups"]
                        ]
                      , ul[class "groups applied-list"]
                        ( if(List.length ruleTargets.exclude > 0) then
                           (List.map (buildIncludeList False) ruleTargets.exclude)
                          else
                           [ li [class "empty"]
                             [ span [] [text "There is no group excluded."]
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
                          , text "Select groups"
                          ]
                        , i [class "fa fa-bars"][]
                        ]
                      , div [class "jstree jstree-default"]
                        [ ul[class "jstree-container-ul jstree-children"][(buildGroupsTree model.groupsTree)]
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
            , button [class "btn btn-success", type_ "button"][text "Create"]
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
            [ ul[class "jstree-container-ul jstree-children"][(buildRulesTree model.rulesTree) ]
            ]
          ]
        ]
      ]
    , div [class "template-main"]
      [ templateMain ]
    ]