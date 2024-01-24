module DirectiveCompliance.ViewUtils exposing (..)

import Dict exposing (Dict)
import Either exposing (Either(..))
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onClick, custom)
import List.Extra
import List
import Maybe.Extra
import String
import Json.Decode as Decode
import Tuple3
import NaturalOrdering as N

import DirectiveCompliance.DataTypes exposing (..)
import Compliance.DataTypes exposing (..)
import Compliance.Utils exposing (..)
import Compliance.Html exposing (buildComplianceBar)

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
    [ ("Value"   , .value   >> text, (\d1 d2 -> N.compare d1.value  d2.value))
    , ("Messages", .reports >> List.filter (filterReports complianceFilters) >> List.map (\r -> Maybe.withDefault "" r.message) >> List.foldl (++) "\n"  >> text, (\d1 d2 -> N.compare d1.value d2.value) )
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
            sortFunction =  subItemOrder (byComponentCompliance subFun complianceFilters) model sortId
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
        Block _ -> (List.map Tuple3.first (byComponentCompliance subFun complianceFilters).rows)
        Value _ ->  (List.map Tuple3.first subFun.rows)
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

byRuleCompliance : Model -> ItemFun value subValue valueData -> ComplianceFilters -> ItemFun (RuleCompliance value) (ComponentCompliance value) (RuleCompliance value)
byRuleCompliance model subFun complianceFilters =
  let
    contextPath  = model.contextPath
  in
    ItemFun
    (\item m sortId ->
    let
      sortFunction = subItemOrder (byComponentCompliance subFun complianceFilters) m sortId
    in
      item.components
      |> List.filter (filterByCompliance complianceFilters)
      |> List.sortWith sortFunction
    )
    (\m i ->  i )
    [ ("Rule", \i  -> span [] [ (badgePolicyMode model.policyMode (Maybe.map .policyMode model.directiveCompliance|> Maybe.withDefault "default")), text i.name , goToBtn (getRuleLink contextPath i.ruleId) ],  (\r1 r2 -> N.compare r1.name r2.name ))
    , ("Compliance", \i -> buildComplianceBar complianceFilters  i.complianceDetails,  (\(r1) (r2) -> Basics.compare r1.compliance r2.compliance ))
    ]
    (.ruleId >> .value)
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
    [ ("Node", (\nId -> span[][text nId.name, goToBtn (getNodeLink mod.contextPath nId.nodeId.value)]),  (\d1 d2 -> N.compare d1.name d2.name))
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


htmlEscape : String -> String
htmlEscape s =
  String.replace "&" "&amp;" s
    |> String.replace ">" "&gt;"
    |> String.replace "<" "&lt;"
    |> String.replace "\"" "&quot;"
    |> String.replace "'" "&#x27;"
    |> String.replace "\\" "&#x2F;"


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

getNodeLink : String -> String -> String
getNodeLink contextPath id =
  contextPath ++ "/secure/nodeManager/node/" ++ id


goToBtn : String -> Html Msg
goToBtn link =
  a [ class "btn-goto", href link , onCustomClick (GoTo link)] [ i[class "fa fa-pen"][] ]

goToIcon : Html Msg
goToIcon =
  span [ class "btn-goto" ] [ i[class "fa fa-pen"][] ]

generateLoadingTable : Html Msg
generateLoadingTable =
  div [class "table-container skeleton-loading", style "margin-top" "17px"]
  [ div [class "dataTables_wrapper_top table-filter"]
    [ div [class "form-group"]
      [ span[][]
      ]
    ]
  , table [class "dataTable"]
    [ thead []
      [ tr [class "head"]
        [ th [][ span[][] ]
        , th [][ span[][] ]
        ]
      ]
    , tbody []
      [ tr[] [ td[][span[style "width" "45%"][]], td[][span[][]]]
      , tr[] [ td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "30%"][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "75%"][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "45%"][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "70%"][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "80%"][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "30%"][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "75%"][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "45%"][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]] ]
      , tr[] [ td[][span[style "width" "70%"][]], td[][span[][]] ]
      , tr[] [ td[][span[][]], td[][span[][]] ]
      ]
    ]
  ]
