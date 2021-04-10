port module Secret exposing (..)

import ApiCalls exposing (addSecret, deleteSecret, updateSecret)
import Browser
import DataTypes exposing (Column(..), Mode(..), Model, Msg(..), Sorting(..), StateInput(..))
import List exposing (filter, head, length, reverse, sortBy)
import Http exposing (Error)
import Init exposing (init, subscriptions)
import List.Extra exposing (setIf)
import String exposing (contains, isEmpty)
import Tuple exposing (first, second)
import View exposing (view)

main =
  Browser.element
    { init = init
    , view = view
    , update = update
    , subscriptions = subscriptions
    }

update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
  case msg of
    CallApi call ->
      (model, call model)
    GetAllSecrets secrets ->
      case secrets of
        Ok s ->
          ({ model | secrets = s}, Cmd.none)
        Err err ->
          processApiError err model
    GetSecret secrets ->
      case secrets of
        Ok s ->
          let
            sec = (head s)
          in
          case sec of
            Just _ ->
              ({model | focusOn =  sec}, Cmd.none)
            Nothing ->
              (model, Cmd.none)
        Err err ->
          processApiError err model
    AddSecret secrets ->
      case secrets of
        Ok s ->
            case (head s) of
              Just sec ->
                ({model | secrets = sec :: model.secrets, newSecretInput = Nothing}, Cmd.none)
              Nothing ->
                (model, Cmd.none)
        Err err ->
          processApiError err model
    DeleteSecret secrets ->
      case secrets of
        Ok s ->
          case (head s) of
            Just sec ->
              let
                filteredSecrets = filter (\present -> present.name /= sec.name) model.secrets
              in
              ({model | secrets = filteredSecrets}, Cmd.none)
            Nothing ->
              (model, Cmd.none)
        Err err ->
          processApiError err model
    UpdateSecret secrets ->
      case secrets of
        Ok newSecret ->
            case (head newSecret) of
                Just sec ->
                  let
                    updatedSecrets = setIf (\s -> s.name == sec.name) sec model.secrets
                  in
                  ({model | secrets = updatedSecrets, newSecretInput = Nothing}, Cmd.none)
                Nothing ->
                  (model, Cmd.none)
        Err err ->
          processApiError err model
    OpenModal action focusedSecret ->
      ({model | openModalMode = action, focusOn = focusedSecret}, Cmd.none)
    CloseModal ->
      ({model | openModalMode = Read, focusOn = Nothing, stateInputs = []}, Cmd.none)
    InputName name ->
      case model.newSecretInput of
        Just s  ->
          let
            secInfo = DataTypes.SecretInfo name s.info.description
          in
          ({model | newSecretInput = Just ((DataTypes.Secret secInfo s.value))}, Cmd.none)
        Nothing ->
         ({model | newSecretInput = Just (DataTypes.Secret (DataTypes.SecretInfo name "") "")}, Cmd.none)

    InputValue value ->
      case model.newSecretInput of
        Just s ->
          let
            secInfo = DataTypes.SecretInfo s.info.name s.info.description
          in
          ({model | newSecretInput = Just (DataTypes.Secret secInfo value)}, Cmd.none)
        Nothing ->
         ({model | newSecretInput = Just (DataTypes.Secret (DataTypes.SecretInfo "" "") value)}, Cmd.none)
    InputDescription description ->
      case model.newSecretInput of
        Just s ->
          let
            secInfo = DataTypes.SecretInfo s.info.name description
          in
          ({model | newSecretInput = Just (DataTypes.Secret secInfo s.value)}, Cmd.none)
        Nothing ->
         ({model | newSecretInput = Just (DataTypes.Secret (DataTypes.SecretInfo "" description) "")}, Cmd.none)
    SubmitSecret action ->
      case model.newSecretInput of
        Just newSecret ->
          let
            isEmptyName = isEmpty newSecret.info.name
            isEmptyValue = isEmpty newSecret.value
            isEmptyDescription = isEmpty newSecret.info.description
          in
            case action of
              Read   -> (model, Cmd.none)
              Delete -> (model, Cmd.none) -- Should never happened
              Add    ->
                case (isEmptyName, isEmptyValue, isEmptyDescription) of
                  (True, _, _) ->
                    ({ model | stateInputs = EmptyInputName :: model.stateInputs}, Cmd.none)
                  (_, True, _) ->
                    (model, Cmd.none)
                  (_, _, True) ->
                    (model, Cmd.none)
                  (False, False, False ) ->
                    ({model | openModalMode = Read, stateInputs = []}, addSecret model newSecret)
              Edit   ->
                case (isEmptyValue, isEmptyDescription) of
                  (True, True) ->
                    (model, Cmd.none)
                  (_, _) ->
                    case model.focusOn of
                      Just sec -> ({model | openModalMode = Read, focusOn = Nothing, stateInputs = []}, updateSecret model {newSecret | info = DataTypes.SecretInfo sec.name newSecret.info.description})
                      Nothing -> (model, Cmd.none)

        Nothing ->
          case action of
            Delete ->
              case model.focusOn of
                Just sec -> ({model | openModalMode = Read}, deleteSecret sec.name model)
                Nothing -> (model, Cmd.none)
            _      -> (model, Cmd.none)
    OpenDescription s ->
      let
        openedDesc = filter (\o -> o /= s.name) model.openedDescription
      in
      if (length openedDesc) == (length model.openedDescription) then
        ({model | openedDescription = s.name :: openedDesc}, Cmd.none)
      else
        ({model | openedDescription = openedDesc}, Cmd.none)
    ChangeSorting col ->
      let
        sortByValue = case col of
          Name -> .name
          Description -> .description
        filteredSec = case model.filteredSecrets of
          Nothing -> Nothing
          Just sec ->
            case (first model.sortOn) of
              ASC -> Just (reverse (sortBy sortByValue sec))
              DESC -> Just (sortBy sortByValue sec)
              _    -> Just sec
      in
      if (col /= second (model.sortOn) || first (model.sortOn) == DESC) then
        ({model | secrets = sortBy sortByValue model.secrets, filteredSecrets = filteredSec ,sortOn = (ASC, col)}, Cmd.none)
      else
        ({model | secrets = reverse (sortBy sortByValue model.secrets), filteredSecrets = filteredSec,sortOn = (DESC, col)}, Cmd.none)
    FilterSecrets s ->
      let
        searchedSecrets = filter (\secret -> (contains s secret.name) || (contains s secret.description)) model.secrets
      in
      ({model | filteredSecrets = Just searchedSecrets}, Cmd.none)



processApiError : Error -> Model -> ( Model, Cmd Msg )
processApiError err model =
    --let
    --    newModel =
            ({ model | openModalMode = Read, focusOn = Nothing, stateInputs = []}, Cmd.none)
    --in
    --( newModel, Cmd.none ) |> createErrorNotification "Error while trying to fetch settings." err