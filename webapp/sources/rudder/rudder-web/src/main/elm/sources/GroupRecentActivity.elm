port module GroupRecentActivity exposing (..)

import Activity.ActivityTable exposing (initTable)
import Activity.ApiCalls exposing (getActivities, processActivityApiError)
import Activity.DataTypes exposing (Activity, ActivityMsg(..), BodyParameters, ContextPath(..), Search, string2Search)
import Browser
import Dict
import Html exposing (Html, div)
import Html.Attributes exposing (class)
import Rudder.Table exposing (ColumnName(..), updateData)
import Time exposing (Zone)
import TimeZone



-- PORTS / SUBSCRIPTIONS


port copy : String -> Cmd msg


port errorNotification : String -> Cmd msg


port initTooltips : String -> Cmd msg


port clearTooltips : String -> Cmd msg


type GroupId
    = GroupId String


type alias Model =
    { groupId : GroupId
    , activityTable : Rudder.Table.Model Activity Msg
    , contextPath : ContextPath
    , zone : Zone
    }


type Msg
    = CallApi (Model -> Cmd Msg)
    | RudderTableMsg (Rudder.Table.Msg Msg)
    | ActivityMessage ActivityMsg


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
    div [ class "main-table" ] [ Html.map RudderTableMsg (Rudder.Table.view model.activityTable) ]


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
            , activityTable = initTable (ContextPath flags.contextPath) zone
            , contextPath = ContextPath flags.contextPath
            , zone = zone
            }

        bodyParameters : BodyParameters
        bodyParameters =
            { search = string2Search flags.groupId

            -- Keep only groups activity filtering on event log types
            , filterTypes = [ "NodeGroupAdded", "NodeGroupDeleted", "NodeGroupModified" ]
            }

        initActions =
            [ Cmd.map ActivityMessage (getActivities bodyParameters initModel.contextPath) ]
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
