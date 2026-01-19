module GroupCompliance.ViewUtils exposing (..)

import Compliance.Html exposing (buildComplianceBar)
import Dict exposing (Dict)
import Either exposing (Either(..))
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (custom, onClick, onInput)
import List.Extra
import List
import Maybe.Extra
import String
import Json.Decode as Decode
import Tuple3
import NaturalOrdering as N

import GroupCompliance.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)
import Compliance.Utils exposing (..)
import Ui.Datatable exposing (SortOrder(..))


isGlobalCompliance : Model -> Bool
isGlobalCompliance model =
  case model.complianceScope of
    GlobalCompliance -> True
    TargetedCompliance -> False

onCustomClick : msg -> Html.Attribute msg
onCustomClick msg =
  custom "click"
    (Decode.succeed
      { message         = msg
      , stopPropagation = True
      , preventDefault  = True
      }
    )
--
-- DATATABLES & TREES
--
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

valueCompliance : ComplianceFilters -> ItemFun ValueCompliance () ValueCompliance
valueCompliance complianceFilters =
  ItemFun
    (\ _ _ _ -> [])
    (\_ i -> i)
    [ ("Value"   , .value   >> (\v -> div[class "value-container font-monospace"][text v]), (\d1 d2 -> N.compare d1.value  d2.value))
    , ("Messages", .reports >> List.filter (filterReports complianceFilters) >> List.map (\r -> Maybe.withDefault "" r.message) >> List.map (\t -> text t) >> List.intersperse (br [] []) >> (\ls -> pre[class "font-monospace"] ls ), (\d1 d2 -> N.compare d1.value d2.value) )
    , ("Status"  , .reports >> List.filter (filterReports complianceFilters) >> buildComplianceReport, (\d1 d2 -> Basics.compare d1.value d2.value))
    ]
    .value
    Nothing
    (always [])
    (filterReportsByCompliance complianceFilters)

byComponentCompliance : ItemFun value subValue valueData -> ComplianceFilters -> ItemFun (ComponentCompliance value) (Either (ComponentCompliance value) value) (ComponentCompliance value)
byComponentCompliance subFun complianceFilters =
  let
    name = \item ->
      case item of
        Compliance.DataTypes.Block b -> b.component
        Compliance.DataTypes.Value c -> c.component
    compliance = \item ->
      case item of
        Compliance.DataTypes.Block b -> b.complianceDetails
        Compliance.DataTypes.Value c -> c.complianceDetails
  in
    ItemFun
    ( \item model sortId ->
      case item of
        Compliance.DataTypes.Block b ->
          let
            sortFunction =  subItemOrder (byComponentCompliance subFun complianceFilters) model sortId
          in
            b.components
            |> List.filter (filterByCompliance complianceFilters)
            |> List.sortWith sortFunction
            |> List.map Left
        Compliance.DataTypes.Value c ->
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
    , ("Compliance", \i -> buildComplianceBar complianceFilters (compliance i), (\d1 d2 -> Basics.compare (name d1) (name d2)) )
    ]
    name
    (Just ( \x ->
      case x of
        Left  value -> showComplianceDetails (byComponentCompliance subFun complianceFilters) value
        Right value -> showComplianceDetails subFun value
    ))
    ( \x ->
      case x of
        Compliance.DataTypes.Block _ -> (List.map Tuple3.first (byComponentCompliance subFun complianceFilters).rows)
        Compliance.DataTypes.Value _ ->  (List.map Tuple3.first subFun.rows)
    )
    (always True)

byNodeCompliance : Model -> ComplianceFilters -> ItemFun NodeCompliance (RuleCompliance ValueCompliance) NodeCompliance
byNodeCompliance mod complianceFilters =
  let
    rule = byRuleCompliance mod (valueCompliance complianceFilters) complianceFilters
  in
  ItemFun
    (\item model sortId ->
      let
        sortFunction = subItemOrder rule mod sortId
      in
        item.rules
        |> List.filter (filterDetailsByCompliance complianceFilters)
        |> List.sortWith sortFunction
    )
    (\m i -> i)
    [ ("Node", (\nId -> span[][ (badgePolicyMode mod.policyMode nId.policyMode), text nId.name, goToBtn (getNodeLink mod.contextPath nId.nodeId.value)]),  (\n1 n2 -> N.compare n1.name n2.name))
    , ("Compliance", .complianceDetails >> buildComplianceBar complianceFilters,  (\n1 n2 -> Basics.compare n1.compliance n2.compliance))
    ]
    (.nodeId >> .value)
    (Just (\b -> showComplianceDetails rule b))
    (always (List.map Tuple3.first rule.rows))
    (always True)

byRuleCompliance : Model -> ItemFun value subValue valueData -> ComplianceFilters -> ItemFun (RuleCompliance value) (DirectiveCompliance value) (RuleCompliance value)
byRuleCompliance model subFun complianceFilters =
  let
    contextPath  = model.contextPath
    directive = byDirectiveCompliance model complianceFilters subFun
  in
    ItemFun
    (\item _ sortId ->
    let
      sortFunction = subItemOrder directive model sortId
    in
      item.directives
      |> List.filter (filterDetailsByCompliance complianceFilters)
      |> List.sortWith sortFunction
    )
    (\_ i ->  i )
    [ ("Rule", \i  -> span [] [ (badgePolicyMode model.policyMode i.policyMode), text i.name , goToBtn (getRuleLink contextPath i.ruleId) ],  (\r1 r2 -> N.compare r1.name r2.name ))
    , ("Compliance", \i -> buildComplianceBar complianceFilters  i.complianceDetails,  (\(r1) (r2) -> Basics.compare r1.compliance r2.compliance ))
    ]
    (.ruleId >> .value)
    (Just (\b -> showComplianceDetails directive b))
    (always (List.map Tuple3.first directive.rows))
    (always True)

byDirectiveCompliance : Model -> ComplianceFilters -> ItemFun value subValue valueData -> ItemFun (DirectiveCompliance value) (ComponentCompliance value) (DirectiveCompliance value)
byDirectiveCompliance mod complianceFilters subFun =
  let
    contextPath  = mod.contextPath
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
    (\_ i -> i)
    [ ("Directive", \i -> span [] [ i.skippedDetails |> Maybe.map badgeSkipped |> Maybe.withDefault (badgePolicyMode mod.policyMode i.policyMode), text i.name, goToBtn (getDirectiveLink contextPath i.directiveId) ],  (\d1 d2 -> N.compare d1.name d2.name ))
    , ("Compliance", \i -> buildComplianceBar complianceFilters i.complianceDetails,  (\d1 d2 -> Basics.compare d1.compliance d2.compliance ))
    ]
    (.directiveId >> .value)
    (Just (\b -> showComplianceDetails (byComponentCompliance subFun complianceFilters) b))
    (always (List.map Tuple3.first (byComponentCompliance subFun complianceFilters).rows))
    (always True)

nodeValueCompliance : Model -> ComplianceFilters -> ItemFun NodeValueCompliance ValueCompliance NodeValueCompliance
nodeValueCompliance mod complianceFilters =
  ItemFun
    (\item model sortId ->
      let
        sortFunction =  subItemOrder (valueCompliance complianceFilters) model sortId
      in
        item.values
        |> List.filter (filterReportsByCompliance complianceFilters)
        |> List.sortWith sortFunction
    )
    (\_ i -> i)
    [ ("Node", (\nId -> span[][ (badgePolicyMode mod.policyMode nId.policyMode), text nId.name, goToBtn (getNodeLink mod.contextPath nId.nodeId.value) ]),  (\d1 d2 -> N.compare d1.name d2.name))
    , ("Compliance", .complianceDetails >> buildComplianceBar complianceFilters ,  (\d1 d2 -> Basics.compare d1.compliance d2.compliance))
    ]
    (.nodeId >> .value)
    (Just (\item -> showComplianceDetails (valueCompliance complianceFilters) item))
    (always (List.map Tuple3.first (valueCompliance complianceFilters).rows))
    (filterDetailsByCompliance complianceFilters)

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
            (children, order, newOrder) = case sortOrder of
              Asc -> (childrenSort, "asc", Desc)
              Desc -> (List.reverse childrenSort, "desc", Asc)
          in
            (
            [ tr [ class "details" ]
              [ td [ class "details", colspan 2 ]
                [ div [ class "innerDetails" ]
                  [
                   table [class "dataTable compliance-table"] [
                   thead [] [
                     tr [ class "head" ]
                     (List.map (\row -> th [onClick (ToggleRowSort rowId row (if row == sortId then newOrder else Asc)) , class ("sorting" ++ (if row == sortId then "_"++order else "")) ] [ text row ]) (fun.subItemRows compliance) )
                   ]
                , tbody []
                  ( if(List.isEmpty children) then
                    [ tr [] [ td [colspan 2, class "dataTables_empty" ] [ text "There is no compliance details" ] ] ]
                  else
                    List.concatMap (\child ->
                      (detailsFun child) rowId openedRows model
                    ) children
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

searchFieldRuleCompliance r =
  [ r.ruleId.value
  , r.name
  ]

searchFieldNodeCompliance n =
  [ n.nodeId.value
  , n.name
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
        "successNotApplicable"       -> "Not applicable"
        "applying"                   -> "Applying"
        "auditNotApplicable"         -> "Not applicable"
        "unexpectedUnknownComponent" -> "Unexpected"
        "unexpectedMissingComponent" -> "Missing"
        "enforceNotApplicable"         -> "Not applicable"
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

getRuleLink : String -> RuleId -> String
getRuleLink contextPath id =
  contextPath ++ "/secure/configurationManager/ruleManagement/rule/" ++ id.value

getDirectiveLink : String -> DirectiveId -> String
getDirectiveLink contextPath id =
  contextPath ++ """/secure/configurationManager/directiveManagement#{"directiveId":" """++ id.value ++ """ "} """

getNodeLink : String -> String -> String
getNodeLink contextPath id =
  contextPath ++ "/secure/nodeManager/node/" ++ id


goToBtn : String -> Html Msg
goToBtn link =
  a [ class "btn-goto", href link , onCustomClick (GoTo link)] [ i[class "fa fa-pen"][] ]

goToIcon : Html Msg
goToIcon =
  span [ class "btn-goto" ] [ i[class "fa fa-pen"][] ]

filtersView : Model -> Html Msg
filtersView model = 
  let 
    filters = model.ui.ruleFilters
    complianceFilters = model.ui.complianceFilters
    complianceScope = model.complianceScope
    isGlobalMode = isGlobalCompliance model
  in 
    div [class "table-header extra-filters"]
      [ div [class "d-inline-flex align-items-baseline pb-3 w-25"]
        [
          div [class "btn-group yesno"]
          [ label 
            [ class ("btn btn-default" ++ if isGlobalMode then " active" else "")
              , attribute "data-bs-toggle" "tooltip"
              , attribute "data-bs-placement" "top"
              , attribute "data-bs-html" "true"
              , attribute "title" (buildTooltipContent "Global compliance" "This mode will show the compliance of all rules that apply directives to a node within this group.")
              , onClick (LoadCompliance GlobalCompliance)
            ]
            [ text "Global"
            ]
          , label 
            [ class ("btn btn-default" ++ if isGlobalMode then "" else " active")
              , attribute "data-bs-toggle" "tooltip"
              , attribute "data-bs-placement" "top"
              , attribute "data-bs-html" "true"
              , attribute "title" (buildTooltipContent "Targeted compliance" "This mode will show only the compliance of rules that explicitly include this group in their target.")
              , onClick (LoadCompliance TargetedCompliance)
            ]
            [ text "Targeted"
            ]
          ]
        ]
      , div [class "main-filters"]
        [ input [type_ "text", placeholder "Filter", class "input-sm form-control", value filters.filter
          , onInput (\s -> (UpdateFilters {filters | filter = s} ))][]
        , button [class "btn btn-default btn-sm btn-icon", onClick (UpdateComplianceFilters {complianceFilters | showComplianceFilters = not complianceFilters.showComplianceFilters}), style "min-width" "170px"]
          [ text ((if complianceFilters.showComplianceFilters then "Hide " else "Show ") ++ "compliance filters")
          , i [class ("fa " ++ (if complianceFilters.showComplianceFilters then "fa-minus" else "fa-plus"))][]
          ]
        , button [class "btn btn-default btn-sm btn-refresh", onClick (RefreshCompliance complianceScope)]
          [ i [class "fa fa-refresh"][] ]
        ]
      , displayComplianceFilters complianceFilters UpdateComplianceFilters
      ]