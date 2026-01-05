module Groups.ViewGroupsTable exposing (..)

import Dict exposing (Dict)
import Maybe
import Groups.DataTypes exposing (..)
import Rudder.Table


toGroupWithCompliance : Dict String GroupComplianceSummary -> Group -> GroupWithCompliance
toGroupWithCompliance groupsCompliance group =
    let
        compliance =
            Dict.get group.id.value groupsCompliance
    in
    { id = group.id
    , name = group.name
    , category = group.category
    , globalCompliance = Maybe.map .global compliance
    , targetedCompliance = Maybe.map .targeted compliance
    }

updateGroupsTableData : Model -> Model
updateGroupsTableData model =
    let
        groupsList = getElemsWithCompliance model
        data =
            List.map
                (toGroupWithCompliance model.groupsCompliance)
                groupsList
    in
    {model | groupsTable = Rudder.Table.updateData data model.groupsTable}
