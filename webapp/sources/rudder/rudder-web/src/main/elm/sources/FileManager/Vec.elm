module FileManager.Vec exposing (..)

type Vec2 = Vec2 Float Float

type alias Bound =
  { x : Float
  , y : Float
  , width : Float
  , height : Float
  }

newBound : Bound
newBound = { x = 0, y = 0, width = 0, height = 0 }

toBound : Vec2 -> Vec2 -> Bound
toBound v1 v2 =
  let
    (Vec2 x1 y1) = v1
    (Vec2 x2 y2) = v2
    (x, y) = (min x1 x2, min y1 y2)
    (width, height) = (max x1 x2 - x, max y1 y2 - y)
  in
    { x = x, y = y, width = width, height = height }

isFar : Vec2 -> Vec2 -> Bool
isFar (Vec2 x1 y1) (Vec2 x2 y2) = abs (x1 - x2) > 3 || abs (y1 - y2) > 3 

touchesBound : Bound -> Bound -> Bool
touchesBound b1 b2 = not
  <| b1.x > b2.x + b2.width
  || b1.y > b2.y + b2.height
  || b1.x + b1.width < b2.x
  || b1.y + b1.height < b2.y
