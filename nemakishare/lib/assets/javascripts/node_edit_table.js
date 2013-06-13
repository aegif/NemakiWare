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

var node = [];
var parent = [];
var aspects = [];

var baseTableParams = {
		data:[],
		datatype : "local",
		//colNames : ,
		//colModel : ,
		rowNum : 10,
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
function buildGridData(nodeJSON, parentJSON, aspectsJSON){
	//Key MUST be the same as Rails Node classes field name
	node.push({key:"name", name:I18n.t("model.node.name"), value: nodeJSON.name});
	node.push({key:"description", name:I18n.t("model.node.description"), value: nodeJSON.description});

	aspects = aspectsJSON;
}

/**
 * Update
 * @returns {Boolean}
 */
function update() {
	// Update basic properties via hidden tag
	var basicValues = $("#basicInfo").jqGrid('getRowData');
	var basicJSON = JSON.stringify(basicValues);
	$("#basic_properties").val(basicJSON);
	
	// Update custom properties via hidden tag
	var editedAspects = [];
	if(aspects && aspects.length > 0){
		for(var i=0; i<aspects.length; i++){
			var a = aspects[i];
			var properties =  $("#" + aspect_table_prefix + a.id).jqGrid('getRowData')
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
	var colModelSettings_basicInfo = [	
		{name:"key",index:"key", align:"left", sortable:false, hidden:true},
		{name:"name",index:"name", width:40, align:"left", sortable:false},
		{name:"value",index:"value",align:"left", edittype:'text', editable:true, sortable:false}
	]
	
	var colNames_basicInfo = ["ID", I18n.t("view.general.property"), I18n.t("view.general.value")];
	//CREATE TABLE
	if(node && node.length > 0){
		createBasicInfoTable();
	}
	function createBasicInfoTable(){
		var tableParams_basicInfo = $.extend(true, {}, baseTableParams);
		tableParams_basicInfo.caption = I18n.t("view.node.show.content_basic_information");
		tableParams_basicInfo.data = node;
		tableParams_basicInfo.colNames = colNames_basicInfo;
		tableParams_basicInfo.colModel = colModelSettings_basicInfo;
		$(selector_basicInfo).jqGrid(tableParams_basicInfo);
	};

	///////////////////////////////////
	//Aspect Info Table
	///////////////////////////////////
	var colModelSettings_aspectInfo = [	
		{name:"key",index:"key", align:"left", sortable:false, hidden: true},
		{name:"name",index:"name", align:"left", sortable:false},
		{name:"value",index:"value",align:"left", sortable:false, editable:true, edittype:'text'}
	]
	
	var colNames_aspectInfo = ["ID", I18n.t("view.general.property"), I18n.t("view.general.value")];
	
	var tableParams_aspectInfo = $.extend(true, {}, baseTableParams);
	tableParams_aspectInfo.colNames = colNames_aspectInfo;
	tableParams_aspectInfo.colModel = colModelSettings_aspectInfo;
	//CREATE TABLE
	createAspectInfoTables(aspects);
	function createAspectInfoTables(aspects){
		for(var i=0; i<aspects.length; i++){
			var a = aspects[i];
			var tableSelector = "#nemaki_table_aspect_" + a.id;
			var data = buildPropertyRecords(a.properties);
			tableParams_aspectInfo.caption = a.attributes.displayName;
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
	
	function buildPropertyRecords(propertiesJSON){
		var data = [];
		for(var i=0; i<propertiesJSON.length; i++){
			var p = propertiesJSON[i];
			data.push({key: p.key, name:p.attributes.displayName, value:p.value});
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