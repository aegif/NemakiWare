function redirectFromPopup(url){
	alert(url);
	window.top.location.href = url;
}