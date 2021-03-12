port module Secret exposing (..)

import ApiCalls exposing (addSecret, updateSecret)
import Browser
import DataTypes exposing (Model, Msg(..), StateInput(..), WriteAction(..))
import List exposing (filter, length, map)
import Http exposing (Error)
import Init exposing (init, subscriptions)
import List.Extra exposing (setIf)
import String exposing (isEmpty)
import View exposing (view)
--import Init exposing (createErrorNotification)



main =
    Browser.element
        { init = init
        , view = view
        , update = update
        , subscriptions = subscriptions
        }

    --= GetAllSecrets (Result Error (List Secret))
    --| GetSecret (Result Error Secret)
    --| AddSecret (Result Error Secret)
    --| DeleteSecret (Result Error String)
    --| UpdateSecret (Result Error Secret)
    --| ReloadSecrets
    --| CallApi (Model -> Cmd Msg)
update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    CallApi call ->
      (model, call model)
    GetAllSecrets secrets ->
      case secrets of
        Ok s ->
          ({ model | secrets = []}, Cmd.none)
        Err err ->
          processApiError err model
    GetSecret secret ->
      case secret of
        Ok s ->
          ({model | focusOn = Just s}, Cmd.none)
        Err err ->
          processApiError err model
    AddSecret secret ->
      case secret of
        Ok s ->
          ({model | secrets = s :: model.secrets}, Cmd.none)
        Err err ->
          processApiError err model
    DeleteSecret name ->
      case name of
        Ok n ->
          let
            filteredSecrets = filter (\s -> s.name /= n) model.secrets
          in
          ({model | secrets = filteredSecrets}, Cmd.none)
        Err err ->
          processApiError err model
    UpdateSecret secret ->
      case secret of
        Ok newSecret ->
          let
            updatedSecrets = setIf (\s -> s.name == newSecret.name) newSecret model.secrets
          in
          ({model | secrets = updatedSecrets}, Cmd.none)
        Err err ->
          processApiError err model
    OpenCreateModal ->
      ({model | isOpenCreateModal = True, isOpenEditModal = False}, Cmd.none)
    OpenEditModal secret ->
      ({model | isOpenCreateModal = False, isOpenEditModal = True, focusOn = Just secret}, Cmd.none)
    CloseModal ->
      ({model | isOpenCreateModal = False, isOpenEditModal = False, focusOn = Nothing }, Cmd.none)
    InputName name ->
      case model.newSecretInput of
        Just s ->
          ({model | newSecretInput = Just (DataTypes.Secret name s.value)}, Cmd.none)
        Nothing ->
         ({model | newSecretInput = Just (DataTypes.Secret name "")}, Cmd.none)

    InputValue value ->
      case model.newSecretInput of
        Just s ->
          ({model | newSecretInput = Just (DataTypes.Secret s.name value)}, Cmd.none)
        Nothing ->
         ({model | newSecretInput = Just (DataTypes.Secret "" value)}, Cmd.none)
    InputDescription description ->
      (model , Cmd.none)
    SubmitSecret action ->
      case model.newSecretInput of
        Just s ->
          if (isEmpty s.name) then
            ({model | stateInputs = InvalidName :: model.stateInputs}, Cmd.none)
          else if (isEmpty s.value) then
            ({model | stateInputs = InvalidValue :: model.stateInputs}, Cmd.none)
                --else if (isEmpty s.description) then
                --   ({model | stateInputs = InvalidDescription :: model.stateInputs}, Cmd.none)
          else
            case action of
              Add ->

                ({model | secrets = s :: model.secrets, isOpenCreateModal = False}, addSecret model s)
              Edit ->
                let
                  newSecrets = setIf (\oldS -> oldS.name == s.name) s model.secrets
                in
                ({model | secrets = newSecrets, isOpenEditModal = False}, updateSecret model s)
        Nothing ->
          (model, Cmd.none)
    OpenDescription s ->
      let
        openedDesc = filter (\o -> o /= s.name) model.openedDescription
      in
      if (length openedDesc) == (length model.openedDescription) then
        ({model | openedDescription = s.name :: openedDesc}, Cmd.none)
      else
        ({model | openedDescription = openedDesc}, Cmd.none)

processApiError : Error -> Model -> ( Model, Cmd Msg )
processApiError err model =
    --let
    --    newModel =
            ({ model | secrets = []}, Cmd.none)
    --in
    --( newModel, Cmd.none ) |> createErrorNotification "Error while trying to fetch settings." err