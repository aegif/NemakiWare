function bindEditable(selector){
	selector = escape(selector);
	$(document).on("dblclick", selector, function(){
		var txt = "";
		if($(selector).attr('on') == 'on'){
			txt = $(selector + ' > div > input').value();
		}else{
			$(selector).attr('on', 'on');
			txt = $(selector + ' > div').text();
		}
		
		
		
		$(this).children('div :first').html('<input type="text" value="'+txt+'" />');
		
		//end of editing
		$(selector + ' > div > input').focus().blur(function(){
			var inputVal = $(this).val();
			$(this).parent().text(inputVal);
			$(selector).removeAttr('on');
		});
		
		//binding: search box keypress
		$(document).on('keypress', selector + ' > div > input', function(event){
			if(event.which == 13){
				var inputVal = $(this).val();
				$(this).parent().text(inputVal);
				$(selector).removeAttr('on');
			}
		});
		
	});
}

function editedValue(selector){
	selector = escape(selector);
	var v = $(selector + " > div").text();
	return v;
}

function escape(selector){
	return selector.replace(':',"\\:");
}