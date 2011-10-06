// increase the default animation speed to exaggerate the effect
	$.fx.speeds._default = 1000;
	$(function() {
		$('#dialog').dialog({
			autoOpen: false,
			position: [250,100],
			width: 535,
			show: '',
			hide: ''		
		});
		$('#openerAccount').click(function() {
			$('#dialog').dialog('open');
			return false;
		});
	});




// Details
	
		$.fx.speeds._default = 1000;
	$(function() {
		$('#dialogDetail').dialog({
			autoOpen: false,
			position: 'center',
			width: 300,
			show: '',
			hide: ''		
		});
		$('.openDetail').click(function() {
			$('#dialogDetail').dialog('open');
			return false;
		});
	});
	


	$(function() {
	    // Bouton rouge
		$('#openAlert').click(function() {
			$('#dialogAlert').modal({
                minHeight:200,
                minWidth: 450
            });
			$('#simplemodal-container').css('height', 'auto');
			return false;
		});

        // Logout
        $('#logout').click(function() {
			$('#dialogLogOut').modal({
                minHeight:100,
                minWidth: 430
            });
			$('#simplemodal-container').css('height', 'auto');
			return false;
		});

	});
