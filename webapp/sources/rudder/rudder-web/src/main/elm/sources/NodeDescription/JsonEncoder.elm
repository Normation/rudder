module NodeDescription.JsonEncoder exposing (encodeNodeDescription)

import Json.Encode as Encode exposing (list, object, string)
import NodeDescription.DataTypes exposing (Model)


encodeNodeDescription : Model -> Encode.Value
encodeNodeDescription model =
    object [ ( "documentation", string model.newDescription ) ]
