module Editor.ViewUtils exposing (..)

import Editor.DataTypes exposing (..)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)



--
-- Rendering helpers shared by the views of the technique editor
--


{-| The save button of a technique or of a category. `disableChecks` pairs a reason with the
condition that makes it apply, and the tooltip lists the ones that hold.
-}
viewBtnSave : Bool -> List ( Bool, String ) -> Msg -> Html Msg
viewBtnSave saving disableChecks action =
    let
        disable =
            disableChecks |> List.any (\( check, _ ) -> check)

        btnTitle =
            if disable then
                String.append
                    (disableChecks
                        |> List.filter (\( check, _ ) -> check)
                        |> List.map (\( _, txt ) -> txt)
                        |> String.join ".\n"
                    )
                    "."

            else
                ""

        icon =
            if saving then
                "fa-spinner fa-pulse"

            else if disable then
                "fa-ban"

            else
                "fa-download"
    in
    button
        [ class
            ("btn btn-success btn-save"
                ++ (if saving then
                        " saving"

                    else
                        ""
                   )
            )
        , type_ "button"
        , Html.Attributes.title btnTitle
        , disabled (saving || disable)
        , onClick action
        ]
        [ i [ class ("fa " ++ icon) ] [] ]


{-| Folder icons say what the user can do with a category: grey for read only, teal for
`User Techniques` where sub-categories can be added, Rudder blue for the user ones.
-}
categoryIconClass : TechniqueCategory -> String
categoryIconClass category =
    case categoryKind category of
        StandardCategory ->
            " category-std"

        UserTechniquesRoot ->
            " category-user-root"

        UserCategory ->
            " category-user"
