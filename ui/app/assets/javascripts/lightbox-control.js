function showLightbox(domId, html){
	var lightbox_me_config = {
			centered:true,
			onClose: function() {
				content.remove();
            }
		};

	var content = $(html).attr('id', 'lightbox-' + domId);

	content.lightbox_me(lightbox_me_config);
}