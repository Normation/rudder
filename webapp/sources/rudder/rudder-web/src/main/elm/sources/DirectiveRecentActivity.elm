port module DirectiveRecentActivity exposing (..)

import Activity.ActivityTable exposing (initTable)
import Activity.ApiCalls exposing (getActivities, processActivityApiError)
import Activity.DataTypes exposing (Activity, ActivityMsg(..), ContextPath(..), Search, string2Search)
import Browser
import Dict
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
    , activityTable : Rudder.Table.Model Activity Msg
    , contextPath : ContextPath
    , zone : Zone
    }


type Msg
    = CallApi (Model -> Cmd Msg)
    | RudderTableMsg (Rudder.Table.Msg Msg)
    | ActivityMessage ActivityMsg


init :
    { directiveId : String
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
            { directiveId = DirectiveId flags.directiveId
            , activityTable = initTable (ContextPath flags.contextPath) zone
            , contextPath = ContextPath flags.contextPath
            , zone = zone
            }

        -- full text search on directive id to keep activity related to this directive
        search =
            string2Search flags.directiveId

        initActions =
            [ Cmd.map ActivityMessage (getActivities search initModel.contextPath (Just "directives")) ]
    in
    ( initModel, Cmd.batch initActions )



{- Table of the recent activity -}


table : Model -> Html Msg
table model =
    div [ class "main-table" ] [ Html.map RudderTableMsg (Rudder.Table.view model.activityTable) ]


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
