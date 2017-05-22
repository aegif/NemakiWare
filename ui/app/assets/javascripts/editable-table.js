//Make a value field editable
function bindEditable(valueFieldSelector){
	$(valueFieldSelector).off().on('dblclick', function(){
		var txt = "";
		if($(this).attr('on') != 'on'){
			$(this).attr('on', 'on');
			txt = $(this).text();
			var trid = $(this).attr('property-id');
			if($(this).attr('property-type') == 'DATETIME'){
				//$(this).html('<div class="input-group date" id="datetimepicker_field"><input class="editable-value-input" type="text" value="" /><span class="input-group-addon"><span class="glyphicon glyphicon-calendar"></span></span></div></div><script type="text/javascript">$(function () {$("datetimepicker_field").datetimepicker();});</script>');
				$(this).html('<div style="position:relative"><input class="editable-value-input" type="text" value="" id="datetimepicker_field" /></div><script type="text/javascript"> $(function () { $("#datetimepicker_field").datetimepicker({widgetParent : "#obj-property-table", format: "YYYY-MM-DD hh:mm:00"}); });</script>');
			}else{
				$(this).html('<input class="editable-value-input" type="text" value="" />');
			}
			$(this).find("input").val(txt);
			$(this).children('.editable-value-input:first').focus();
		}

		//end of editing
		$(document).on('blur.editable-value', valueFieldSelector + ' .editable-value-input:first', function(){
			$(document).off('.editable-value');
			revertField($(this));
		});

		//binding: keypress
		$(valueFieldSelector + ' .editable-value-input:first').off().on('keypress', function(event){
			if(event.which == 13){
				revertField($(this));
			}
		});
    });
}

function revertField(inputboxDom){
	console.log('revert!');
	console.log(inputboxDom);
	var inputVal = inputboxDom.val();
	var wrap = inputboxDom.closest("div.antiscroll-wrap");
	var inner = inputboxDom.closest("div.antiscroll-inner");

	console.log(inputboxDom);
	inner.removeAttr('on');
	inner.html(inputVal);

	var height = wrap.height();
	var width = wrap.width();
	$(function () {
		wrap.antiscroll();
		inner.height(height);
		inner.width(width);
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