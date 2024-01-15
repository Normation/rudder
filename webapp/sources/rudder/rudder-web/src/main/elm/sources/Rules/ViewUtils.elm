module Rules.ViewUtils exposing (..)

import Dict exposing (Dict)
import Dict.Extra
import Either exposing (Either(..))
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, custom, onInput)
import List.Extra
import List
import Maybe.Extra
import String exposing (fromFloat)
import Json.Decode as Decode
import Tuple3
import NaturalOrdering as N exposing (compare)
import Rules.ChangeRequest exposing (ChangeRequestSettings)

import Rules.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)
import Compliance.Utils exposing (..)
import Compliance.Html exposing (buildComplianceBar)

onCustomClick : msg -> Html.Attribute msg
onCustomClick msg =
  custom "click"
    (Decode.succeed
      { message         = msg
      , stopPropagation = True
      , preventDefault  = False
      }
    )

getRuleCompliance : Model -> RuleId -> Maybe RuleComplianceGlobal
getRuleCompliance model rId =
  Dict.get rId.value model.rulesCompliance
  
getListRules : Category Rule -> List (Rule)
getListRules r = getAllElems r

getListCategories : Category Rule  -> List (Category Rule)
getListCategories r = getAllCats r

getRuleNbNodes : RuleDetails -> Maybe Int
getRuleNbNodes ruleDetails =
  ruleDetails.numberOfNodes

getRuleNbGroups : Maybe Rule -> Int
getRuleNbGroups rule =
  case Maybe.Extra.unwrap [] .targets rule of
    [Composition (Or i) (Or e)] -> List.length i + List.length e
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

subItemOrder : ItemFun item subItem data ->  Model -> String -> (item -> item -> Order)
subItemOrder fun model id  =
  case List.Extra.find (Tuple3.first >> (==) id) fun.rows of
    Just (_,_,sort) -> (\i1 i2 -> sort (fun.data model i1) (fun.data model i2))
    Nothing -> (\_ _ -> EQ)

type alias ItemFun item subItem data =
  { children : item -> Model -> String -> List subItem
  , data : Model -> item -> data
  , rows : List (String, data -> Html Msg, (data -> data -> Order) )
  , id : item -> String
  , childDetails : Maybe (subItem -> String -> Dict String (String, SortOrder) -> Model -> List (Html Msg))
  , subItemRows : item -> List String
  , filterItems : item -> Bool
  }

valueCompliance : ComplianceFilters -> ItemFun ValueLine () ValueLine
valueCompliance complianceFilters =
  ItemFun
    (\ _ _ _ -> [])
    (\_ i -> i)
    [ ("Value", .value >> text, (\d1 d2 -> N.compare d1.value  d2.value))
    , ("Message", .message >>  text, (\d1 d2 -> N.compare d1.message d2.message) )
    , ("Status", \r -> td [class "report-compliance"] [ div[] [ span[class r.status][text ( buildComplianceReport r) ] ] ] , (\d1 d2 -> Basics.compare (reportStatusOrder d1) (reportStatusOrder d2)))
    ]
    .value
    Nothing
    (always [])
    (filterValueByCompliance complianceFilters)

nodeValueCompliance : Model -> ComplianceFilters -> ItemFun NodeValueCompliance ValueLine NodeValueCompliance
nodeValueCompliance mod complianceFilters =
  ItemFun
    (\item model sortId ->
      let
        sortFunction = subItemOrder (valueCompliance complianceFilters) model sortId
      in
        item.values
        |> List.filter (filterValueByCompliance complianceFilters)
        |> List.sortWith sortFunction
    )
    (\_ i -> i)
    [ ("Node", .nodeId >> (\nId -> span[][text (getNodeHostname mod nId.value), goToBtn (getNodeLink mod.contextPath nId.value)]),  (\d1 d2 -> N.compare d1.name d2.name))
    , ("Compliance", .complianceDetails >> buildComplianceBar complianceFilters,  (\d1 d2 -> Basics.compare d1.compliance d2.compliance))
    ]
    (.nodeId >> .value)
    (Just (\item -> showComplianceDetails (valueCompliance complianceFilters) item))
    (always (List.map Tuple3.first (valueCompliance complianceFilters).rows))
    (filterDetailsByCompliance complianceFilters)

byComponentCompliance : ItemFun value subValue valueData -> ComplianceFilters -> ItemFun (ComponentCompliance value) (Either (ComponentCompliance value) value) (ComponentCompliance value)
byComponentCompliance subFun complianceFilters =
  let
    name = \item ->
      case item of
        Block b -> b.component
        Value c -> c.component
    compliance = \item ->
      case item of
        Block b -> b.complianceDetails
        Value c -> c.complianceDetails
    complianceValue = \item ->
      case item of
        Block b -> b.compliance
        Value c -> c.compliance
  in
    ItemFun
    ( \item model sortId ->
      case item of
        Block b ->
          let
            sortFunction = subItemOrder (byComponentCompliance subFun complianceFilters) model sortId
          in
            b.components
            |> List.filter (filterByCompliance complianceFilters)
            |> List.sortWith sortFunction
            |> List.map Left
        Value c ->
          let
            sortFunction =  subItemOrder subFun model sortId
          in
            c.values
            |> List.filter subFun.filterItems
            |> List.sortWith sortFunction
            |> List.map Right
    )
    (\_ i -> i)
    [ ("Component", name >> text,  (\d1 d2 -> N.compare (name d1) (name d2)))
    , ("Compliance", \i -> buildComplianceBar complianceFilters (compliance  i ), (\d1 d2 -> Basics.compare (complianceValue d1) (complianceValue d2)) )
    ]
    name
    (Just ( \x ->
      case x of
        Left  b -> showComplianceDetails (byComponentCompliance subFun complianceFilters) b
        Right value -> showComplianceDetails subFun value
    ))
    ( \x ->
      case x of
        Block _ -> (List.map Tuple3.first (byComponentCompliance subFun complianceFilters).rows)
        Value _ ->  (List.map Tuple3.first subFun.rows)
    )
    (always True)

byDirectiveCompliance : Model -> ComplianceFilters -> ItemFun value subValue valueData -> ItemFun (DirectiveCompliance value) (ComponentCompliance value) (Directive, DirectiveCompliance value)
byDirectiveCompliance mod complianceFilters subFun =
  let
    globalPolicy = mod.policyMode
    contextPath  = mod.contextPath
    compliance = \item ->
      case item of
        Block b -> b.complianceDetails
        Value c -> c.complianceDetails
  in
    ItemFun
    (\item model sortId ->
    let
      sortFunction = subItemOrder (byComponentCompliance subFun complianceFilters) model sortId
    in
      item.components
      |> List.filter (filterByCompliance complianceFilters)
      |> List.sortWith sortFunction
    )
    (\m i -> (Maybe.withDefault (Directive i.directiveId i.name "" "" "" False False "" []) (Dict.get i.directiveId.value m.directives), i ))
    [ ("Directive", \(d,_)  -> span [] [ badgePolicyMode globalPolicy d.policyMode, text d.displayName, buildTagsTree d.tags, goToBtn (getDirectiveLink contextPath d.id) ],  (\(_,d1) (_,d2) -> N.compare d1.name d2.name ))
    , ("Compliance", \(_,i) -> buildComplianceBar complianceFilters i.complianceDetails,  (\(_,d1) (_,d2) -> Basics.compare d1.compliance d2.compliance ))
    ]
    (.directiveId >> .value)
    (Just (\b -> showComplianceDetails (byComponentCompliance subFun complianceFilters) b))
    (always (List.map Tuple3.first (byComponentCompliance subFun complianceFilters).rows))
    (always True)

byNodeCompliance :  Model -> ComplianceFilters -> ItemFun NodeCompliance (DirectiveCompliance ValueLine) NodeCompliance
byNodeCompliance mod complianceFilters =
  let
    directive = byDirectiveCompliance mod complianceFilters (valueCompliance complianceFilters)
  in
    ItemFun
    (\item _ sortId ->
      let
        sortFunction = subItemOrder directive mod sortId
      in
        item.directives
        |> List.filter (filterDetailsByCompliance complianceFilters)
        |> List.sortWith sortFunction
    )
    (\_ i -> i)
    [ ("Node", .nodeId >> (\nId -> span[][text (getNodeHostname mod nId.value), goToBtn (getNodeLink mod.contextPath nId.value)]),  (\d1 d2 -> N.compare d1.name d2.name))
    , ("Compliance", .complianceDetails >> buildComplianceBar complianceFilters  ,  (\d1 d2 -> Basics.compare d1.compliance d2.compliance))
    ]
    (.nodeId >> .value)
    (Just (\b -> showComplianceDetails directive b))
    (always (List.map Tuple3.first directive.rows))
    (always True)


showComplianceDetails : ItemFun item subItems data -> item -> String -> Dict String (String, SortOrder) -> Model -> List (Html Msg)
showComplianceDetails fun compliance parent openedRows model =
  let
    itemRows = List.map Tuple3.second (fun.rows)
    data = fun.data model compliance
    detailsRows = List.map (\row -> td [] [row data]) itemRows
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
            filteredChildren = childrenSort

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

searchFieldDirectiveCompliance d =
  [ d.directiveId.value
  , d.name
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
            Just _ -> True
            Nothing -> False
        else if String.isEmpty tag.value then
          case List.Extra.find (\t -> t.key == tag.key) ruleTags of
            Just _ -> True
            Nothing -> False
        else
          True
        )

-- WARNING:
--
-- Here we are building an html snippet that will be placed inside an attribute, so
-- we can't easily use the Html type as there is no built-in way to serialize it manually.
-- This means it will be vulnerable to XSS on its parameters (here the description).
--
-- We resort to escaping it manually here.
buildHtmlStringTag : Tag -> String
buildHtmlStringTag tag =
  let
    tagOpen  = "<span class='tags-label'>"
    tagIcon  = "<i class='fa fa-tag'></i>"
    tagKey   = "<span class='tag-key'>"   ++ htmlEscape tag.key   ++ "</span>"
    tagSep   = "<span class='tag-separator'>=</span>"
    tagVal   = "<span class='tag-value'>" ++ htmlEscape tag.value ++ "</span>"
    tagClose = "</span>"
  in
    tagOpen ++ tagIcon ++ tagKey ++ tagSep ++ tagVal ++ tagClose

htmlEscape : String -> String
htmlEscape s =
  String.replace "&" "&amp;" s
    |> String.replace ">" "&gt;"
    |> String.replace "<" "&lt;"
    |> String.replace "\"" "&quot;"
    |> String.replace "'" "&#x27;"
    |> String.replace "\\" "&#x2F;"

buildTagsTree : List Tag -> Html Msg
buildTagsTree tags =
  let
    nbTags = List.length tags

    tooltipContent : List Tag -> String
    tooltipContent listTags =
      buildTooltipContent ("Tags <span class='tags-label'><i class='fa fa-tags'></i><b>"++ (String.fromInt nbTags) ++"</b></span>") (String.concat (List.map (\tt -> buildHtmlStringTag tt) listTags))
  in
    if (nbTags > 0) then
      span [class "tags-label", attribute "data-bs-toggle" "tooltip", attribute "data-bs-placement" "top", title (tooltipContent tags)]
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
            span [class "tags-label", attribute "data-bs-toggle" "tooltip", attribute "data-bs-placement" "top", title (tooltipContent (List.drop 2 tags))]
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
    span [class ("treeGroupName rudder-label label-sm label-" ++ mode), attribute "data-bs-toggle" "tooltip", attribute "data-bs-placement" "bottom", title (buildTooltipContent "Policy mode" msg)][]

-- WARNING:
--
-- Here the content is an HTML so it need to be already escaped.
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

buildComplianceReport : ValueLine -> String
buildComplianceReport report =
  case report.status of
    "reportsDisabled"            -> "Reports Disabled"
    "noReport"                   -> "No report"
    "error"                      -> "Error"
    "successAlreadyOK"           -> "Success"
    "successRepaired"            -> "Repaired"
    "applying"                   -> "Applying"
    "successNotApplicable"       -> "Not applicable"
    "unexpectedUnknownComponent" -> "Unexpected"
    "unexpectedMissingComponent" -> "Missing"
    "auditNotApplicable"         -> "Not applicable"
    "auditError"                 -> "Error"
    "auditCompliant"             -> "Compliant"
    "auditNonCompliant"          -> "Non compliant"
    "badPolicyMode"              -> "Bad Policy Mode"
    val -> val


reportStatusOrder : ValueLine -> Int
reportStatusOrder report =
  case report.status of
    "successAlreadyOK"           -> 0
    "auditCompliant"             -> 1
    "successNotApplicable"       -> 2
    "auditNotApplicable"         -> 3
    "successRepaired"            -> 4
    "auditNonCompliant"          -> 5
    "error"                      -> 6
    "auditError"                 -> 7
    "badPolicyMode"              -> 8
    "unexpectedMissingComponent" -> 9
    "unexpectedUnknownComponent" -> 10
    "applying"                   -> 11
    "reportsDisabled"            -> 12
    "noReport"                   -> 13
    _ -> -1

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

generateLoadingTable : Html Msg
generateLoadingTable =
  div [class "table-container skeleton-loading"]
  [ table [class "dataTable"]
    [ thead []
      [ tr [class "head"]
        [ th [][ span[][] ]
        , th [][ span[][] ]
        , th [][ span[][] ]
        , th [][ span[][] ]
        , th [][ span[][] ]
        ]
      ]
    , tbody []
      [ tr[] [ td[][span[style "width" "45%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "30%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "75%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "45%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "70%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "80%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "30%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "75%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "45%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "70%"][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]], td[][span[][]] ]
      ]
    ]
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
  let
    anchorKey =
      if String.startsWith "special:" id || String.startsWith "policyServer:" id then
        "target"
      else
        "groupId"
  in
    contextPath ++ """/secure/nodeManager/groups#{\"""" ++ anchorKey ++ """\":\"""" ++ id ++ """\"}"""

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

getNbResourcesBadge : Int -> String -> Html Msg
getNbResourcesBadge nb msg =
  let
    (warningClass, warningTitle, warningIcon) =
      if nb <= 0 then
      ( " warning"
      , msg
      , i[class "fa fa-exclamation-triangle"][]
      )
      else
      ( ""
      , ""
      , text ""
      )
  in
    span [class ("nb-resources" ++ warningClass), title warningTitle]
    [ text (String.fromInt nb)
    , warningIcon
    ]

getGroupsNbResourcesBadge : Int -> Int -> String -> Html Msg
getGroupsNbResourcesBadge nbTargets nbInclude msg =
  let
    (warningClass, warningTitle, warningIcon) =
      if nbInclude <= 0 then
      ( " warning"
      , msg
      , i[class "fa fa-exclamation-triangle"][]
      )
      else
      ( ""
      , ""
      , text ""
      )
  in
    span [class ("nb-resources" ++ warningClass), title warningTitle]
    [ text (String.fromInt nbTargets)
    , warningIcon
    ]

btnSave : Bool -> Bool -> Msg -> Bool -> Html Msg
btnSave saving disable action crEnabled =
  let
    icon =
      if saving then
        i [ class "fa fa-spinner fa-pulse"] []
      else if crEnabled then
        i [ class "fa fa-plus"] []
      else
        i [ class "fa fa-download"] []

    btnClass = if crEnabled then "btn-cr" else "btn-save"
  in
    button [class ("btn btn-success " ++ btnClass ++ (if saving then " saving" else "")), type_ "button", disabled (saving || disable), onClick action]
    [ icon ]

rulesTableHeader : Filters -> Html Msg
rulesTableHeader ruleFilters =
 tr [class "head"]
 [ th [ class (thClass ruleFilters.tableFilters Name) , rowspan 1, colspan 1
       , onClick (UpdateRuleFilters (sortTable ruleFilters Name))
       ] [ text "Name" ]
 , th [ class (thClass ruleFilters.tableFilters Parent) , rowspan 1, colspan 1
      , onClick (UpdateRuleFilters (sortTable ruleFilters Parent))
      ] [ text "Category" ]
 , th [ class (thClass ruleFilters.tableFilters Status) , rowspan 1, colspan 1
      , onClick (UpdateRuleFilters (sortTable ruleFilters Status))
      ] [ text "Status" ]
 , th [ class (thClass ruleFilters.tableFilters Compliance) , rowspan 1, colspan 1
      , onClick (UpdateRuleFilters (sortTable ruleFilters Compliance))
      ] [ text "Compliance" ]
 , th [ class (thClass ruleFilters.tableFilters RuleChanges) , rowspan 1, colspan 1
      , onClick (UpdateRuleFilters (sortTable ruleFilters RuleChanges))
      ] [ text "Changes" ]
 ]
