//////////////////////////////////////////////////////////////////
//Display member(users) list
//////////////////////////////////////////////////////////////////

//CAUTION:
//Please set values as "var members = ..." BEFORE including this script

$(function() {
	//////////////////////
	//Check initial data
	//////////////////////
	if(!members || members.length == 0){
		members = [];
	}

	//////////////////////
	//Table definition
	//////////////////////
	
	//Column setting
	var userColModelSettings= [	
		{name:"principalId",index:"principal",width:240,align:"center",classes:"principal_class"},
	]
	//Column display name	
	var userColNames = ["ID"];
	
	//Create the table
	createMemberTable();
	function createMemberTable(){
		$("#member_table").jqGrid({
			data:members,
			datatype : "local",
			colNames : userColNames,
			colModel : userColModelSettings,
			rowNum : 4,
			rowList : [1, 10, 20],
			caption : "メンバ一覧",
			height : 240,
			width : 300,
			cellEdit: false,
			cellsubmit: 'clientArray',
			pager : 'member_pager',
			shrinkToFit : true,
			viewrecords: true,
			scroll:true,
			sortname: 'no',
			sortorder: "ASC",
			multiselect: true
		});
	};
});


//////////////////////////////////////////////////////////////////
//Update to the server
//////////////////////////////////////////////////////////////////
function update(){
	var ret = confirm("メンバ情報を更新します。よろしいですか？");

	if (!ret) {
		return false;
	}

    values = $("#member_table").jqGrid('getRowData');
	json = JSON.stringify(values);
	$("#principals_json").val(json);
    $('#update_form').submit();
}
 
//////////////////////////////////////////////////////////////////
//Remove record from local jqGrid
//////////////////////////////////////////////////////////////////
function delrow(tableName){
	var tableSelector = "#" + tableName;
	var arrrows = $(tableSelector).getGridParam("selarrrow");
	if (arrrows.length == 0) {
		alert("削除するレコードを選択してください");
	}else{
		var len = arrrows.length;
		for(i = len-1; i >= 0; i--) {
			$(tableSelector).delRowData(arrrows[i]);
		}
	}
}