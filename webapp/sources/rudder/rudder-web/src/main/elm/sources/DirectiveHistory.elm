port module DirectiveHistory exposing (..)

import Browser
import Dict
import EventLogs.ApiCalls exposing (getEventLogs, processEventLogsApiError)
import EventLogs.DataTypes exposing (ContextPath(..), EventLog, EventLogsMsg(..), Search, string2ObjectId)
import EventLogs.Table exposing (initTable)
import Html exposing (Html, div)
import Html.Attributes exposing (class)
import Rudder.Table exposing (..)
import Time exposing (Posix, Zone)
import TimeZone


port errorNotification : String -> Cmd msg


port copy : String -> Cmd msg


type DirectiveId
    = DirectiveId String


type alias Model =
    { directiveId : DirectiveId
    , historyTable : Rudder.Table.Model EventLog Msg
    , contextPath : ContextPath
    , zone : Zone
    }


type Msg
    = CallApi (Model -> Cmd Msg)
    | RudderTableMsg (Rudder.Table.Msg Msg)
    | HistoryMessage EventLogsMsg


init :
    { directiveId : String
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

        initModel : Model
        initModel =
            { directiveId = DirectiveId flags.directiveId
            , historyTable = initTable flags.canReadChangeLogs (ContextPath flags.contextPath) zone
            , contextPath = ContextPath flags.contextPath
            , zone = zone
            }

        -- directive id to keep history related to this directive
        id =
            string2ObjectId flags.directiveId

        initActions =
            [ Cmd.map HistoryMessage (getEventLogs id 100 initModel.contextPath (Just "directives")) ]
    in
    ( initModel, Cmd.batch initActions )


table : Model -> Html Msg
table model =
    div [ class "main-table" ] [ Html.map RudderTableMsg (Rudder.Table.view model.historyTable) ]


view : Model -> Html Msg
view model =
    table model


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
