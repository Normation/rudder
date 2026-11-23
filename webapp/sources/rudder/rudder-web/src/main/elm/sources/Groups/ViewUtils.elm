module Groups.ViewUtils exposing (..)

import Compliance.Utils exposing (getAllComplianceValues)
import Dict exposing (Dict)
import GroupCompliance.DataTypes exposing (GroupId)
import Groups.DataTypes exposing (..)
import Html exposing (Attribute, Html, div, i, li, span, table, tbody, td, th, thead, tr, ul)
import Html.Attributes exposing (class, style)
import List.Extra
import Maybe
import Maybe.Extra
import NaturalOrdering
import Round
import Rudder.Table exposing (buildOptions)
import Ui.Datatable exposing (Category, SortOrder(..), SubCategories(..), getAllCats)


getCategoryName : Category Group -> String -> String
getCategoryName groupTree id =
    let
        cat =
            List.Extra.find (.id >> (==) id) (getAllCats groupTree)
    in
    case cat of
        Just c ->
            c.name

        Nothing ->
            id


searchFieldGroups : Group -> Model -> List String
searchFieldGroups g model =
    [ g.id.value
    , g.name
    ]
        ++ Maybe.Extra.toList g.category
        ++ Maybe.Extra.toList (Maybe.map (getCategoryName model.groupsTree) g.category)


generateLoadingList : Html Msg
generateLoadingList =
    ul [ class "skeleton-loading" ]
        [ li [ style "width" "calc(100% - 25px)" ] [ i [] [], span [] [] ]
        , li [] [ i [] [], span [] [] ]
        , li [ style "width" "calc(100% - 95px)" ] [ i [] [], span [] [] ]
        , ul []
            [ li [ style "width" "calc(100% - 45px)" ] [ i [] [], span [] [] ]
            , li [ style "width" "calc(100% - 125px)" ] [ i [] [], span [] [] ]
            , li [] [ i [] [], span [] [] ]
            ]
        , li [] [ i [] [], span [] [] ]
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
        foldedList =
            if List.member catId filters.folded then
                List.Extra.remove catId filters.folded

            else
                catId :: filters.folded
    in
    { filters | folded = foldedList }


getIdAnchorKey : String -> String
getIdAnchorKey id =
    if String.startsWith "special:" id || String.startsWith "policyServer:" id then
        "target"

    else
        "groupId"


getGroupLink : String -> String -> String
getGroupLink contextPath id =
    let
        anchorKey =
            getIdAnchorKey id
    in
    contextPath ++ """/secure/nodeManager/groups#{\"""" ++ anchorKey ++ """":\"""" ++ id ++ """"}"""


hasMoreGroups : Model -> Bool
hasMoreGroups model =
    let
        countTreeElements : Category Group -> Int
        countTreeElements category =
            let
                subElems =
                    case category.subElems of
                        SubCategories l ->
                            l
            in
            List.length category.elems + List.sum (List.map countTreeElements subElems)
    in
    countTreeElements model.groupsTree > Dict.size model.groupsCompliance


{-| Update both the groupsTable and csvExportOptions when the model's groupsCompliance is fetched and updated.
-}
updateGroupsTableData : Model -> Model
updateGroupsTableData model =
    let
        groupsList =
            getElemsWithCompliance model

        data =
            List.map
                (toGroupWithCompliance model.groupsCompliance model.groupsTree)
                groupsList
    in
    { model
        | groupsTable = Rudder.Table.updateData data model.groupsTable
        , mode =
            if model.mode == LoadingTable then
                GroupTable

            else
                model.mode
    }


toGroupWithCompliance : Dict String GroupComplianceSummary -> Category Group -> Group -> GroupWithCompliance
toGroupWithCompliance groupsCompliance groupTree group =
    let
        compliance =
            Dict.get group.id.value groupsCompliance

        category =
            Maybe.map (getCategoryName groupTree) group.category
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
            if complianceDataAvailable compliance then
                Round.round 2 compliance.compliance ++ "%"

            else
                "No data available"

        Nothing ->
            "Loading..."


complianceDataAvailable : ComplianceSummaryValue -> Bool
complianceDataAvailable compliance =
    let
        allComplianceValues =
            getAllComplianceValues compliance.complianceDetails
    in
    if
        allComplianceValues.okStatus.value
            + allComplianceValues.nonCompliant.value
            + allComplianceValues.error.value
            + allComplianceValues.unexpected.value
            + allComplianceValues.pending.value
            + allComplianceValues.reportsDisabled.value
            + allComplianceValues.noReport.value
            == 0
    then
        False

    else
        True
