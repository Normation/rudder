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
import ApiCalls exposing (..)
import ComplianceUtils exposing (..)
import Json.Decode as Decode
import Tuple3

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
    span [class ("treeGroupName tooltipable bs-tooltip rudder-label label-sm label-" ++ mode), attribute "data-toggle" "tooltip", attribute "data-placement" "bottom", attribute "data-container" "body", attribute "data-html" "true", attribute "data-original-title" (buildTooltipContent "Policy mode" msg)][]

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

byComponentCompliance : ItemFun value subValue valueData -> ItemFun (ComponentCompliance value) (Either (ComponentCompliance value) value) (ComponentCompliance value)
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
        Left  value -> showComplianceDetails (byComponentCompliance subFun) value
        Right value -> showComplianceDetails subFun value
    ))
    ( \x ->
      case x of
        Block _ -> (List.map Tuple3.first (byComponentCompliance subFun).rows)
        Value _ ->  (List.map Tuple3.first subFun.rows)
    )

byNodeCompliance :  Model -> ItemFun NodeCompliance (RuleCompliance ValueCompliance) NodeCompliance
byNodeCompliance mod =
  let
    rule = byRuleCompliance mod valueCompliance
  in
  ItemFun
    (\item model sortId ->
      let
        sortFunction = subItemOrder rule mod sortId
      in
        List.sortWith sortFunction item.rules
    )
    (\m i -> i)
    [ ("Node", (\nId -> span[][ (badgePolicyMode mod.policyMode nId.policyMode), text nId.name, goToBtn (getNodeLink mod.contextPath nId.nodeId.value)]),  (\n1 n2 -> compare n1.name n2.name))
    , ("Compliance", .complianceDetails >> buildComplianceBar,  (\n1 n2 -> compare n1.compliance n2.compliance))
    ]
    (.nodeId >> .value)
    (Just (\b -> showComplianceDetails rule b))
    (always (List.map Tuple3.first rule.rows))


byRuleCompliance : Model -> ItemFun value subValue valueData -> ItemFun (RuleCompliance value) (ComponentCompliance value) (RuleCompliance value)
byRuleCompliance model subFun =
  let
    contextPath  = model.contextPath
  in
    ItemFun
    (\item m sortId ->
    let
      sortFunction = subItemOrder (byComponentCompliance subFun) m sortId
    in
      List.sortWith sortFunction item.components
    )
    (\m i ->  i )
    [ ("Rule", \i  -> span [] [ (badgePolicyMode model.policyMode (Maybe.map .policyMode model.directiveCompliance|> Maybe.withDefault "default")), text i.name , goToBtn (getRuleLink contextPath i.ruleId) ],  (\r1 r2 -> compare r1.name r2.name ))
    , ("Compliance", \i -> buildComplianceBar  i.complianceDetails,  (\(r1) (r2) -> compare r1.compliance r2.compliance ))
    ]
    (.ruleId >> .value)
    (Just (\b -> showComplianceDetails (byComponentCompliance subFun) b))
    (always (List.map Tuple3.first (byComponentCompliance subFun).rows))

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
    [ ("Node", (\nId -> span[][text nId.name, goToBtn (getNodeLink mod.contextPath nId.nodeId.value)]),  (\d1 d2 -> compare d1.name d2.name))
    , ("Compliance", .complianceDetails >> buildComplianceBar ,  (\d1 d2 -> compare d1.compliance d2.compliance))
    ]
    (.nodeId >> .value)
    (Just (\item -> showComplianceDetails valueCompliance item))
    (always (List.map Tuple3.first valueCompliance.rows))

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