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


/**
 * ACL JSON is passed from Ruby like as:
 * {CONST_PRINCIPAL:"GROUP_EVERYONE","permissions":["cmis:read"],"direct":false}
 */

/**
 * ABOUT jqGrid's bizarre actions:
 * 1)jqGrid GridnUnload & redraw causes the header/column width lag.
 * 	 WORKAROUND: Delete all rows and add new rows to refresh a table.
 * 2)Presupposed 1), when a column is sorted the old data before refreshing appears.
 *   WORKAROUND: Disable all 'sortable' in each colModelSettings.
 */

/**
 * DO NOT MODIFY GLOBAL VARIABLES!
 */
var PREFIX_PERMISSION = "permission_";
var CONST_TYPE = "type";
var CONST_PRINCIPAL = "principal";
var CONST_INHERITANCE = "inheritance";
var CONST_USER = "user";
var CONST_GROUP = "group";
var CONST_TRUE = "True";
var CONST_FALSE = "False";

var DOM_ACL_TABLE = "acl_table";
var DOM_ACL_PAGER = "acl_pager";
var DOM_ACL_TABLE_INHERITED = "acl_table_inherited";
var DOM_ACL_PAGER_INHERITED = "acl_pager_inherited";
var DOM_PRINCIPAL_TABLE = "principal_table";
var DOM_PRINCIPAL_PAGER = "principal_pager";
var DOM_SEARCH_TARGET = "search_target";
var DOM_PRINCIPAL_SEARCH = "principal_search";
var DOM_INHERITANCE_BUTTON = 'inheritance_button';

var GLOBAL_ACL_JSON = [];
var GLOBAL_PARENT_ACL_JSON = [];
var GLOBAL_ACL_INHERITANCE;
var GLOBAL_PERMISSIONS;
var GLOBAL_BASE_TABLE_PARAMS = {
    data: [],
    datatype: "local",
    colNames: [],
    colModel: [],
    rowNum: 7,
    rowList: [1, 10, 20],
    caption: "",
    cellEdit: true,
    cellsubmit: 'clientArray',
    height: 200,
    width: 300,
    forceFit: true,
    shrinkToFit: false,
    viewrecords: true,
    gridview: true,
    scroll: true,
    sortname: 'no',
    sortorder: "ASC",
    multiselect: true,
    hidegrid: false
};

/**
 * Copy data from Ruby to global variables once and for all
 * @param {} aclJSON
 * @param {} parentAclJSON
 * @param {} aclInheritance
 * @param {} permissions
 * @returns {}
 */

function copyData(aclJSON, parentAclJSON, aclInheritance, permissions) {
    //Copy and store values from Ruby to global variables
    this.GLOBAL_ACL_JSON = aclJSON;
    this.GLOBAL_PARENT_ACL_JSON = parentAclJSON;
    this.GLOBAL_ACL_INHERITANCE = aclInheritance;
    this.GLOBAL_PERMISSIONS = permissions;
}


// ////////////////////////////////////////////////////////////////
// Functions called on jQuery loading
// ////////////////////////////////////////////////////////////////
$(function() {
    // /////////////////////
    // Principal search form
    // /////////////////////
    $("#" + DOM_PRINCIPAL_SEARCH).bind('ajax:success', function(xhr, data, status) {
        //Delete existing rows
        deleteAllRows(DOM_PRINCIPAL_TABLE);

        // Build update data
        var principals = [];
        if ($("#" + DOM_SEARCH_TARGET).val() == CONST_USER) {
            //User
            for (i = 0; i < data.length; i++) {
                principals.push({
                    type: I18n.t("view.node.permission.principal_type_user"),
                    principal: data[i].userId
                });
            }
        } else {
            //Group
            for (i = 0; i < data.length; i++) {
                principals.push({
                    type: I18n.t("view.node.permission.principal_type_group"),
                    principal: data[i].groupId
                });
            }
        }

        //Add new rows
        $("#" + DOM_PRINCIPAL_TABLE).addRowData(
        undefined, principals);
    });


    // ///////////////////////////////
    // jqGrid: Principal Search Result
    // ///////////////////////////////

    function buildUserColNames() {
        return [
        I18n.t("view.node.permission.principal_type"), I18n.t("view.node.permission.principal_id")];
    }

    function buildUserColModelSettings() {
        return [{
            name: CONST_TYPE,
            index: CONST_TYPE,
            align: "left",
            width: 50,
            sortable: false
        }, {
            name: CONST_PRINCIPAL,
            index: CONST_PRINCIPAL,
            align: "left",
            width: 180,
            sortable: false
        }];
    }

    // Create
    createPrincipalTable([]);
    function createPrincipalTable(principals) {
        var tableParams_principal = $.extend(true, {}, GLOBAL_BASE_TABLE_PARAMS);
        tableParams_principal.caption = I18n.t("view.node.permission.principal_search_results");
        tableParams_principal.colNames = buildUserColNames();
        tableParams_principal.colModel = buildUserColModelSettings();
        tableParams_principal.data = principals;
        tableParams_principal.width = 280;
        tableParams_principal.height = 80;
        tableParams_principal.pager = DOM_PRINCIPAL_PAGER;

        $("#" + DOM_PRINCIPAL_TABLE).jqGrid(tableParams_principal);
    };


    // //////////////////////////
    // jqGrid: ACL
    // //////////////////////////
    function buildAclColNames() {
        var names = [];

        //Principal part
        names.push(I18n.t("view.node.permission.principal_id"));

        //Permissions part
        for (var i = 0; i < GLOBAL_PERMISSIONS.length; i++) {
			var name = GLOBAL_PERMISSIONS[i];
            //Remove <<cmis:>>
            var cmisPrefix = "cmis:";
            if(name.indexOf(cmisPrefix) >= 0){
            	_name = name.substring(cmisPrefix.length, name.length);
            	names.push(_name);
            }else{
            	names.push(name);
            }

        }

        return names;
    }

    function buildAclColModelSettings() {
        var settings = [];

        //Principal part
        var principal = {
            name: CONST_PRINCIPAL,
            index: CONST_PRINCIPAL,
            width: 140,
            align: "left",
            editable: false,
            sortable: false
        };
        settings.push(principal);

        //Permissions part
        var value = {};
        for (var i = 0; i < GLOBAL_PERMISSIONS.length; i++) {
            var checkbox = {
                name: PREFIX_PERMISSION + GLOBAL_PERMISSIONS[i],
                index: PREFIX_PERMISSION + GLOBAL_PERMISSIONS[i],
                width: 30,
                align: "left",
                editable: true,
                edittype: 'checkbox',
                editoptions: {
                    value: CONST_TRUE + ":" + CONST_FALSE
                },
                formatter: "checkbox",
                formatoptions: {
                    disabled: false
                },
                sortable: false
            };
            settings.push(checkbox);
        }

        return settings;
    }

     function buildGridData(aclJSON) {
        var acl = [];

        for (var i = 0; i < aclJSON.length; i++) {
            var a = aclJSON[i];

            var rowData = {
                principal: a.principal,
				//For filtering use
                direct: a.direct
            };

            for (var j = 0; j < GLOBAL_PERMISSIONS.length; j++) {
                if ($.inArray(GLOBAL_PERMISSIONS[j], a.permissions) >= 0) {
                    rowData[PREFIX_PERMISSION + GLOBAL_PERMISSIONS[j]] = CONST_TRUE;
                } else {
                    rowData[PREFIX_PERMISSION + GLOBAL_PERMISSIONS[j]] = CONST_FALSE;
                }
            }
            acl.push(rowData);
        }
        return acl;
    }

    /**
	 * Return rows having directFlag
	 * @param {} data
	 * @param {} directFlag
	 * @returns {}
	 */
    function filterGridData(data, directFlag) {
        result = [];
        for (var i = 0; i < data.length; i++) {
            if (data[i].direct == directFlag) {
                result.push(data[i]);
            }
        }
        return result;
    }

    // Create(local ACL table)
    var local_acl = filterGridData(buildGridData(GLOBAL_ACL_JSON), true);
    createAclTable(DOM_ACL_TABLE, DOM_ACL_PAGER, local_acl, false);

	// Create(inherited ACL table)
    var inherited_acl = filterGridData(buildGridData(GLOBAL_ACL_JSON), false);
    createAclTable(DOM_ACL_TABLE_INHERITED, DOM_ACL_PAGER_INHERITED, inherited_acl, true);
	disableAllRows(DOM_ACL_TABLE_INHERITED);
	hideSelectButtons(DOM_ACL_TABLE_INHERITED);

    function createAclTable(gridDom, pagerDom, data, inherited) {
        //Prepare table parameters
        var tableParams_acl = $.extend(true, {}, GLOBAL_BASE_TABLE_PARAMS);
		
		tableParams_acl.caption = I18n.t("view.node.permission.permission_table");
        tableParams_acl.data = data;
        tableParams_acl.colNames = buildAclColNames();
        colModel = buildAclColModelSettings();
        tableParams_acl.colModel = colModel;
        tableParams_acl.height = 125;
        tableParams_acl.width = 375;
        tableParams_acl.pager = pagerDom;

        //Draw
        $("#" + gridDom).jqGrid(tableParams_acl);
    };


    // /////////////////////////////
    // Switching Inheritance
    // /////////////////////////////
    //FIXME Duplicate in createAclTables()

    function disableAllRows(gridDom) {
        var ids = $("#" + gridDom).jqGrid('getDataIDs');
        for (var i = 0; i < ids.length; i++) {
            $("#" + ids[i]).children("td").children("input").attr("disabled", true);
        }
    }

    $("#" + DOM_INHERITANCE_BUTTON).on('switch-change', function(e, data) {
        value = data.value;
		var inheritedTable = $("#" + DOM_ACL_TABLE_INHERITED);

        if (value == GLOBAL_ACL_INHERITANCE) {
        	//Make table contents before switched
            deleteAllRows(DOM_ACL_TABLE_INHERITED);
            data = filterGridData(buildGridData(GLOBAL_ACL_JSON), false);
            inheritedTable.addRowData(
            undefined, data);
        } else if (value) {
            //aclInheritance = OFF / switch >> ON
            inheritedTable.addRowData(
            	undefined,
            	buildGridData(GLOBAL_PARENT_ACL_JSON)
            );
        } else {
        	//aclInheritance = ON / switch >> OFF
            deleteAllRows(DOM_ACL_TABLE_INHERITED);
        }

		disableAllRows(DOM_ACL_TABLE_INHERITED);
        hideSelectButtons(DOM_ACL_TABLE_INHERITED);
    });
});


// ////////////////////////////////////////////////////////////////
// Functions called from Rails view
// ////////////////////////////////////////////////////////////////
// ////////////////////
// Table Add/Remove
// ////////////////////
/**
 * Add a new principal from Principal table to Acl table
 * @returns {}
 */
function addPrincipalToAcl() {
	var userTable = $("#" + DOM_PRINCIPAL_TABLE);
    selectedIds = userTable.getGridParam('selarrrow');
    for (i = 0; i < selectedIds.length; i++) {
        row = userTable.getRowData(selectedIds[i]);

        var gridAcl = $("#" + DOM_ACL_TABLE).jqGrid('getRowData');
        var p = row.principal;
        if (isDuplicatePrincipal(p, gridAcl)) {
            //Validation
            window.alert(p + ":" + I18n.t("view.node.permission.principal_already_added"));
        } else {
            //Add
            addRow(p);
        }
    }
}

function addRow(principal) {
    var aclTable = $("#" + DOM_ACL_TABLE);
    var arrows = aclTable.getRowData();
    var max = arrows.length;

    aclTable.addRowData(
    undefined, {
        principal: principal,
        //By default, no permission set
        permissions: {}
    });
}

/**
 * Remove a row from Acl table
 * @returns {}
 */
function removePrincipalFromAcl() {
	var aclTable = $("#" + DOM_ACL_TABLE);

    var arrrows = aclTable.getGridParam("selarrrow");
    if (arrrows.length == 0) {
        alert(I18n.t("view.node.permission.select_rows_to_delete"));
    } else {
        var len = arrrows.length;
        for (i = len - 1; i >= 0; i--) {
            var r = aclTable.getRowData(arrrows[i]);

            if (r.inheritance == CONST_TRUE) {
                //Validation
                window.alert(r.principal + ":" + I18n.t("view.node.permission.inherited_permission_cannot_be_modified"));
            } else {
                //Delete
                aclTable.delRowData(arrrows[i]);
            }
        }
    }
}

// ////////////////////
// Update ACL
// ////////////////////
/**
 * Send updated ACL data to server
 * @returns {}
 */
function update() {
    var ret = confirm(I18n.t("view.node.permission.confirm_update"));

    if (!ret) {
        return false;
    }

    // Store jqGrid data to the hidden tag as JSON
    var gridAcl = $("#" + DOM_ACL_TABLE).jqGrid('getRowData');
    var acl = convertGridDataForUpdate(gridAcl);
    var acl_json = JSON.stringify(acl);
    //TODO const
    $("#acl_entries").val(acl_json);

    $('#permission_form').submit();
}

/**
 * Format jqGridData as a list of the hash like:
 * {principal: "admin", permissions: ["cmis:read", "cmis:write"]}
 * @param {Object} gridAcl
 */
function convertGridDataForUpdate(gridAcl) {
    var acl = [];
    for (var i = 0; i < gridAcl.length; i++) {
        var gridAce = gridAcl[i];

        //Send only localACEs
        if (gridAce.inheritance == CONST_TRUE) continue;

        var ace = {};
        ace.permissions = {};
        for (var key in gridAce) {
            if (key == CONST_PRINCIPAL) {
                ace.principal = gridAce[CONST_PRINCIPAL];
            }
            if (key.indexOf(PREFIX_PERMISSION) >= 0) {
                var permKey = key.substring(PREFIX_PERMISSION.length, key.length);
                ace.permissions[permKey] = gridAce[key];
            }
        }
        acl.push(ace);
    }
    return acl;
}


// ////////////////////////////////////////////////////////////////
// Utility
// ////////////////////////////////////////////////////////////////

function deleteAllRows(gridDom) {
    var table = $("#" + gridDom);
    var ids = table.jqGrid('getDataIDs');
    for (var i = 0; i < ids.length; i++) {
        table.delRowData(ids[i]);
    }
}

function calculateInheritedAcl(targetAcl, sourceAcl) {
    var result = [];
    var targetMap = buildMap(targetAcl);
    var sourceMap = buildMap(sourceAcl);

    //Prepare and Filter
    for (var s in sourceMap) {
        if (!(s in targetMap)) {
            result.push(s);
        }
    }
    return result;
}

function buildMap(aclArray) {
    var map = {};
    if (aclArray) {
        for (var i = 0; i < aclArray.length; i++) {
            ace = aclArray[i];
            map[ace[CONST_PRINCIPAL]] = ace;
        }
    }
    return map;
}

function buildArray(aclMap) {
    array = [];
    if (aclMap) {
        for (var a in aclMap) {
            array.push(a);
        }
    }
    return array;
}

function isDuplicatePrincipal(principal, jqGridData){
	for (var i = 0; i < jqGridData.length; i++) {
        var gridAce = jqGridData[i];
        if (principal == gridAce[CONST_PRINCIPAL]) {
            return true;
        }
    }
    return false;
}

function hideSelectButtons(gridDom){
	//Hide CheckAll button
	$("#cb_" + gridDom).hide();
	
	//Hide MultiSelect button
	var ids = $("#" + gridDom).jqGrid('getDataIDs');
	for(var i = 0; i < ids.length; i++){
		s = $("#" + gridDom + "_" + ids[i]);
		$("#jqg_" + gridDom + "_" + ids[i]).hide();
	}
}
