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
//alert(I18n.t("model.user.id"));
var selector_basicInfo = "#basicInfo";
var selector_versionInfo = "#versionInfo";

var node = [];
var parent = [];
var versions = [];
var aspects = [];

var baseTableParams = {
		data:[],
		datatype : "local",
		colNames : [],
		colModel : [],
		//rowNum : 11,
		rowList : [1, 10, 20],
		caption : "",
		forceFit: true,
		cellEdit: false,
		cellsubmit: 'clientArray',
		//pager : ,
		width: 360,
		shrinkToFit : true,
		viewrecords: true,
		scroll:true,
		sortname: 'no',
		sortorder: "ASC",
		multiselect: false,
		beforeSelectRow: function(rowid, e) {
		    return false;
		}
	};

//////////////////////////////////////////
//Build data for jqGrid from Ruby variable
//////////////////////////////////////////
function buildGridData(nodeJSON, parentJSON, versionsJSON, aspectsJSON){
	node.push({property:I18n.t("model.node.id"), value: nodeJSON.id});
	node.push({property:I18n.t("model.node.name"), value: nodeJSON.name});
	node.push({property:I18n.t("model.node.type"), value: convertNodeTypeForUser(nodeJSON.type)});
	node.push({property:I18n.t("model.node.description"), value: nodeJSON.description});
	node.push({property:I18n.t("model.node.creator"), value: nodeJSON.creator});
	node.push({property:I18n.t("model.node.created_time"), value: nodeJSON.created});
	node.push({property:I18n.t("model.node.modifier"), value: nodeJSON.modifier});
	node.push({property:I18n.t("model.node.modified_time"), value: nodeJSON.modified});
	if(nodeJSON.type==="cmis:folder"){
		node.push({property:I18n.t("model.node.path"), value: nodeJSON.path});
	}
	if(nodeJSON.type==="cmis:document"){
		node.push({property:I18n.t("model.node.path"), value: buildDocumentPath(nodeJSON.name, parentJSON.path)});
		node.push({property:I18n.t("model.node.mimetype"), value: nodeJSON.mimetype});
		node.push({property:I18n.t("model.node.filesize"), value: readableFileSize(nodeJSON.size)});
		
		//Set Versions
		if(versionsJSON && versionsJSON.length > 0){
			for(var i=0; i<versionsJSON.length; i++){
				var v = versionsJSON[i];
				versions.push({version:v.version_label, id:v.id, time:v.modified, user:v.modifier});
			}
		}
	}
	aspects = aspectsJSON;
}

function buildDocumentPath(documentPathSegment, parentPath){
	if(parentPath === "/"){
		return "/" + documentPathSegment
	}else{
		return parentPath + "/" + documentPathSegment
	}
}

$(function() {
	///////////////////////////////////
	//Basic Info Table
	///////////////////////////////////
	var colModelSettings_basicInfo = [	
		{name:"property",index:"property", width:40, align:"left", sortable:false},
		{name:"value",index:"value",align:"left", sortable:false}
	]
	
	var colNames_basicInfo = [I18n.t("view.general.property"), I18n.t("view.general.value")];
	//CREATE TABLE
	if(node && node.length > 0){
		createBasicInfoTable();
	}
	function createBasicInfoTable(){
		var tableParams_basicInfo = $.extend(true, {}, baseTableParams);
		tableParams_basicInfo.caption = I18n.t("view.node.show.content_basic_information");
		tableParams_basicInfo.data = node;
		tableParams_basicInfo.height = "auto";
		tableParams_basicInfo.colNames = colNames_basicInfo;
		tableParams_basicInfo.colModel = colModelSettings_basicInfo;
		$(selector_basicInfo).jqGrid(tableParams_basicInfo);
	};

	///////////////////////////////////
	//Version Info Table
	///////////////////////////////////
	var colModelSettings_versionInfo = [	
		{name:"version",index:"version", width:100, align:"left", sortable:false, formatter:returnMyLink},
		{name:"id",index:"id",align:"left", hidden:true},
		{name:"time",index:"time",align:"left", width:150, sortable:false},
		{name:"user",index:"user",align:"left", width:150, sortable:false}
	]
	
	var colNames_versionInfo = [I18n.t("model.node.version"), "ID", I18n.t("model.node.modified_time"), I18n.t("model.node.modifier")];
	//CREATE TABLE
	if(versions && versions.length > 0){
		createVersionInfoTable();
	}
	function createVersionInfoTable(){
		var tableParams_versionInfo = $.extend(true, {}, baseTableParams);
		tableParams_versionInfo.caption = I18n.t("view.node.show.version_history");
		tableParams_versionInfo.data = versions;
		tableParams_versionInfo.height = 70;
		tableParams_versionInfo.colNames = colNames_versionInfo;
		tableParams_versionInfo.colModel = colModelSettings_versionInfo;
		//tableParams_versionInfo.pager = '#versionInfo_pager'; 
		$(selector_versionInfo).jqGrid(tableParams_versionInfo);
	};
	
	///////////////////////////////////
	//Aspect Info Table
	///////////////////////////////////
	var colModelSettings_aspectInfo = [	
		{name:"key",index:"key", align:"left", sortable:false},
		{name:"value",index:"value",align:"left", sortable:false}
	]
	
	var colNames_aspectInfo = [I18n.t("view.node.show.property"), I18n.t("view.node.show.value")];
	
	var tableParams_aspectInfo = $.extend(true, {}, baseTableParams);
	tableParams_aspectInfo.colNames = colNames_aspectInfo;
	tableParams_aspectInfo.colModel = colModelSettings_aspectInfo;
	//CREATE TABLE
	createAspectInfoTables(aspects);
	function createAspectInfoTables(aspects){
		for(var i=0; i<aspects.length; i++){
			var a = aspects[i];
			var tableSelector = "#nemaki_table_aspect_" + escapeColon(a.id);
			var data = buildPropertyRecords(a.properties);
			tableParams_aspectInfo.caption = a.attributes.displayName;
			tableParams_aspectInfo.data = data;
			tableParams_aspectInfo.height = "auto";
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
			data.push({key:p.attributes.displayName, value:p.value});
		}
		return data;
	}
});

///////////////////////////////////
//Utility
///////////////////////////////////
function convertNodeTypeForUser(type){
	if(type==="cmis:document"){
		return I18n.t("view.node.show.file") + "(" + type + ")";
	}else if(type==="cmis:folder"){
		return I18n.t("view.node.show.folder") + "(" + type + ")";
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