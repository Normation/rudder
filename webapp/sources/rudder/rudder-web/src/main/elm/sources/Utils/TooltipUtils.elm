module Utils.TooltipUtils exposing (..)

-- WARNING:
--
-- This should only be used to fill the 'title' HTML attribute with Bootstrap's tooltip/popover system,
-- as it already sanitises the content of this attribute by default.
-- Parameters 'title' and 'content' must be sanitized manually if used in another scenario.
--

buildTooltipContent : String -> String -> String
buildTooltipContent title content =
  let
    headingTag = "<h4 class='tags-tooltip-title'>"
    contentTag = "</h4><div class='tooltip-inner-content'>"
    closeTag   = "</div>"
  in
    headingTag ++ title ++ contentTag ++ content ++ closeTag
    