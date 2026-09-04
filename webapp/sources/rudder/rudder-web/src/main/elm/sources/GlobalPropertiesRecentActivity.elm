port module GlobalPropertiesRecentActivity exposing (..)

import Activity.ActivityTable exposing (initTable)
import Activity.ApiCalls exposing (getActivities, processActivityApiError)
import Activity.DataTypes exposing (Activity, ActivityMsg(..), ContextPath(..), string2Id)
import Browser
import Dict
import Html exposing (Html, div, i, table, tbody, td, text, th, thead, tr)
import Html.Attributes exposing (class, colspan, rowspan)
import Rudder.Table exposing (updateData)
import Time exposing (Zone)
import TimeZone


port errorNotification : String -> Cmd msg


port copy : String -> Cmd msg


type GlobalPropertyId
    = GlobalPropertyId String


type alias Model =
    { globalPropertyId : GlobalPropertyId
    , activityTable : Rudder.Table.Model Activity Msg
    , contextPath : ContextPath
    , zone : Zone
    }


type Msg
    = CallApi (Model -> Cmd Msg)
    | RudderTableMsg (Rudder.Table.Msg Msg)
    | ActivityMessage ActivityMsg


init :
    { globalPropertyId : String
    , contextPath : String
    , timeZone : String
    }
    -> ( Model, Cmd Msg )
init flags =
    let
        initTimeZone =
            Dict.get flags.timeZone TimeZone.zones
                |> Maybe.withDefault (\() -> Time.utc)

        zone =
            initTimeZone ()

        contextPath =
            ContextPath flags.contextPath

        initModel : Model
        initModel =
            { globalPropertyId = GlobalPropertyId flags.globalPropertyId
            , activityTable = initTable contextPath zone
            , contextPath = contextPath
            , zone = zone
            }

        -- id filter on global property id to keep activity related to this global property
        globalPropertyId =
            string2Id flags.globalPropertyId

        initActions =
            [ Cmd.map ActivityMessage (getActivities globalPropertyId initModel.contextPath (Just "parameters")) ]
    in
    ( initModel, Cmd.batch initActions )



{- Table of the recent activity -}


tableView : Rudder.Table.Model Activity Msg -> Html Msg
tableView tableModel =
    if Rudder.Table.getRows tableModel == [] then
        text "-"

    else
        div
            [ class "main-table" ]
            [ div [ class "parameterRecentActivityTable" ] [ Html.map RudderTableMsg (Rudder.Table.view tableModel) ] ]


view : Model -> Html Msg
view model =
    tableView model.activityTable


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        CallApi call ->
            ( model, call model )

        RudderTableMsg m ->
            let
                ( activityTable, tableMsg, _ ) =
                    Rudder.Table.update m model.activityTable
            in
            ( { model | activityTable = activityTable }, tableMsg )

        ActivityMessage a ->
            case a of
                GetActivities res ->
                    case res of
                        -- Update table data
                        Ok ( _, activities ) ->
                            let
                                updatedTable =
                                    updateData activities model.activityTable
                            in
                            ( { model | activityTable = updatedTable }, Cmd.none )

                        Err err ->
                            ( model, processActivityApiError "Getting activities list" err errorNotification )

                CopyToClipboard s ->
                    ( model, copy s )


subscriptions _ =
    Sub.none


main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }
