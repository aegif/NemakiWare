//////////////////////////////////////////////////////////////////
//Add record to local jqGrid
//////////////////////////////////////////////////////////////////


/**
 *@tableName: name of the destination table 
 */
function addSearchResult(tableName){
	//ユーザjqGrid上で選択済みのユーザを取得
	selectedIds = $("#search_table").getGridParam('selarrrow');
	for(i=0; i < selectedIds.length; i++){
		row = $("#search_table").getRowData(selectedIds[i]);
		addrow(tableName, row);			
	}
}

/**
 *@tableName: name of the destination table 
 */
function buildAddedData(tableName, row){
	var tmpData;
	if(tableName === "member_table"){
		tmpData = { 
			principalId: row.principalId
		};
	}
	return tmpData;
}

/**
 *@tableName: name of the destination table 
 */
function addrow(tableName, row){
	var tableSelector = "#" + tableName;
	var arrows = $(tableSelector).getRowData();
	var max = arrows.length;
		
	var tmpData = buildAddedData(tableName, row);
	
	$(tableSelector).addRowData(undefined, tmpData);
}

//////////////////////////////////////////////////////////////////
//Principal Search Form 
//////////////////////////////////////////////////////////////////
$(function() {
	// ////////////////////
	// ユーザ検索フォーム
	// ////////////////////
	$('#principal_search')
	.bind('ajax:success', function(xhr, data, status) {
		// jqGridを一旦消去
		$("#search_table").GridUnload();
		// 更新データの作成
		results = [];
		for(i=0; i < data.length; i++){
			if(data[i].userId){
				results.push({principalId:data[i].userId});
			}
			if(data[i].groupId){
				results.push({principalId:data[i].groupId});
			}
		}
		// jqGridの再描画
		createSearchTable();
	});
	
	// ////////////////////
	// ユーザ検索結果表示jqGrid
	// ////////////////////
	// 列の設定
	var userColModelSettings= [	
		{name:"principalId",index:"principal",width:240,align:"center",classes:"principal_class"},
	]
	// 列の表示名
	var userColNames = ["ID"];
	// 初期データ
	var results = [];
	// テーブルの作成
	createSearchTable();
	function createSearchTable(){
		$("#search_table").jqGrid({
			data:results,
			datatype : "local",
			colNames : userColNames,
			colModel : userColModelSettings,
			rowNum : 4,
			rowList : [1, 10, 20],
			caption : "ユーザ/グループ検索結果",
			height : 60,
			width : 300,
			cellEdit: false,
			cellsubmit: 'clientArray',
			pager : 'search_pager',
			shrinkToFit : true,
			viewrecords: true, 
			scroll:true,
			sortname: 'no',
			sortorder: "ASC",
			multiselect: true
		}); 
	};
});