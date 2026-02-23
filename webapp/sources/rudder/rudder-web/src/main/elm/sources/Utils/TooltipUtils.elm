module Utils.TooltipUtils exposing (..)

-- WARNING:
--
-- Here we are building an html snippet that will be placed inside an attribute, so
-- we can't easily use the Html type as there is no built-in way to serialize it manually.
-- This means it will be vulnerable to XSS on its parameters (here the description).
--
-- We resort to escaping it manually here.
buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
    escapedTitle = htmlEscape title
    escapedContent = htmlEscape content
  in
    headingTag ++ escapedTitle ++ contentTag ++ escapedContent ++ closeTag

htmlEscape : String -> String
htmlEscape s =
  String.replace "&" "&amp;" s
    |> String.replace ">" "&gt;"
    |> String.replace "<" "&lt;"
    |> String.replace "\"" "&quot;"
    |> String.replace "'" "&#x27;"
    |> String.replace "\\" "&#x2F;"