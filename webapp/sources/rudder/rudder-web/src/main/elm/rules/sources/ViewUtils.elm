module ViewUtils exposing (..)

import DataTypes exposing (..)
import Dict exposing (Dict)
import Either exposing (Either(..))
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
import Tuple3
import Round
import Browser.Navigation as Nav

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

getRuleNbNodes : RuleDetails -> Int
getRuleNbNodes ruleDetails =
  List.length (Maybe.withDefault [] (Maybe.map .nodes ruleDetails.compliance))

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
    case  tableFilters.sortOrder of
      Asc  -> "sorting_asc"
      Desc -> "sorting_desc"
  else
    "sorting"

sortTable : Filters -> SortBy -> Filters
sortTable filters sortBy =
  let
    tableFilters = filters.tableFilters
    order =
      case tableFilters.sortOrder of
        Asc -> Desc
        Desc -> Asc
  in
    if sortBy == tableFilters.sortBy then
      {filters | tableFilters = {tableFilters | sortOrder = order}}
    else
      {filters | tableFilters = {tableFilters | sortBy = sortBy, sortOrder = Asc}}


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

subItemOrder : ItemFun item subItem data ->  Model -> String -> (item -> item -> Order)
subItemOrder fun model id  =
  case List.Extra.find (Tuple3.first >> (==) id) fun.rows of
    Just (_,_,sort) -> (\i1 i2 -> sort (fun.data model i1) (fun.data model i2))
    Nothing -> (\_ _ -> EQ)

type alias ItemFun item subItem data =
  { children : item -> Model -> String ->  List subItem
  , data : Model -> item -> data
  , rows : List (String, data -> Html Msg, (data -> data -> Order) )
  , id : item -> String
  , childDetails : Maybe (subItem -> String -> Dict String (String, SortOrder) -> Model -> List (Html Msg))
  , subItemRows : item -> List String
  }

valueCompliance :  ItemFun ValueCompliance () ValueCompliance
valueCompliance =
  ItemFun
    (\ _ _ _ -> [])
    (\_ i -> i)
    [ ("Value", .value >> text, (\d1 d2 -> compare d1.value  d2.value))
    , ("Messages", .reports >> List.map (\r -> Maybe.withDefault "" r.message) >> List.foldl (++) "\n"  >> text, (\d1 d2 -> compare d1.value d2.value) )
    , ( "Status", .reports >> buildComplianceReport, (\d1 d2 -> compare d1.value d2.value))
    ]
    .value
    Nothing
    (always [])

nodeValueCompliance : Model -> ItemFun NodeValueCompliance ValueCompliance NodeValueCompliance
nodeValueCompliance mod =
  ItemFun
    (\item model sortId ->
      let
        sortFunction =  subItemOrder valueCompliance model sortId
      in
        List.sortWith sortFunction item.values
    )
    (\_ i -> i)
    [ ("Node", .nodeId >> (\nId -> span[][text (getNodeHostname mod nId.value), goToBtn (getNodeLink mod.contextPath nId.value)]),  (\d1 d2 -> compare d1.name d2.name))
    , ("Compliance", .complianceDetails >> buildComplianceBar ,  (\d1 d2 -> compare d1.compliance d2.compliance))
    ]
    (.nodeId >> .value)
    (Just (\item -> showComplianceDetails valueCompliance item))
    (always (List.map Tuple3.first valueCompliance.rows))

byComponentCompliance : ItemFun  value subValue valueData -> ItemFun (ComponentCompliance value) (Either (ComponentCompliance value) value) (ComponentCompliance value)
byComponentCompliance subFun =
  let
    name = \item ->
             case item of
               Block b -> b.component
               Value c -> c.component
    compliance = \item ->
                   case item of
                     Block b -> b.complianceDetails
                     Value c -> c.complianceDetails
  in
  ItemFun
    ( \item model sortId ->
      case item of
        Block b ->
          let
            sortFunction =  subItemOrder (byComponentCompliance subFun) model sortId
          in
             List.map Left (List.sortWith sortFunction b.components)
        Value c ->
          let
            sortFunction =  subItemOrder subFun model sortId
          in
             List.map Right (List.sortWith sortFunction c.values)
    )
    (\_ i -> i)
    [ ("Component", name >> text,  (\d1 d2 -> compare (name d1) (name d2)))
    , ("Compliance", \i -> buildComplianceBar (compliance i), (\d1 d2 -> compare (name d1) (name d2)) )
    ]
    name
    (Just ( \x ->
      case x of
        Left  b -> showComplianceDetails (byComponentCompliance subFun) b
        Right value ->   showComplianceDetails subFun value
    ))
    ( \x ->
      case x of
        Block _ -> (List.map Tuple3.first (byComponentCompliance subFun).rows)
        Value _ ->  (List.map Tuple3.first subFun.rows)
    )

byDirectiveCompliance : Model -> ItemFun value subValue valueData -> ItemFun (DirectiveCompliance value) (ComponentCompliance value) (Directive, DirectiveCompliance value)
byDirectiveCompliance mod subFun =
  let
    globalPolicy = mod.policyMode
    contextPath  = mod.contextPath
  in
    ItemFun
    (\item model sortId ->
    let
      sortFunction =  subItemOrder (byComponentCompliance subFun) model sortId
    in
      List.sortWith sortFunction item.components
    )
    (\m i -> (Maybe.withDefault (Directive i.directiveId i.name "" "" "" False False "" []) (Dict.get i.directiveId.value m.directives), i ))
    [ ("Directive", \(d,i)  -> span [] [ badgePolicyMode globalPolicy d.policyMode, text d.displayName, buildTagsTree d.tags, goToBtn (getDirectiveLink contextPath d.id) ],  (\(_,d1) (_,d2) -> compare d1.name d2.name ))
    , ("Compliance", \(d,i) -> buildComplianceBar  i.complianceDetails,  (\(_,d1) (_,d2) -> compare d1.compliance d2.compliance ))
    ]
    (.directiveId >> .value)
    (Just (\b -> showComplianceDetails (byComponentCompliance subFun) b))
    (always (List.map Tuple3.first (byComponentCompliance subFun).rows))

byNodeCompliance :  Model -> ItemFun NodeCompliance (DirectiveCompliance ValueCompliance) NodeCompliance
byNodeCompliance mod =
  let
    directive = byDirectiveCompliance mod valueCompliance
  in
  ItemFun
    (\item model sortId ->
      let
        sortFunction = subItemOrder directive mod sortId
      in
        List.sortWith sortFunction item.directives
    )
    (\m i -> i)
    [ ("Node", .nodeId >> (\nId -> span[][text (getNodeHostname mod nId.value), goToBtn (getNodeLink mod.contextPath nId.value)]),  (\d1 d2 -> compare d1.name d2.name))
    , ("Compliance", .complianceDetails >> buildComplianceBar,  (\d1 d2 -> compare d1.compliance d2.compliance))
    ]
    (.nodeId >> .value)
    (Just (\b -> showComplianceDetails directive b))
    (always (List.map Tuple3.first directive.rows))



showComplianceDetails : ItemFun item subItems data -> item -> String -> Dict String (String, SortOrder) -> Model -> List (Html Msg)
showComplianceDetails fun compliance parent openedRows model =
  let
    itemRows = List.map Tuple3.second (fun.rows)
    data = fun.data model compliance
    detailsRows = List.map (\row -> td [class "ok"] [row data]) itemRows
    id = fun.id compliance
    rowId = parent ++ "/" ++ id
    rowOpened = Dict.get rowId openedRows
    defaultSort = Maybe.withDefault "" (List.head (fun.subItemRows compliance))
    clickEvent =
      if Maybe.Extra.isJust fun.childDetails then
        [ onClick (ToggleRow rowId defaultSort) ]
      else
        []
    (details, classes) =
              case (fun.childDetails, rowOpened) of
                (Just detailsFun, Just (sortId, sortOrder)) ->
                  let
                    childrenSort = fun.children compliance model sortId
                    (children, order, newOrder) = case sortOrder of
                      Asc -> (childrenSort, "asc", Desc)
                      Desc -> (List.reverse childrenSort, "desc", Asc)
                  in
                   ([ tr [ class "details" ] [
                     td [ class "details", colspan 2 ] [
                       div [ class "innerDetails" ] [
                         table [class "dataTable compliance-table"] [
                           thead [] [
                             tr [ class "head" ] (List.map (\row -> th [onClick (ToggleRowSort rowId row (if row == sortId then newOrder else Asc)), class ("sorting" ++ (if row == sortId then "_"++order else ""))  ] [ text row ]) (fun.subItemRows compliance) )
                           ]
                         , tbody []
                            ( if(List.isEmpty children)
                              then
                                [ tr [] [ td [colspan 2, class "dataTables_empty" ] [ text "There is no compliance details" ] ] ]
                              else
                                let
                                  sortedChildren = children
                                in
                                  List.concatMap (\child ->
                                    (detailsFun child) rowId openedRows model
                                  ) sortedChildren
                            )
                       ]
                     ]
                  ] ] ],
                          "row-foldable row-open")
                (Just _, Nothing) -> ([], "row-foldable row-folded")
                (Nothing, _) -> ([],"")
  in
    (tr ( class classes :: clickEvent)
      detailsRows)
     :: details


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
filterTags : List Tag -> List Tag -> Bool
filterTags ruleTags tags =
  if List.isEmpty tags then
    True
  else if List.isEmpty ruleTags then
    False
  else
    --List.Extra.count (\t -> List.Extra.notMember t ruleTags) tags <= 0
    tags
      |> List.all (\tag ->
        if not (String.isEmpty tag.key) && not (String.isEmpty tag.value) then
          List.member tag ruleTags
        else if String.isEmpty tag.key then
          case List.Extra.find (\t -> t.value == tag.value) ruleTags of
            Just ok -> True
            Nothing -> False
        else if String.isEmpty tag.value then
          case List.Extra.find (\t -> t.key == tag.key) ruleTags of
            Just ok -> True
            Nothing -> False
        else
          True
        )

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
    defaultMsg = "This mode is the globally defined default. You can change it in the global <b>settings</b>."
    msg =
      case mode of
        "enforce" -> "<div style='margin-bottom:5px;'>This rule is in <b style='color:#9bc832;'>enforce</b> mode.</div>" ++ defaultMsg
        "audit"   -> "<div style='margin-bottom:5px;'>This rule is in <b style='color:#3694d1;'>audit</b> mode.</div>" ++ defaultMsg
        "mixed" ->
          """
          <div style='margin-bottom:5px;'>This rule is in <b>mixed</b> mode.</div>
          This rule is applied on at least one node or directive that will <b style='color:#9bc832;'>enforce</b>
          one configuration, and at least one that will <b style='color:#3694d1;'>audit</b> them.
          """
        _ -> "Unknown policy mode"

  in
    span [class ("treeGroupName tooltipable bs-tooltip rudder-label label-sm label-" ++ mode), attribute "data-toggle" "tooltip", attribute "data-placement" "bottom", attribute "data-container" "body", attribute "data-html" "true", attribute "data-original-title" (buildTooltipContent "Policy mode" msg)][]

buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
  in
    headingTag ++ title ++ contentTag ++ content ++ closeTag

buildIncludeList : Maybe Rule -> Category Group -> Model -> Bool -> Bool -> RuleTarget -> Html Msg
buildIncludeList originRule groupsTree model editMode includeBool ruleTarget =
  let
    id = case ruleTarget of
      NodeGroupId groupId -> groupId
      Composition _ _ -> "compo"
      Special spe -> spe
      Node node -> node
      Or _ -> "or"
      And _ -> "and"

    groupsList = getAllElems groupsTree

    isNew = case originRule of
      Nothing -> True
      Just oR ->
        let
          list = case oR.targets of
            [Composition (Or i) (Or e)] ->
              if includeBool then i else e
            targets ->
             if includeBool then targets else []
        in
          List.member ruleTarget list

    (groupName, groupTarget, isEnabled) = case List.Extra.find (\g -> g.id == id) groupsList of
      Just gr -> (gr.name, gr.target, gr.enabled)
      Nothing -> (id, id, True)

    (disabledClass, disabledLabel) =
      if isEnabled then
        ("", text "")
      else
        (" is-disabled", span[class "badge-disabled"][])

    rowIncludeGroup = li[class ((if isNew then "" else "new") ++ disabledClass)]
      [ span[class "fa fa-sitemap"][]
      , a[href (getGroupLink model.contextPath id)]
        [ span [class "target-name"][text groupName]
        , disabledLabel
        , goToIcon
        ]
      , ( if editMode then
          span [class "target-remove", onClick (SelectGroup groupTarget includeBool)][ i [class "fa fa-times"][] ]
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
    displayCompliance : {value : Float, rounded : Int, details : String} -> String -> Html msg
    displayCompliance compliance className =
      if compliance.value > 0 then
        let

          --Hide the compliance text if the value is too small (less than 3%)
          complianceTxt = if compliance.rounded < 3 then "" else String.fromInt (compliance.rounded) ++ "%"
        in
          div [class ("progress-bar progress-bar-" ++ className ++ " bs-tooltip"), attribute "data-toggle" "tooltip", attribute "data-placement" "top", attribute "data-container" "body", attribute "data-html" "true", attribute "data-original-title" (buildTooltipContent "Compliance" compliance.details), style "flex" (fromFloat compliance.value)]
          [ text complianceTxt ]
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

generateLoadingList : Html Msg
generateLoadingList =
  ul[class "skeleton-loading"]
  [ li[style "width" "calc(100% - 25px)"][i[][], span[][]]
  , li[][i[][], span[][]]
  , li[style "width" "calc(100% - 95px)"][i[][], span[][]]
  , ul[]
    [ li[style "width" "calc(100% - 45px)"][i[][], span[][]]
    , li[style "width" "calc(100% - 125px)"][i[][], span[][]]
    , li[][i[][], span[][]]
    ]
  , li[][i[][], span[][]]
  ]

countRecentChanges: RuleId -> (Dict String (List Changes)) -> Float
countRecentChanges rId changes =
  case Dict.get rId.value changes of
    Just cl ->
      case List.Extra.last cl of
        Just c -> c.changes
        Nothing -> 0
    Nothing -> 0

toRuleTarget : String -> RuleTarget
toRuleTarget targetId =
  if String.startsWith "group:" targetId then
    NodeGroupId (String.dropLeft 6 targetId)
  else if String.startsWith "node:" targetId then
    Node (String.dropLeft 5 targetId)
  else Special targetId

getDirectiveLink : String -> DirectiveId -> String
getDirectiveLink contextPath id =
  contextPath ++ """/secure/configurationManager/directiveManagement#{"directiveId":" """++ id.value ++ """ "} """

getGroupLink : String -> String -> String
getGroupLink contextPath id =
  contextPath ++ """/secure/nodeManager/groups#{"groupId":\"""" ++ id ++ """\"}"""

getNodeLink : String -> String -> String
getNodeLink contextPath id =
  contextPath ++ "/secure/nodeManager/node/" ++ id

getNodeHostname : Model -> String -> String
getNodeHostname model id = (Maybe.withDefault (NodeInfo id id "" "" ) (Dict.get id model.nodes)).hostname


goToBtn : String -> Html Msg
goToBtn link =
  a [ class "btn-goto", href link , onCustomClick (GoTo link)] [ i[class "fa fa-pen"][] ]

goToIcon : Html Msg
goToIcon =
  span [ class "btn-goto" ] [ i[class "fa fa-pen"][] ]