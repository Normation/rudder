port module DirectiveRecentActivity exposing (..)

import Activity.ApiCalls exposing (getActivities)
import Activity.DataTypes exposing (Activity, ActivityMsg(..), ContextPath(..), Search(..))
import Activity.HtmlParserAdapter exposing (toHtml, toString)
import Activity.JsonDecoder exposing (decodeErrorDetails)
import Browser
import Dict
import Html exposing (Html, div, text)
import Html.Attributes exposing (class)
import Http.Detailed as Detailed
import List.Nonempty as NonEmptyList
import Ordering exposing (Ordering)
import Rudder.Table exposing (..)
import Time exposing (Posix, Zone)
import TimeZone
import Utils.DateUtils exposing (posixToString)


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


initTable : Zone -> Rudder.Table.Model Activity Msg
initTable timezone =
    let
        columns : NonEmptyList.Nonempty (Rudder.Table.Column Activity Msg)
        columns =
            NonEmptyList.Nonempty
                { name = ColumnName "Id", renderHtml = .id >> String.fromInt >> text, ordering = Ordering.byField .id }
                [ { name = ColumnName "Actor", renderHtml = .actor >> text, ordering = Ordering.byField .actor }
                , { name = ColumnName "Description"
                  , renderHtml = .description >> toHtml
                  , ordering = Ordering.byField (.description >> toString)
                  }
                , { name = ColumnName "Date", renderHtml = .date >> posixToString timezone >> text, ordering = Ordering.byField (.date >> Time.posixToMillis) }
                ]

        config =
            buildConfig.newConfig columns
                |> buildConfig.withOptions
                    (buildOptions.newOptions
                        |> buildOptions.withCustomizations
                            (buildCustomizations.newCustomizations
                                |> buildCustomizations.withTableContainerAttrs [ class "table-container" ]
                                |> buildCustomizations.withTableAttrs [ class "no-footer dataTable" ]
                            )
                    )
    in
    Rudder.Table.init config []


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

        initModel =
            Model (DirectiveId flags.directiveId) (initTable zone) (ContextPath flags.contextPath) zone

        -- Keep only directive activity filtering on event log types
        filterType =
            [ "DirectiveAdded", "DirectiveDeleted", "DirectiveModified" ]

        -- full text search on directive id to keep activity related to this directive
        search =
            Search flags.directiveId

        initActions =
            [ Cmd.map ActivityMessage (getActivities search filterType initModel.contextPath) ]
    in
    ( initModel, Cmd.batch initActions )



{- Table of the recent activity -}


table : Model -> Html Msg
table model =
    div [ class "main-table" ] [ Html.map RudderTableMsg (Rudder.Table.view model.activityTable) ]


view : Model -> Html Msg
view model =
    table model


processApiError : String -> Detailed.Error String -> Cmd msg
processApiError apiName err =
    let
        message =
            case err of
                Detailed.BadUrl url ->
                    "The URL " ++ url ++ " was invalid"

                Detailed.Timeout ->
                    "Unable to reach the server, try again"

                Detailed.NetworkError ->
                    "Unable to reach the server, check your network connection"

                Detailed.BadStatus _ body ->
                    let
                        ( title, errors ) =
                            decodeErrorDetails body
                    in
                    title ++ "\n" ++ errors

                Detailed.BadBody _ _ msg ->
                    msg
    in
    errorNotification ("Error when " ++ apiName ++ ", details: \n" ++ message)


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
                            ( model, processApiError "Getting activities list" err )

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
