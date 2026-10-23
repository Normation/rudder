module Editor.Tests.EditionStateTest exposing (..)

import Dict
import Dom.DragDrop as DragDrop
import Editor exposing (switchTab)
import Editor.DataTypes exposing (..)
import Expect
import Test exposing (..)


technique : Technique
technique =
    Technique (TechniqueId "my_technique") "1.0" "My technique" "" "" "ncf_techniques" [] [] [] [] Nothing


ui : TechniqueUiInfo
ui =
    TechniqueUiInfo General Dict.empty Dict.empty [] False Unchanged Unchanged Nothing


editInfo : TechniqueEditInfo
editInfo =
    TechniqueEditInfo "" (EnabledEdition VisualEdit)


details : Mode
details =
    TechniqueDetails technique (Edit technique) ui editInfo


model : Mode -> Model
model mode =
    Model [] [] Dict.empty (TechniqueCategory "" "" "" "" (SubCategories [])) Dict.empty [] mode "" (TreeFilters "" []) (MethodListUI (MethodFilter "" False Nothing FilterClosed) []) False DragDrop.initialState Nothing True Nothing Nothing False [] "default"


editModeTests : Test
editModeTests =
    describe "The edit mode of a technique"
        [ test "is the inner mode of an enabled edition" <|
            \_ -> techniqueEditMode (EnabledEdition YamlEdit) |> Expect.equal YamlEdit
        , test "is the inner mode of a disabled edition" <|
            \_ -> techniqueEditMode (DisabledEdition YamlEdit) |> Expect.equal YamlEdit
        , test "toggles from visual to YAML" <|
            \_ -> toggleEditMode VisualEdit |> Expect.equal YamlEdit
        , test "toggles from YAML to visual" <|
            \_ -> toggleEditMode YamlEdit |> Expect.equal VisualEdit
        ]


editionStateForTabTests : Test
editionStateForTabTests =
    describe "The edition state of a technique, depending on the displayed tab"
        [ test "is enabled on the Information tab" <|
            \_ -> editionStateForTab General (DisabledEdition VisualEdit) |> Expect.equal (EnabledEdition VisualEdit)
        , test "is enabled on the Parameters tab" <|
            \_ -> editionStateForTab Parameters (DisabledEdition YamlEdit) |> Expect.equal (EnabledEdition YamlEdit)
        , test "is disabled on the Resources, Directives, Output and None tabs" <|
            \_ ->
                [ Resources, Directives, Output, None ]
                    |> List.map (\tab -> editionStateForTab tab (EnabledEdition VisualEdit))
                    |> Expect.equal (List.repeat 4 (DisabledEdition VisualEdit))
        , test "keeps the YAML mode while disabled, and restores it on a tab which enables it" <|
            \_ ->
                EnabledEdition YamlEdit
                    |> editionStateForTab Directives
                    |> Expect.all
                        [ Expect.equal (DisabledEdition YamlEdit)
                        , editionStateForTab General >> Expect.equal (EnabledEdition YamlEdit)
                        ]
        ]


updateFunctionsTests : Test
updateFunctionsTests =
    describe "The update functions of the technique editor model"
        [ test "updateMode replaces the mode of the model" <|
            \_ ->
                model Introduction
                    |> updateMode (always details)
                    |> .mode
                    |> Expect.equal details
        , test "updateTechniqueUi updates the UI of the technique details" <|
            \_ ->
                details
                    |> updateTechniqueUi (setTab Directives)
                    |> Expect.equal (TechniqueDetails technique (Edit technique) { ui | tab = Directives } editInfo)
        , test "updateTechniqueUi leaves another mode unchanged" <|
            \_ -> Introduction |> updateTechniqueUi (setTab Directives) |> Expect.equal Introduction
        , test "updateTechniqueEditInfo updates the edit info of the technique details" <|
            \_ ->
                details
                    |> updateTechniqueEditInfo (setEditValue "id: my_technique")
                    |> Expect.equal (TechniqueDetails technique (Edit technique) ui { editInfo | value = "id: my_technique" })
        , test "updateTechniqueEditInfo leaves another mode unchanged" <|
            \_ -> Introduction |> updateTechniqueEditInfo (setEditValue "id: my_technique") |> Expect.equal Introduction
        , test "updateEditionState updates only the edition state" <|
            \_ ->
                editInfo
                    |> setEditValue "id: my_technique"
                    |> updateEditionState (always (DisabledEdition YamlEdit))
                    |> Expect.equal (TechniqueEditInfo "id: my_technique" (DisabledEdition YamlEdit))
        , test "setTab updates only the tab" <|
            \_ -> setTab Parameters ui |> Expect.equal { ui | tab = Parameters }
        , test "they compose through the model, down to the edition state" <|
            \_ ->
                model details
                    |> updateMode (updateTechniqueEditInfo (updateEditionState (editionStateForTab Directives)))
                    |> .mode
                    |> Expect.equal (TechniqueDetails technique (Edit technique) ui (TechniqueEditInfo "" (DisabledEdition VisualEdit)))
        ]


switchTabTests : Test
switchTabTests =
    describe "Switching the tab of a technique"
        [ test "disables the edition on a tab which does not allow it" <|
            \_ ->
                model details
                    |> switchTab Directives
                    |> .mode
                    |> Expect.equal (TechniqueDetails technique (Edit technique) { ui | tab = Directives } (TechniqueEditInfo "" (DisabledEdition VisualEdit)))
        , test "enables a disabled edition on the Information tab" <|
            \_ ->
                model (TechniqueDetails technique (Edit technique) ui (TechniqueEditInfo "" (DisabledEdition VisualEdit)))
                    |> switchTab General
                    |> .mode
                    |> Expect.equal details
        , test "keeps the default edition of a technique opened on the Information tab" <|
            \_ ->
                model (TechniqueDetails technique (Edit technique) ui defaultTechniqueEditInfo)
                    |> switchTab General
                    |> .mode
                    |> Expect.equal (TechniqueDetails technique (Edit technique) ui defaultTechniqueEditInfo)
        , test "disables a YAML edition on the Directives tab, and enables it back as YAML on the Information tab" <|
            \_ ->
                model (TechniqueDetails technique (Edit technique) ui (TechniqueEditInfo "" (EnabledEdition YamlEdit)))
                    |> switchTab Directives
                    |> Expect.all
                        [ .mode >> Expect.equal (TechniqueDetails technique (Edit technique) { ui | tab = Directives } (TechniqueEditInfo "" (DisabledEdition YamlEdit)))
                        , switchTab General >> .mode >> Expect.equal (TechniqueDetails technique (Edit technique) ui (TechniqueEditInfo "" (EnabledEdition YamlEdit)))
                        ]
        , test "leaves another mode unchanged" <|
            \_ -> model Introduction |> switchTab Directives |> .mode |> Expect.equal Introduction
        ]
