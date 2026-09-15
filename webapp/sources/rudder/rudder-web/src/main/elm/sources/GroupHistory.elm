port module GroupHistory exposing (..)

import Browser
import Dict
import EventLogs.ApiCalls exposing (getEventLogs, processEventLogsApiError)
import EventLogs.DataTypes exposing (ContextPath(..), EventLog, EventLogsMsg(..), Search, string2Search)
import EventLogs.Table exposing (initTable)
import Html exposing (Html, div)
import Html.Attributes exposing (class)
import Rudder.Table exposing (ColumnName(..), updateData)
import Time exposing (Zone)
import TimeZone



-- PORTS / SUBSCRIPTIONS


port copy : String -> Cmd msg


port errorNotification : String -> Cmd msg


type GroupId
    = GroupId String


type alias Model =
    { groupId : GroupId
    , historyTable : Rudder.Table.Model EventLog Msg
    , contextPath : ContextPath
    , zone : Zone
    }


type Msg
    = CallApi (Model -> Cmd Msg)
    | RudderTableMsg (Rudder.Table.Msg Msg)
    | HistoryMessage EventLogsMsg


subscriptions : Model -> Sub Msg
subscriptions _ =
    Sub.none



-- default to global compliance


main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }


table : Model -> Html Msg
table model =
    div [ class "main-table" ] [ Html.map RudderTableMsg (Rudder.Table.view model.historyTable) ]


view : Model -> Html Msg
view model =
    table model


init :
    { groupId : String
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

        initModel : Model
        initModel =
            { groupId = GroupId flags.groupId
            , historyTable = initTable (ContextPath flags.contextPath) zone
            , contextPath = ContextPath flags.contextPath
            , zone = zone
            }

        search : Search
        search =
            string2Search flags.groupId

        initActions =
            [ Cmd.map HistoryMessage (getEventLogs search initModel.contextPath (Just "groups")) ]
    in
    ( initModel, Cmd.batch initActions )



--
-- update loop --
--


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
                        Ok ( _, activities ) ->
                            let
                                updatedTable =
                                    updateData activities model.historyTable
                            in
                            ( { model | historyTable = updatedTable }, Cmd.none )

                        Err err ->
                            ( model, processEventLogsApiError "Getting activities list" err errorNotification )

                CopyToClipboard s ->
                    ( model, copy s )
