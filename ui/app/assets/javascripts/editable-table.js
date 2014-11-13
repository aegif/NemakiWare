//Make a value field editable
function bindEditable(valueFieldSelector){
	$(document).on('dblclick', valueFieldSelector, function(){
		var txt = "";
		if($(this).attr('on') != 'on'){
			$(this).attr('on', 'on');
			txt = $(this).text();
			$(this).html('<input class="editable-value-input" type="text" value="'+txt+'" />');
			$(this).children('.editable-value-input:first').focus();
		}

		//end of editing
		$(this).children('.editable-value-input:first').focus().blur(function(){
			var inputVal = $(this).val();
			var parentDiv = $(this).parent();
			parentDiv.text(inputVal);
			parentDiv.removeAttr('on');
		});
		
		//binding: keypress
		$(document).on('keypress', valueFieldSelector + ' > .editable-value-input:first', function(event){
			if(event.which == 13){
				var inputVal = $(this).val();
				$(this).parent().text(inputVal);
				$(valueFieldSelector).removeAttr('on');
			}
		});
    });
}

//Get edited value 
//TODO specify table tag
function editedValue(selector){
	selector = escape(selector);
	var divs = $(selector + " > td.editable-value div.editable-value-field");
	if(divs.size() === 0){
		alert("no edited value! selector:" + selector);
	}else if(divs.size() == 1){
		//single value
		var v = divs.text();
		return v;
	}else{
		//multiple value
		var arr = divs.map(function(){
			return $(this).text();
		}).get();
		return arr;
	}
}

//Escape for DOM selector with colon
function escape(selector){
	return selector.replace(':',"\\:");
}