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

var inherited_acl = [];
var local_acl = [];
var users = [];
var aclInheritance;

var baseTableParams = {
	data : [],
	datatype : "local",
	colNames : [],
	colModel : [],
	rowNum : 7,
	rowList : [ 1, 10, 20 ],
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
function buildGridData(aclJSON, inheritance) {
	for ( var i = 0; i < aclJSON.length; i++) {
		var a = aclJSON[i];
		var rowData = {
			principal : a.principal,
			permissions : a.permissions[0]
		};
		if (a.direct) {
			local_acl.push(rowData);
		} else {
			inherited_acl.push(rowData);
		}
	}
	
	aclInheritance = inheritance;
}

/** 
 * Convert Input Tag to content value string
 *
 */
function convertSelectToValue(value) {
    //begin with <input
    if ( value.lastIndexOf('<select',0) === 0 ) {
        inputObj = $.parseHTML(value);
        return $('#' + inputObj[0].id + ' option:selected').text();
    }
    return value;
}
function convertSelectToValues(values) {
    for(key in values) {
	values[key].permissions = convertSelectToValue(values[key].permissions);
    }
    return values;
}



/**
 * ユーザをACL jqGridに追加
 */
function addPrincipalToAcl() {
	selectedIds = $("#user_table").getGridParam('selarrrow');
	for (i = 0; i < selectedIds.length; i++) {
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
 * ACL jqGridからレコード削除
 */
function delrow() {
	var arrrows = $("#local_acl_table").getGridParam("selarrrow");
	if (arrrows.length == 0) {
		alert("削除するレコードを選択してください");
	} else {
		var len = arrrows.length;
		for (i = len - 1; i >= 0; i--) {
			$("#local_acl_table").delRowData(arrrows[i]);
		}
	}
}

// ////////////////////
// ACLの更新
// ////////////////////
function update() {
	var ret = confirm("権限情報を保存します。よろしいですか？");

	if (!ret) {
		return false;
	}

	// グリッド内のデータをhiddenタグにJSONで格納
	var aclValues = $("#local_acl_table").jqGrid('getRowData');
        aclValues = convertSelectToValues(aclValues);
	var acl_json = JSON.stringify(aclValues);
	$("#acl_entries").val(acl_json);
	$('#permission_form').submit();
}

// ////////////////////////////////////////////////////////////////
// jQueryプラグイン定義
// ////////////////////////////////////////////////////////////////
$(function() {
	// ////////////////////
	// Switching ACL
	// ////////////////////
    $('#inheritance_button').on('switch-change', function (e, data) {
    	value = data.value;
    	$("#acl_inheritance").val(value);
    	if(value){
    		$("#inherited_acl_table").jqGrid('setGridState', 'visible');
    	}else{
    		$("#inherited_acl_table").jqGrid('setGridState', 'hidden');
    	}
    });
	
	// ////////////////////
	// User search form
	// ////////////////////
	$('#user_search').bind('ajax:success', function(xhr, data, status) {
		// jqGridを一旦消去
		$("#user_table").GridUnload();
	        users = [];
		// 更新データの作成
		for (i = 0; i < data.length; i++) {
			users.push({principal : data[i].userId});
		}
		// jqGridの再描画
		createUserTable();
	});

	// ////////////////////
	// Group search form
	// ////////////////////
	$('#group_search').bind('ajax:success', function(xhr, data, status) {
		// jqGridを一旦消去
		$("#user_table").GridUnload();
	        users = [];
		// 更新データの作成
		for (i = 0; i < data.length; i++) {
			users.push({principal : data[i].groupId});
		}
		// jqGridの再描画
		createUserTable();
	});
        

	// //////////////////////////
	// jqGrid: User Search Result
	// //////////////////////////
	var userColModelSettings = [ {
		name : "principal",
		index : "principal",
		align : "left",
		classes : "principal_class"
	} ];
	var userColNames = [ I18n.t("view.node.permission.user_and_group_id") ];
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
	// 列の設定
	var aclColModelSettings = [ 
	{
		name : "principal",
		index : "principal",
		width : 200,
		align : "left",
		classes : "principal_class"
	}, 
	{
		name : "permissions",
		index : "permissions",
		width : 100,
		align : "left",
		classes : "permissions_class",
		editoptions : {
			maxlength : 10
		},
		editable : false,
		edittype : 'select',
		editoptions : {
			value : {
				1 : "cmis:read",
				2 : "cmis:write",
				3 : "cmis:all"
			}
		}
	} ];
	var aclColNames = [I18n.t("view.node.permission.user_and_group_id"), I18n.t("view.node.permission.permission") ];

	// Inherited ACL Table
	createInheritedAclTable();
	function createInheritedAclTable() {
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