@(repositoryId:String)

/**
 */
var nmk = {};

/**
 */
nmk.dragAndDrop = {
	/**
	 * 
	 */
	handleDragStart : function(event) {
		event.preventDefault();
		$(".dropzone").css("border", "3px solid #74A9D6");
	},

	/**
	 * 
	 */
	hadleDragLeave : function(event) {
		event.preventDefault();
		$(".dropzone").css("border", "none");
	},

	/**
	 * 
	 */
	handleDrop : function(event) {
		event.preventDefault();
		event.stopPropagation();

		var uploadCount = 0;
		var errCount = 0;
		
		// Message for the browsers out of support
		if (!window.FormData) {
			alert("@Messages("view.node.drag-and-drop.out-of-support")");
			return;
		}

		// CSS effect
		$(".dropzone").css("border", "none");

		// Process
		$.blockUI({ message: "@Messages("view.message.please.wait")"});
		var NAMES_WITH_ID = namesWithId();
		process(event.dataTransfer.files)
		.always(function(data){
			$.unblockUI();
			
			if(!uploadCount && !errCount){
				return; //do nothing
			}
			
			if(uploadCount && !errCount){
				notifyAllSuccess(uploadCount);
			}else if(!errCount ){
				notifySomeErrors(uploadCount, errCount)
			}
			
			window.location.reload();
		});
		
		// *****************
		// Library
		// *****************
		function process(files){
			function checkPattern(file){
				return function(){
					var defer = $.Deferred();
					var modalDom = $('#nmk-dd-duplicate-confirm');
					if(checkDuplicate(file.name, Object.keys(NAMES_WITH_ID))){
						//Set modal window contents
						modalDom.find('.old-name').text(file.name);
						var newName = buildNewName(file.name, Object.keys(namesWithId()));
						modalDom.find('.new-name').text(newName);
						
						//Show modal window
						modalDom.modal('show');
						
						//Bind to resolve deferred object
						var action = function(){
							var flg = $(this).data('flg');
							modalDom.modal('hide');
							defer.resolve({file:file, flg:flg, newName: newName});
						};
						$('.nmk-action').on('click', action);
						modalDom.on('hidden.bs.modal', function(e){
							$('.nmk-action').unbind('click', action);
							if(defer.state() != 'resolved'){
								defer.resolve(null);
							}
						});
					}else{
						defer.resolve({file:file, flg:"std"});
					}
					
					return defer.promise();
				};
			}
			
			/**
			 * 
			 */
			function sendFile(obj){
				if(obj == undefined || obj.file == undefined){
					return $.Deferred().resolve().promise();
				}
				var file = obj.file;
				var flg = obj.flg;
				var newName = obj.newName;
				
				var defer;
				switch(flg){
				case 'std':
					defer = create(file, file.name);
					break;
				case 'rename':
					defer = create(file, newName);
					break;	
				case 'replace':
					var objectId = NAMES_WITH_ID[file.name];
					defer = update(file, objectId);
					break;	
				}
				
				defer.done(function(){
					uploadCount += 1;
				}).fail(function(jqXHR, textStatus, errorThrown){
					errCount += 1;
					console.log( 'ERROR', obj, jqXHR, textStatus, errorThrown );
				});
				
				return defer.promise();
			}
			
			//Main process chain
			var res = 
				$.map(files, function(elm, idx){
					return checkPattern(files[idx]);}
				).reduce(function(x,y){
					return x.then(y).then(sendFile);
				}, $.Deferred().resolve());
			return res;
		}
		
		function namesWithId() {
			var json = {};
			var list = $('#objects-table').find('.obj-name');
			var names = list.map(function() {
				json[$(this).text()] = $(this).attr('objectId');
			});
			return json;
		}

		function checkDuplicate(name, names) {
			var namesLower = $.map(names, function(n,i){return n.toLowerCase();});
			var duplicate = ($.inArray(name.toLowerCase(), namesLower) >= 0);
			return duplicate;
		}
		
		function buildFormData(file) {
			var formData = new FormData();
			formData.append("cmis:parentId", $("#list").attr('parentId'));
			formData.append("cmis:objectTypeId", 'cmis:document');
			formData.append("file", file);
			return formData;
		}
		
		function create(file, name){
			var formData = buildFormData(file);
			formData.append("cmis:name", name);
			return submit(formData, "create");
		}

		function update(file, id){
			var formData = buildFormData(file);
			formData.append("cmis:objectId", id);
			return submit(formData, "update");
		}
		
		function submit(formData, action) {
			var restUrl = "@routes.Node.dragAndDrop(repositoryId)";
			var res = $.ajax(restUrl + "?action=" + action, {
				type : 'post',
				processData : false,
				contentType : false,
				data : formData
			});
			return res;
		}
		
		function buildNewName(name, names){
			var ary = name.split(".");

			if(ary.length < 2){
				return name;  
			}
			
			var ext = ary[ary.length - 1];
			var oldBody = ary.slice(0, -1).join(".");
			
			var f = function(b, i){
				var nameWithExt = [b, ext].join(".");
				if(!checkDuplicate(nameWithExt, Object.keys(NAMES_WITH_ID))){
					return nameWithExt;
				}
				var m = b.match(/\([0-9]+\)$/);
				if(m){
					var suffix = m[0];
					b = b.substr(0, b.length - suffix.length);
				}
				return f(b + "(" + i + ")", ++i);
			};
			return f(oldBody, 2);
		}
		
		function notifyAllSuccess(uploadCount){
			alert("すべてのファイル" + 
					"(" + 
					uploadCount + 
					"件)が正常にアップロードされました");
		}
		
		function notifySomeErrors(uploadCount, errCount){
			alert(event.dataTransfer.files.length + 
					"件中:"+  
					uploadCount + 
					"件成功"+
					", " + 
					errCount + 
					"件失敗");
		}
		
	}
};

//Binding: nmk-action hover
$(".nmk-action").hover(function() {
	$(this).addClass("hover");
}, function() {
	if ($(this).hasClass("hover")) {
		$(this).removeClass("hover");
	}
});