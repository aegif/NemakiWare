/*******************************************************************************
 * Copyright (c) 2013 aegif.
 * 
 * This file is part of NemakiWare.
 * 
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/

var selector_basicInfo = "#basicInfo";
var selector_versionInfo = "#versionInfo";
var aspect_table_prefix = "nemaki_table_aspect_";

var CONST_YES = "Y";
var CONST_NO = "N";

var GLOBAL_NODE = [];
var parent = [];
var GLOBAL_ASPECTS = [];
var GLOBAL_TYPE = [];

var baseTableParams = {
		data:[],
		datatype : "local",
		//colNames : ,
		//colModel : ,
		//rowNum : 10,
		rowList : [1, 10, 20],
		caption : "",
		forceFit: true,
		cellEdit: true,
		cellsubmit: 'clientArray',
		//pager : ,
		height: 'auto',
		width: 360,
		shrinkToFit : true,
		viewrecords: true,
		scroll:true,
		sortname: 'no',
		sortorder: "ASC"
	};

//////////////////////////////////////////
//Build data for jqGrid from Ruby variable
//////////////////////////////////////////
function copyData(nodeJSON, parentJSON, aspectsJSON, typeJSON){
	//Key MUST be the same as Rails Node classes field name
	GLOBAL_NODE.push({key:"name", name:I18n.t("model.node.name"), value: nodeJSON.name});
	GLOBAL_NODE.push({key:"description", name:I18n.t("model.node.description"), value: nodeJSON.description});

	GLOBAL_ASPECTS = aspectsJSON;
	GLOBAL_TYPE = typeJSON;
}

/** 
 * Convert Input Tag to content value string
 *
 */
function convertInputToValue(value) {
    //begin with <input
    if ( value.lastIndexOf('<input',0) === 0 ) {
        inputObj = $.parseHTML(value);
        return $('#' + inputObj[0].id).val();
    }
    return value;
}
function convertInputToValues(values) {
    for(key in values) {
	values[key].value = convertInputToValue(values[key].value);
    }
    return values;
}

/**
 * Update
 * @returns {Boolean}
 */
function update() {
	// Update basic properties via hidden tag
	var basicValues = $(selector_basicInfo).jqGrid('getRowData');
        basicValues = convertInputToValues(basicValues);
	var basicJSON = JSON.stringify(basicValues);

	$("#basic_properties").val(basicJSON);
	
	// Update custom properties via hidden tag
	var editedAspects = [];
	if(GLOBAL_ASPECTS && GLOBAL_ASPECTS.length > 0){
		for(var i=0; i < GLOBAL_ASPECTS.length; i++){
			var a = GLOBAL_ASPECTS[i];
			var properties =  $("#" + aspect_table_prefix + escapeColon(a.id)).jqGrid('getRowData');
                        properties = convertInputToValues(properties);
			var aspect = {"id": a.id, "properties": properties};
			editedAspects.push(aspect);
		}
	}
	$("#custom_properties").val(JSON.stringify(editedAspects));
	
	$("#update_form").submit();
}

$(function() {
	///////////////////////////////////
	//Basic Info Table
	///////////////////////////////////
	function buildColNames(){
		var colNames = ["ID", I18n.t("view.general.property"), I18n.t("view.general.value"), "タイプ", "M"];
		return colNames;
	}
	
	function buildColModelSettings(){
		var colModelSettings =
			[{name:"key",index:"key", align:"left", sortable:false, hidden:true},
			{name:"name",index:"name", width:80, align:"left", sortable:false},
			{name:"value",index:"value",align:"left", edittype:'text', editable:true, sortable:false},
			{name:"datatype",index:"datatype",width:30, align:"center", sortable:false, editable:false},
			{name:"cardinality",index:"cardinality",width:15, align:"center", sortable:false, editable:false}];
		return colModelSettings;
	}
	
	/**
	 *
	 * @returns {}
	 */
	//TODO prototype: hard-coded!
	function buildBasicGridData(){
		var data = [];
		
		for(var i = 0; i < GLOBAL_NODE.length; i++){
			rowData = {};
			rowData.key = GLOBAL_NODE[i].key;
			rowData.name = GLOBAL_NODE[i].name;
			rowData.value = GLOBAL_NODE[i].value;
			rowData.datatype = "string";
			rowData.updatability = "readwrite";
			rowData.cardinality = CONST_NO;
			data.push(rowData);
		}
		
		return data;
	}
	
	
	//CREATE TABLE
	if(GLOBAL_NODE && GLOBAL_NODE.length > 0){
		createBasicInfoTable();
	}
	function createBasicInfoTable(){
		var tableParams_basicInfo = $.extend(true, {}, baseTableParams);
		tableParams_basicInfo.caption = I18n.t("view.node.show.content_basic_information");
		
		
		tableParams_basicInfo.data = buildBasicGridData();
		
		tableParams_basicInfo.colNames = buildColNames();
		tableParams_basicInfo.colModel = buildColModelSettings();
		$(selector_basicInfo).jqGrid(tableParams_basicInfo);
	};

	///////////////////////////////////
	//Aspect Info Table
	///////////////////////////////////
	var tableParams_aspectInfo = $.extend(true, {}, baseTableParams);
	tableParams_aspectInfo.colNames = buildColNames();
	tableParams_aspectInfo.colModel = buildColModelSettings();
	//CREATE TABLE
	createAspectInfoTables(GLOBAL_ASPECTS);
	function createAspectInfoTables(aspects){
		for(var i=0; i<aspects.length; i++){
			var a = aspects[i];
			var tableSelector = "#nemaki_table_aspect_" + escapeColon(a.id);
			tableParams_aspectInfo.caption = a.attributes.displayName;
			
			var data = buildAspectGridData(a.properties);
			tableParams_aspectInfo.data = data;
			
			$(tableSelector).jqGrid(tableParams_aspectInfo);
			
			var gridState;
			if(a.implemented){
				gridState = 'visible';
			}else{
				gridState = 'hidden';
			}
			$(tableSelector).jqGrid('setGridState', gridState);
		}
	}
	
	/**
	 * Build and Filter data for jqGrid
	 *  Filtering: only Updatable rows
	 * @param {} propertiesJSON
	 * @returns {}
	 */
	function buildAspectGridData(propertiesJSON){
		var data = [];
		for(var i=0; i<propertiesJSON.length; i++){
			var p = propertiesJSON[i];
			
			//Filter
			updatability = p.attributes.updatability;
			if(updatability != "readwrite") continue;
			
			//Set a row data			
			var rowData = {key: p.key, name:p.attributes.displayName, value:p.value};
			rowData.datatype = p.attributes.datatype;
			if(p.attributes.cardinality = "single"){
				rowData.cardinality = CONST_NO;
			}else{
				rowData.cardinality = CONST_YES;
			}
			data.push(rowData);
		}
		return data;
	}
});

///////////////////////////////////
//Utility
///////////////////////////////////
function convertNodeTypeForUser(type){
	if(type==="cmis:document"){
		return "ファイル(" + type + ")";
	}else if(type==="cmis:folder"){
		return "フォルダ(" + type + ")";
	}
}

function readableFileSize(size) {
    var units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
    var i = 0;
    while(size >= 1024) {
        size /= 1024;
        ++i;
    }
    return size.toFixed(1) + ' ' + units[i];
}

function returnMyLink(celldata, options, rowdata, action){
	return "<a href='" + rowdata.id + "/download'>" + "<i class='icon-download-alt'></i>" + rowdata.version + "</a>";
}

function escapeColon(string){
	return string.split(":").join("\\:");
}
