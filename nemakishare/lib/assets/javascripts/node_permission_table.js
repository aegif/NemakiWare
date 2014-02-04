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

var PREFIX_PERMISSION = "permission_";

var inherited_acl = [];
var local_acl = [];
var users = [];
var aclInheritance;
var permissions;

var baseTableParams = {
	data : [],
	datatype : "local",
	colNames : [],
	colModel : [],
	rowNum : 7,
	rowList : [1, 10, 20],
	caption : "",
	height : 120,
	width : 300,
	cellEdit : true,
	cellsubmit : 'clientArray',
	shrinkToFit : true,
	viewrecords : true,
	scroll : true,
	sortname : 'no',
	sortorder : "ASC",
	multiselect : true
};

/**
 *
 */
function buildGridData(aclJSON, inheritance, permissions) {
	for (var i = 0; i < aclJSON.length; i++) {
		var a = aclJSON[i];
		
		var rowData = {
			principal : a.principal,
		};
		
		for(var i = 0; i < permissions.length; i++){
			if($.inArray(permissions[i], a.permissions) >= 0){
				rowData[PREFIX_PERMISSION + permissions[i]] = "True";
			}else{
				rowData[PREFIX_PERMISSION + permissions[i]] = "False";
			}
		}
		
		if (a.direct) {
			local_acl.push(rowData);
		} else {
			inherited_acl.push(rowData);
		}
	}

	aclInheritance = inheritance;
	this.permissions = permissions;
}

/**
 * Add a new user from User table to Acl table
 */
function addPrincipalToAcl() {
	selectedIds = $("#user_table").getGridParam('selarrrow');
	for ( i = 0; i < selectedIds.length; i++) {
		row = $("#user_table").getRowData(selectedIds[i]);
		addrow(row.principal);
	}
}

/**
 *
 */
function addrow(principal) {
	var arrows = $("#local_acl_table").getRowData();
	var max = arrows.length;
	var principalValue = "";

	if (principal) {
		principalValue = principal;
	} else {
		principalValue = "選択してください";
	}

	var tmpData = {
		principal : principalValue,
		permissions : "cmis:read"
	};

	$("#local_acl_table").addRowData(undefined, tmpData);
}

/**
 * Delete a row from Acl table
 */
function delrow() {
	var arrrows = $("#local_acl_table").getGridParam("selarrrow");
	if (arrrows.length == 0) {
		alert("削除するレコードを選択してください");
	} else {
		var len = arrrows.length;
		for ( i = len - 1; i >= 0; i--) {
			$("#local_acl_table").delRowData(arrrows[i]);
		}
	}
}

// ////////////////////
// Update ACL
// ////////////////////
function update() {
	var ret = confirm("権限情報を保存します。よろしいですか？");

	if (!ret) {
		return false;
	}

	// Store jqGrid data to the hidden tag as JSON
	var gridAcl = $("#local_acl_table").jqGrid('getRowData');
	var acl = convertGridDataForUpdate(gridAcl);
	var acl_json = JSON.stringify(acl);
	$("#acl_entries").val(acl_json);

	$('#permission_form').submit();
}

/**
 * Format jqGridData as a list of the hash like:
 * {principal: "admin", permissions: ["cmis:read", "cmis:write"]}
 * @param {Object} gridAcl
 */
function convertGridDataForUpdate(gridAcl){
	var acl = [];
	for(var i = 0; i < gridAcl.length; i++){
		var gridAce = gridAcl[i];

		var ace = {};
		ace.permissions = {};
		for(var key in gridAce){
			if(key == "principal"){
				ace.principal = gridAcl[i]["principal"];		
			}
			if(key.indexOf(PREFIX_PERMISSION) >= 0){
				var permKey = key.substring(PREFIX_PERMISSION.length, key.length);
				ace.permissions[permKey] = gridAce[key];
			}
		}
		
		acl.push(ace);
	}
	return acl;
}

// ////////////////////////////////////////////////////////////////
// jQuery Plugin Definition
// ////////////////////////////////////////////////////////////////
$(function() {
	// ////////////////////
	// Switching ACL
	// ////////////////////
	$('#inheritance_button').on('switch-change', function(e, data) {
		value = data.value;
		$("#acl_inheritance").val(value);
		if (value) {
			$("#inherited_acl_table").jqGrid('setGridState', 'visible');
		} else {
			$("#inherited_acl_table").jqGrid('setGridState', 'hidden');
		}
	});

	// ////////////////////
	// User search form
	// ////////////////////
	$('#user_search').bind('ajax:success', function(xhr, data, status) {
		// Erase jqGrid once
		$("#user_table").GridUnload();
		users = [];
		
		// Build update data
		if ($('#search_target').val() == 'user') {
			for ( i = 0; i < data.length; i++) {
				users.push({
					principal : data[i].userId
				});
			}
		} else {
			for ( i = 0; i < data.length; i++) {
				users.push({
					principal : data[i].groupId
				});
			}
		}
		
		// Redraw jqGrid
		createUserTable();
	});

	// ////////////////////
	// Group search form
	// ////////////////////
	$('#group_search').bind('ajax:success', function(xhr, data, status) {
		// Erase jqGrid
		$("#user_table").GridUnload();
		users = [];
		
		// Build update data
		for ( i = 0; i < data.length; i++) {
			users.push({
				principal : data[i].groupId
			});
		}
		// Redraw jqGrid
		createUserTable();
	});

	// //////////////////////////
	// jqGrid: User Search Result
	// //////////////////////////
	var userColModelSettings = [{
		name : "principal",
		index : "principal",
		align : "left",
		classes : "principal_class"
	}];
	var userColNames = [I18n.t("view.node.permission.user_and_group_id")];
	// CREATE
	createUserTable();
	function createUserTable() {
		var tableParams_user = $.extend(true, {}, baseTableParams);
		tableParams_user.caption = I18n.t("view.node.permission.user_search_results");
		tableParams_user.data = users;
		tableParams_user.colNames = userColNames;
		tableParams_user.colModel = userColModelSettings;
		$("#user_table").jqGrid(tableParams_user);
	}

	;

	// ////////////////////////////
	// jqGrid: ACL
	// //////////////////////////
	function buildAclColNames() {
		//return [I18n.t("view.node.permission.user_and_group_id"), I18n.t("view.node.permission.permission")];
		
		var names = [];
		//Principal part
		names.push(I18n.t("view.node.permission.user_and_group_id"));
		
		//Permissions part
		for(var i = 0; i < permissions.length; i++){
			names.push(permissions[i]);
		}
		return names;
	}

	function buildAclColModelSettings() {
		var settings = [];

		//Principal part
		var principal = {
			name : "principal",
			index : "principal",
			width : 200,
			align : "left",
			classes : "principal_class"
		};
		settings.push(principal);

		//Permissions part
		var value = {};
		for (var i = 0; i < permissions.length; i++) {
			//value[i] = permissions[i];
			var checkbox =
				{
					name : PREFIX_PERMISSION + permissions[i],
					index : PREFIX_PERMISSION + permissions[i],
					width : 50,
					align : "left",
					classes : "permissions_class",
					editable : true,
					edittype : 'checkbox',
					editoptions : {
						value: "True:False"
					},
					formatter: "checkbox",
					formatoptions: { disabled: false}
				};
			settings.push(checkbox);
		}
		return settings;
	}

	// Inherited ACL Table
	createInheritedAclTable();
	function createInheritedAclTable() {
		var aclColNames = buildAclColNames();
		var aclColModelSettings = buildAclColModelSettings();

		var tableParams_inheritedAcl = $.extend(true, {}, baseTableParams);
		tableParams_inheritedAcl.caption = I18n.t("view.node.permission.inherited_permissions");
		tableParams_inheritedAcl.data = inherited_acl;
		tableParams_inheritedAcl.colNames = aclColNames;
		tableParams_inheritedAcl.colModel = aclColModelSettings;
		tableParams_inheritedAcl.width = 360;
		tableParams_inheritedAcl.multiselect = false;
		tableParams_inheritedAcl.hiddengrid = !aclInheritance;
		tableParams_inheritedAcl.hidegrid = !aclInheritance;

		$("#inherited_acl_table").jqGrid(tableParams_inheritedAcl);
	};

	// Local ACL Table
	createLocalAclTable();
	function createLocalAclTable() {
		var aclColNames = buildAclColNames();
		var aclColModelSettings = buildAclColModelSettings();

		//$.extend(ColModel) doesn't work causing the error "length ColNames <> ColModel"
		aclColModelSettings[1].editable = true;

		var tableParams_localAcl = $.extend(true, {}, baseTableParams);
		tableParams_localAcl.caption = I18n.t("view.node.permission.local_permissions");
		tableParams_localAcl.data = local_acl;
		tableParams_localAcl.colNames = aclColNames;
		tableParams_localAcl.colModel = aclColModelSettings;
		tableParams_localAcl.width = 360;

		$("#local_acl_table").jqGrid(tableParams_localAcl);
	};
});
