/**
 * Instanciate the tooltip
 * For each element having the "tooltipable" class, when hovering it will look for it's 
 * tooltipid attribute, and display in the tooltip the content of the div with the id 
 * tooltipid 
 */
function createTooltip() {
	$(".tooltipable").tooltip({
			bodyHandler: function() {
		    return $("#"+$(this).attr("tooltipid")).html();
		}
		});
}