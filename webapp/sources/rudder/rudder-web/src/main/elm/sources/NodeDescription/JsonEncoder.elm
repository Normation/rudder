module NodeDescription.JsonEncoder exposing (encodeNodeDescription, encodeUserRoleCoverage)

import Json.Encode as Encode exposing (list, object, string)
import NodeDescription.DataTypes exposing (Model)


encodeNodeDescription : Model -> Encode.Value
encodeNodeDescription model =
    object [ ( "documentation", string model.newDescription ) ]


encodeUserRoleCoverage : Encode.Value
encodeUserRoleCoverage =
    object
        [ ( "permissions", list string [ "configuration" ] )
        , ( "authz", list string [ "node_write" ] )
        ]
