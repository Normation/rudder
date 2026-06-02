module Groups.ViewUtils exposing (..)

import Dict exposing (Dict)
import Maybe
import Maybe.Extra
import NaturalOrdering
import List.Extra

import Html exposing (Attribute, Html, b, div, span, table, tbody, td, text, th, thead, tr)
import Html.Attributes exposing (attribute, class, disabled, style, title)
import Html exposing (ul, li, i)

import Groups.DataTypes exposing (..)

import Compliance.Utils exposing (getAllComplianceValues)
import GroupCompliance.DataTypes exposing (GroupId)
import Round
import Rudder.Table exposing (buildOptions)
import Ui.Datatable exposing (SortOrder(..), getAllCats, Category, SubCategories(..))


getCategoryName : Category Group -> String -> String
getCategoryName groupTree id =
  let
    cat = List.Extra.find (.id >> (==) id  ) (getAllCats groupTree)
  in
    case cat of
      Just c -> c.name
      Nothing -> id

searchFieldGroups : Group -> Model -> List String
searchFieldGroups g model =
  [ g.id.value
  , g.name
  ] ++ Maybe.Extra.toList g.category ++ Maybe.Extra.toList (Maybe.map (getCategoryName model.groupsTree) g.category)

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

foldedClass : Filters -> String -> String
foldedClass filters catId =
  if List.member catId filters.folded then
    " jstree-closed"
  else
    " jstree-open"

foldUnfoldCategory : Filters -> String -> Filters
foldUnfoldCategory filters catId =
  let
    foldedList  =
      if List.member catId filters.folded then
        List.Extra.remove catId filters.folded
      else
        catId :: filters.folded
  in
    {filters | folded = foldedList}

getIdAnchorKey : String -> String
getIdAnchorKey id =
  if String.startsWith "special:" id || String.startsWith "policyServer:" id then
    "target"
  else
    "groupId"

getGroupLink : String -> String -> String
getGroupLink contextPath id =
  let
    anchorKey = getIdAnchorKey id
  in
    contextPath ++ """/secure/nodeManager/groups#{\"""" ++ anchorKey ++ """\":\"""" ++ id ++ """\"}"""

hasMoreGroups : Model -> Bool
hasMoreGroups model =
  let
    countTreeElements : Category Group -> Int
    countTreeElements category =
      let
        subElems = case category.subElems of SubCategories l -> l
      in
        (List.length category.elems) + List.sum (List.map countTreeElements subElems)
  in
    (countTreeElements model.groupsTree) > (Dict.size model.groupsCompliance)

{-| Update both the groupsTable and csvExportOptions when the model's groupsCompliance is fetched and updated. -}
updateGroupsTableData : Model -> Model
updateGroupsTableData model =
    let
        groupsList = getElemsWithCompliance model
        data =
            List.map
                (toGroupWithCompliance model.groupsCompliance model.groupsTree)
                groupsList

        size =  model.groupsTable |> Rudder.Table.getRows |> List.length
        {-
            For some reason, table data is not loaded if the user loads the elm app in the state where
            a given group's details are displayed on the right pane, e.g.
            by opening the link rudder/secure/nodeManager/groups#{"groupId":"all-nodes-with-cfengine-agent"}
            (i.e. without visiting rudder/secure/nodeManager/groups, and clicking on the group from the table or the tree).
            The "isDisabled" condition ensures that the export button is enabled if and only if table data is available.
        -}
        isDisabled = (size == 0) && (model.mode /= GroupTable)
        options =
            buildOptions.newOptions
            |> buildOptions.withCsvExport
                { entryToStringList = entryToStringList
                , btnAttributes = (if isDisabled then disabledCsvExportButtonAttributes else [class "btn-primary"])}

    in
    {model | groupsTable = Rudder.Table.updateData data model.groupsTable, mode = (if model.mode == LoadingTable then GroupTable else model.mode), csvExportOptions = options.csvExport }


disabledCsvExportButtonAttributes : List (Attribute msg)
disabledCsvExportButtonAttributes =
    [ disabled True
    , attribute "data-bs-toggle" "tooltip"
    , attribute "data-bs-placement" "bottom"
    , attribute
        "data-bs-original-title"
        "The groups tree was not loaded. Close the group details view on the right pane in order to load the groups tree."
    , style "pointer-events" "auto"]


toGroupWithCompliance : Dict String GroupComplianceSummary -> Category Group -> Group -> GroupWithCompliance
toGroupWithCompliance groupsCompliance groupTree group =
    let
        compliance = Dict.get group.id.value groupsCompliance
        category = Maybe.map (getCategoryName groupTree) group.category
    in
    { id = group.id
    , name = group.name
    , category = category
    , globalCompliance = Maybe.map .global compliance
    , targetedCompliance = Maybe.map .targeted compliance
    }

entryToStringList : GroupWithCompliance -> List String
entryToStringList group =
    [ group.name
    , group.category |> Maybe.withDefault ""
    , group.globalCompliance |> complianceToString
    , group.targetedCompliance |> complianceToString
    ]

complianceToString : Maybe ComplianceSummaryValue -> String
complianceToString complianceOpt =
    case complianceOpt of
        Just compliance ->
            if (complianceDataAvailable compliance)
            then (Round.round 2 compliance.compliance) ++ "%"
            else "No data available"
        Nothing ->
            "Loading..."


complianceDataAvailable : ComplianceSummaryValue -> Bool
complianceDataAvailable compliance =
    let allComplianceValues = getAllComplianceValues compliance.complianceDetails in
    if ( allComplianceValues.okStatus.value
        + allComplianceValues.nonCompliant.value
        + allComplianceValues.error.value
        + allComplianceValues.unexpected.value
        + allComplianceValues.pending.value
        + allComplianceValues.reportsDisabled.value
        + allComplianceValues.noReport.value == 0 ) then False else True


badgeSecurityTags : Maybe SecurityTag -> Html Msg
badgeSecurityTags mTag =
  case mTag of
    Nothing ->
      text ""
    Just Open ->
      text ""
    Just (ByTenants tenants) ->
      let
        tenantNames = String.join ", " tenants
        nbTenants   = List.length tenants
        label       = if nbTenants == 0 then "no tenants" else tenantNames
      in
      span
        [ class "tenants-label"
        , attribute "data-bs-toggle" "tooltip"
        , attribute "data-bs-placement" "top"
        , title ("Tenants: " ++ label)
        ]
        [ i [ class "fa fa-building" ] []
        , b [] [ text (String.fromInt nbTenants) ]
        ]
