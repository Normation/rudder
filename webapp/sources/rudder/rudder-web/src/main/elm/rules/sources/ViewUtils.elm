module ViewUtils exposing (..)

import DataTypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, onInput, custom)
import List.Extra
import List
import Maybe.Extra
import String exposing (fromFloat)
import NaturalOrdering exposing (compareOn)
import ApiCalls exposing (..)
import ComplianceUtils exposing (..)
import Json.Decode as Decode


onCustomClick : msg -> Html.Attribute msg
onCustomClick msg =
  custom "click"
    (Decode.succeed
      { message         = msg
      , stopPropagation = True
      , preventDefault  = True
      }
    )

getListRules : Category Rule -> List (Rule)
getListRules r = getAllElems r

getListCategories : Category Rule  -> List (Category Rule)
getListCategories r = getAllCats r

getRuleNbNodes : Model -> RuleId -> Int
getRuleNbNodes model ruleId =
  case getRuleCompliance model ruleId of
    Just rc ->
      let
        nodesCompliance = toNodeCompliance rc
      in
        List.length nodesCompliance.nodes
    Nothing -> 0

getRuleNbGroups : Maybe Rule -> Int
getRuleNbGroups rule =
  case Maybe.Extra.unwrap [] .targets rule of
    [Composition (Or i) (Or e)] -> List.length i
    targets -> List.length targets


getCategoryName : Model -> String -> String
getCategoryName model id =
  let
    cat = List.Extra.find (.id >> (==) id  ) (getListCategories model.rulesTree)
  in
    case cat of
      Just c -> c.name
      Nothing -> id

getParentCategoryId : List (Category a) -> String -> String
getParentCategoryId categories categoryId =
  let
    findSubCategory : String -> Category a -> Bool
    findSubCategory catId cat =
      case List.Extra.find (\c -> c.id == catId) (getSubElems cat) of
        Just _  -> True
        Nothing -> False
  in
    case List.Extra.find (\c -> (findSubCategory categoryId c)) categories of
      Just ct -> ct.id
      Nothing -> "rootRuleCategory"
--
-- DATATABLES & TREES
--

thClass : TableFilters -> SortBy -> String
thClass tableFilters sortBy =
  if sortBy == tableFilters.sortBy then
    if (tableFilters.sortOrder == True) then
      "sorting_asc"
    else
      "sorting_desc"
  else
    "sorting"

sortTable : Filters -> SortBy -> Filters
sortTable filters sortBy =
  let
    tableFilters = filters.tableFilters
  in
    if sortBy == tableFilters.sortBy then
      {filters | tableFilters = {tableFilters | sortOrder = not tableFilters.sortOrder}}
    else
      {filters | tableFilters = {tableFilters | sortBy = sortBy, sortOrder = True}}


foldedClass : TreeFilters -> String -> String
foldedClass treeFilters catId =
  if List.member catId treeFilters.folded then
    " jstree-closed"
  else
    " jstree-open"

foldUnfoldCategory : Filters -> String -> Filters
foldUnfoldCategory filters catId =
  let
    treeFilters = filters.treeFilters
    foldedList  =
      if List.member catId treeFilters.folded then
        List.Extra.remove catId treeFilters.folded
      else
        catId :: treeFilters.folded
  in
    {filters | treeFilters = {treeFilters | folded = foldedList}}

foldUnfoldRow : String -> Filters -> Filters
foldUnfoldRow rowId filters =
  let
    tableFilters = filters.tableFilters
  in
    if List.member rowId tableFilters.unfolded then
      { filters | tableFilters = { tableFilters | unfolded = (List.Extra.remove rowId tableFilters.unfolded) }}
    else
      { filters | tableFilters = { tableFilters | unfolded = (rowId :: tableFilters.unfolded) }}

foldedRowClass : String -> TableFilters -> String
foldedRowClass rowId tableFilters =
  if List.member rowId tableFilters.unfolded then
    " row-foldable row-open"
  else
    " row-foldable row-folded"

getDirectiveName : List Directive -> DirectiveId -> String
getDirectiveName directives directiveId =
  case List.Extra.find (\d -> d.id == directiveId) directives of
    Just di -> di.displayName
    Nothing -> "Cannot find directive details"

rowComplianceDetails : String -> RowState -> Filters -> Msg -> Model -> Html Msg
rowComplianceDetails rowId rowState filters onClickEvent model =
  let
    directives   = model.directives
    tableFilters = filters.tableFilters

    innerTableRow : ({rowId : String, rowState : RowState, name : String, value : Html Msg, optional : Maybe (Html Msg)}) -> List (Html Msg)
    innerTableRow item =
      let
        newRowId       = (rowId ++ "--" ++ item.rowId)
        trClass        = class (if item.rowState /= NoSublvl then (foldedRowClass newRowId tableFilters) else "")
        nextClickEvent = case (item.rowState, onClickEvent) of
          (NoSublvl, _) -> Ignore
          (_ , UpdateDirectiveFilters f) -> UpdateDirectiveFilters (foldUnfoldRow newRowId model.ui.directiveFilters)
          (_ , UpdateGroupFilters     f) -> UpdateGroupFilters     (foldUnfoldRow newRowId model.ui.groupFilters    )
          _ -> Ignore
        clickEvent     = onCustomClick nextClickEvent
        trDetails      = rowComplianceDetails newRowId item.rowState filters nextClickEvent model
      in
        [ tr[trClass, clickEvent, id newRowId]
          [ td [][ text item.name  ]
          , td [][ item.value ]
          , ( case item.optional of
            Just op -> op
            Nothing -> text ""
          )
          ]
        , trDetails
        ]
  in
    if List.member rowId tableFilters.unfolded then
      let
        ({col1, col2, col3}, items) = case rowState of
          DirectiveComponentLvl directiveCompliance -> ( {col1 = "Component" , col2 = "Status" , col3 = Nothing } ,
            directiveCompliance.components
              |> List.map (\i ->
                { rowId    = i.component
                , rowState = DirectiveNodeLvl i
                , name     = i.component
                , value    = buildComplianceBar i.complianceDetails
                , optional = Nothing
                }
              )
            )
          DirectiveNodeLvl componentCompliance -> ( {col1 = "Node" , col2 = "" , col3 = Nothing } ,
            componentCompliance.nodes
              |> List.map (\i ->
                { rowId    = i.nodeId.value
                , rowState = DirectiveValueLvl i
                , name     = i.name
                , value    = text ""
                , optional = Nothing
                }
              )
            )
          DirectiveValueLvl nodeCompliance -> ( {col1 = "Value" , col2 = "Messages" , col3 = Just "Status" } ,
            nodeCompliance.values
              |> List.map (\i ->
                { rowId    = i.value
                , rowState = NoSublvl
                , name     = i.value
                , value    = text ( i.reports
                  |> List.map (\r -> Maybe.withDefault "" r.message)
                  |> String.join ", "
                )
                , optional = Just ( buildComplianceReport i.reports )
                }
              )
            )
          NodeDirectiveLvl nodeCompliance      -> ( {col1 = "Directive" , col2 = "Status" , col3 = Nothing } ,
            nodeCompliance.directives
              |> List.map (\i ->
                { rowId    = i.directiveId.value
                , rowState = NodeComponentLvl i
                , name     = getDirectiveName directives i.directiveId
                , value    = buildComplianceBar i.complianceDetails
                , optional = Nothing
                }
              )
            )
          NodeComponentLvl directiveByNodeCompliance -> ( {col1 = "Component" , col2 = "Status" , col3 = Nothing } ,
            directiveByNodeCompliance.components
              |> List.map (\i ->
                { rowId    = i.component
                , rowState = NodeValueLvl i
                , name     = i.component
                , value    = buildComplianceBar i.complianceDetails
                , optional = Nothing
                }
              )
            )
          NodeValueLvl componentByNodeCompliance -> ( {col1 = "Value" , col2 = "Messages" , col3 = Just "Status" } ,
            componentByNodeCompliance.value
              |> List.map (\i ->
                { rowId    = i.value
                , rowState = NoSublvl
                , name     = i.value
                , value    = text ( i.reports
                  |> List.map (\r -> Maybe.withDefault "" r.message)
                  |> String.join ", "
                )
                , optional = Just ( buildComplianceReport i.reports )
                }
              )
            )
          NoSublvl -> ( {col1 = "" , col2 = "" , col3 = Nothing }, [] )
      in
        tr[class "details"]
        [ td [class "details", colspan 2]
          [ div [class "innerDetails"]
            [ table [class "dataTable"]
              [ thead []
                [ tr [class "head"]
                  [ th[][ text col1 ]
                  , th[][ text col2 ]
                  , ( case col3 of
                    Just c  -> th[][ text c ]
                    Nothing -> text ""
                  )
                  ]
                ]
              , tbody []
                ( if(List.length items > 0) then
                    List.concatMap innerTableRow items
                  else
                    [ tr[]
                      [ td[colspan 2, class "dataTables_empty"][text "There is no compliance"]
                      ]
                    ]
                )
              ]
            ]
          ]
        ]
      else text ""

getDirectivesSortFunction : List RuleCompliance -> RuleId -> TableFilters -> Directive -> Directive -> Order
getDirectivesSortFunction rulesCompliance ruleId tableFilter d1 d2 =
  let
    order = case tableFilter.sortBy of
      Name -> NaturalOrdering.compare d1.displayName d2.displayName

      Compliance -> case List.Extra.find (\c -> c.ruleId == ruleId) rulesCompliance of
        Just co ->
          let
            d1Co = case List.Extra.find (\dir -> dir.directiveId == d1.id) co.directives of
              Just c  -> getDirectiveComputedCompliance c
              Nothing -> -2
            d2Co = case List.Extra.find (\dir -> dir.directiveId == d2.id) co.directives of
              Just c  -> getDirectiveComputedCompliance c
              Nothing -> -2
          in
            compare d1Co d2Co

        Nothing -> LT
      _ -> LT
  in
    if tableFilter.sortOrder then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

getNodesSortFunction : TableFilters -> List NodeInfo -> NodeComplianceByNode -> NodeComplianceByNode -> Order
getNodesSortFunction tableFilter nodes n1 n2 =
  let
    order = case tableFilter.sortBy of
      Name ->
        let
         n1Name = case List.Extra.find (\n -> n.id == n1.nodeId.value) nodes of
           Just nn -> nn.hostname
           Nothing -> ""

         n2Name = case List.Extra.find (\n -> n.id == n2.nodeId.value) nodes of
           Just nn -> nn.hostname
           Nothing -> ""
        in
          NaturalOrdering.compare n1Name n2Name

      Compliance ->
        let
          n1Co = getNodeComputedCompliance n1
          n2Co = getNodeComputedCompliance n2
        in
          compare n1Co n2Co

      _ -> LT
  in
    if tableFilter.sortOrder then
      order
    else
      case order of
        LT -> GT
        EQ -> EQ
        GT -> LT

searchFieldDirectives d =
  [ d.id.value
  , d.displayName
  ]

searchFieldRules r model =
  [ r.id.value
  , r.name
  , r.categoryId
  , getCategoryName model r.categoryId
  ]

searchFieldNodes n nodes=
  [ n.nodeId.value
  , case List.Extra.find (\node -> node.id == n.nodeId.value) nodes of
    Just nn -> nn.hostname
    Nothing -> ""
  ]

searchFieldGroups g =
  [ g.id
  , g.name
  ]

filterSearch : String -> List String -> Bool
filterSearch filterString searchFields =
  let
    -- Join all the fields into one string to simplify the search
    stringToCheck = searchFields
      |> String.join "|"
      |> String.toLower

    searchString  = filterString
      |> String.toLower
      |> String.trim
  in
    String.contains searchString stringToCheck


-- TAGS DISPLAY
buildHtmlStringTag : Tag -> String
buildHtmlStringTag tag =
  let
    tagOpen  = "<span class='tags-label'>"
    tagIcon  = "<i class='fa fa-tag'></i>"
    tagKey   = "<span class='tag-key'>"   ++ tag.key   ++ "</span>"
    tagSep   = "<span class='tag-separator'>=</span>"
    tagVal   = "<span class='tag-value'>" ++ tag.value ++ "</span>"
    tagClose = "</span>"
  in
    tagOpen ++ tagIcon ++ tagKey ++ tagSep ++ tagVal ++ tagClose


buildTagsTree : List Tag -> Html Msg
buildTagsTree tags =
  let
    nbTags = List.length tags

    tooltipContent : List Tag -> String
    tooltipContent listTags =
      buildTooltipContent ("Tags <span class='tags-label'><i class='fa fa-tags'></i><b>"++ (String.fromInt nbTags) ++"</b></span>") (String.concat (List.map (\tt -> buildHtmlStringTag tt) listTags))
  in
    if (nbTags > 0) then
      span [class "tags-label bs-tooltip", attribute "data-toggle" "tooltip", attribute "data-placement" "top", attribute "data-container" "body", attribute "data-html" "true", attribute "data-original-title" (tooltipContent tags)]
      [ i [class "fa fa-tags"][]
      , b[][text (String.fromInt nbTags)]
      ]
    else
      text ""

buildTagsList : List Tag -> Html Msg
buildTagsList tags =
  let
    nbTags = List.length tags

    spanTags : Tag -> Html Msg
    spanTags t =
      span [class "tags-label"]
      [ i [class "fa fa-tag"][]
      , span [class "tag-key"       ][(if (String.isEmpty t.key  ) then i [class "fa fa-asterisk"][] else span[][text t.key  ])]
      , span [class "tag-separator" ][text "="]
      , span [class "tag-value"     ][(if (String.isEmpty t.value) then i [class "fa fa-asterisk"][] else span[][text t.value])]
      ]

    tooltipContent : List Tag -> String
    tooltipContent listTags =
      buildTooltipContent ("Tags <span class='tags-label'><i class='fa fa-tags'></i><b><i>"++ (String.fromInt (nbTags - 2)) ++" more</i></b></span>") (String.concat (List.map (\tt -> buildHtmlStringTag tt) listTags))
  in
    if (nbTags > 0) then
      if (nbTags > 2) then
        span[class "tags-list"](
          List.append (List.map (\t -> spanTags t) (List.take 2 tags)) [
            span [class "tags-label bs-tooltip", attribute "data-toggle" "tooltip", attribute "data-placement" "top", attribute "data-container" "body", attribute "data-html" "true", attribute "data-original-title" (tooltipContent (List.drop 2 tags))]
            [ i [class "fa fa-tags"][]
            , b [][ i[][text (String.fromInt (nbTags - 2)), text " more"]]
            ]
          ]
        )
      else
        span[class "tags-list"](List.map (\t -> spanTags t) tags)
    else
      text ""


badgePolicyMode : String -> String -> Html Msg
badgePolicyMode globalPolicyMode policyMode =
  let
    mode = if policyMode == "default" then globalPolicyMode else policyMode
  in
    span [class ("rudder-label label-sm label-" ++ mode)][]

buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
  in
    headingTag ++ title ++ contentTag ++ content ++ closeTag

buildIncludeList : Category Group -> Bool -> Bool -> RuleTarget -> Html Msg
buildIncludeList groupsTree editMode includeBool ruleTarget =
  let
    groupsList = getAllElems groupsTree
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
      , ( if editMode then
          span [class "target-remove", onClick (SelectGroup (NodeGroupId id) includeBool)][ i [class "fa fa-times"][] ]
        else
          text ""
      )
      , span [class "border"][]
      ]
  in
    rowIncludeGroup

buildComplianceBar : ComplianceDetails -> Html Msg
buildComplianceBar complianceDetails=
  let
    displayCompliance : {value : Float, details : String} -> String -> Html msg
    displayCompliance compliance className =
      if compliance.value > 0 then
        div [class ("progress-bar progress-bar-" ++ className ++ " bs-tooltip"), attribute "data-toggle" "tooltip", attribute "data-placement" "top", attribute "data-container" "body", attribute "data-html" "true", attribute "data-original-title" (buildTooltipContent "Compliance" compliance.details), style "flex" (fromFloat compliance.value)]
        [ text ((fromFloat compliance.value) ++ "%") ]
      else
        text ""

    allComplianceValues = getAllComplianceValues complianceDetails
  in
    if ( allComplianceValues.okStatus.value + allComplianceValues.nonCompliant.value + allComplianceValues.error.value + allComplianceValues.unexpected.value + allComplianceValues.pending.value + allComplianceValues.reportsDisabled.value + allComplianceValues.noReport.value == 0 ) then
      div[ class "text-muted"][text "No data available"]
    else
      div[ class "progress progress-flex"]
      [ displayCompliance allComplianceValues.okStatus        "success"
      , displayCompliance allComplianceValues.nonCompliant    "audit-noncompliant"
      , displayCompliance allComplianceValues.error           "error"
      , displayCompliance allComplianceValues.unexpected      "unknown progress-bar-striped"
      , displayCompliance allComplianceValues.pending         "pending progress-bar-striped"
      , displayCompliance allComplianceValues.reportsDisabled "reportsdisabled"
      , displayCompliance allComplianceValues.noReport        "no-report"
      ]

buildComplianceReport : List Report -> Html Msg
buildComplianceReport reports =
  let
    complianceTxt : String -> String
    complianceTxt val =
      case val of
        "reportsDisabled"            -> "Reports Disabled"
        "noReport"                   -> "No report"
        "error"                      -> "Error"
        "successAlreadyOK"           -> "Success"
        "successRepaired"            -> "Repaired"
        "applying"                   -> "Applying"
        "auditNotApplicable"         -> "Not applicable"
        "unexpectedUnknownComponent" -> "Unexpected"
        "unexpectedMissingComponent" -> "Missing"
        "AuditNotApplicable"         -> "Not applicable"
        "auditError"                 -> "Error"
        "auditCompliant"             -> "Compliant"
        "auditNonCompliant"          -> "Non compliant"
        "badPolicyMode"              -> "Bad Policy Mode"
        _ -> val
  in
    td [class "report-compliance"]
    [ div[]
      ( List.map (\r -> span[class r.status][text (complianceTxt r.status)]) reports )
    ]