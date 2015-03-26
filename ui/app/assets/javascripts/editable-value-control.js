function editableValueFieldControl(){
	editableValueFieldAdd();
	editableValueFieldRemove();
	editableValueFieldUp();
	editableValueFieldDown();
}

function editableValueFieldAdd(){
	$(document).on('click', '.editable-value-field-add', function(){
		var myContainer = $(this).closest("div.editable-value-field-container");
		myContainer.clone().insertAfter(myContainer);
		//empty value after clone
		myContainer.next().find("div.editable-value-field:first").text("");
	});
}

function editableValueFieldRemove(){
	$(document).on('click', '.editable-value-field-remove', function(){
		var myContainer = $(this).closest("div.editable-value-field-container");
		if(myContainer.parent().children("div.editable-value-field-container").size() >= 2){
			//remove
			myContainer.remove();
		}else if(myContainer.parent().children("div.editable-value-field-container").size() == 1){
			//empty value
			myContainer.find("div.editable-value-field:first").text("");
		}
	});
}

function editableValueFieldUp(){
	$(document).on('click', '.editable-value-field-up', function(){
		var myContainer = $(this).closest("div.editable-value-field-container");
		if(myContainer.prev().length !== 0){
			myContainer.clone().insertBefore(myContainer.prev());
			myContainer.remove();
		}
	});
}

function editableValueFieldDown(){
	$(document).on('click', '.editable-value-field-down', function(){
		var myContainer = $(this).closest("div.editable-value-field-container");
		if(myContainer.next().length !== 0){	
			myContainer.clone().insertAfter(myContainer.next());
			myContainer.remove();
		}
	});
}