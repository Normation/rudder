port module GlobalPropertiesHistory exposing (..)

import Browser
import Dict
import EventLogs.ApiCalls exposing (getEventLogs, processEventLogsApiError)
import EventLogs.DataTypes exposing (ContextPath(..), EventLog, EventLogsMsg(..), string2Search)
import EventLogs.Table exposing (initTable)
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
    , historyTable : Rudder.Table.Model EventLog Msg
    , contextPath : ContextPath
    , zone : Zone
    }


type Msg
    = CallApi (Model -> Cmd Msg)
    | RudderTableMsg (Rudder.Table.Msg Msg)
    | HistoryMessage EventLogsMsg


init :
    { globalPropertyId : String
    , contextPath : String
    , timeZone : String
    , canReadChangeLogs : Bool
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
            , historyTable = initTable flags.canReadChangeLogs contextPath zone
            , contextPath = contextPath
            , zone = zone
            }

        -- full text search on directive id to get history related to this global property
        search =
            string2Search flags.globalPropertyId

        initActions =
            [ Cmd.map HistoryMessage (getEventLogs search 100 initModel.contextPath (Just "parameters")) ]
    in
    ( initModel, Cmd.batch initActions )


tableView : Rudder.Table.Model EventLog Msg -> Html Msg
tableView tableModel =
    if Rudder.Table.getRows tableModel == [] then
        text "-"

    else
        div
            [ class "main-table" ]
            [ div [ class "parameterRecentActivityTable" ] [ Html.map RudderTableMsg (Rudder.Table.view tableModel) ] ]


view : Model -> Html Msg
view model =
    tableView model.historyTable


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        CallApi call ->
            ( model, call model )

        RudderTableMsg m ->
            let
                ( historyTable, tableMsg, _ ) =
                    Rudder.Table.update m model.historyTable
            in
            ( { model | historyTable = historyTable }, tableMsg )

        HistoryMessage a ->
            case a of
                GetEventLogs res ->
                    case res of
                        -- Update table data
                        Ok ( _, history ) ->
                            let
                                updatedTable =
                                    updateData history model.historyTable
                            in
                            ( { model | historyTable = updatedTable }, Cmd.none )

                        Err err ->
                            ( model, processEventLogsApiError "Getting event logs list" err errorNotification )

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
