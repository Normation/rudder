$(function() {
	/*
		$(".column").sortable({
			connectWith: '.column'
		});
		$(".column").disableSelection();
	*/

		$(".portlet").addClass("ui-widget ui-widget-content ui-helper-clearfix arrondis")
			.find(".portlet-header")
				.addClass("ui-widget-header arrondishaut")
				.prepend('<span class="ui-icon ui-icon-minusthick"></span>')
				.end()
			.find(".portlet-content");

		$(".portlet-header .ui-icon").click(function() {
			$(this).toggleClass("ui-icon-minusthick").toggleClass("ui-icon-plusthick");
			$(this).parents(".portlet:first").find(".portlet-content").toggle();
		});

	});


/**
 * Check all checkbox named name according to the status of the checkbox with id id
 * @param id
 * @param name
 * @return
 */
function jqCheckAll( id, name )
{
   $("INPUT[@name=" + name + "][type='checkbox']").attr('checked', $('#' + id).is(':checked'));
}
