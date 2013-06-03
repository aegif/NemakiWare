//flash fadeIn/fadeOut
$(function() {
   	$('#flash').delay(500).fadeIn('normal', function() {
      	$(this).delay(3500).fadeOut();
   	});
});