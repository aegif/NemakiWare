import{R as Ur,r as Hr}from"./index-SNZQGrHG.js";import{H as gv,M as yv}from"./hls-CZhmVaL9.js";import{C as sn}from"./custom-media-element-Ce0vsE-t.js";var Tv=Object.create,hh=Object.defineProperty,Av=Object.getOwnPropertyDescriptor,kv=Object.getOwnPropertyNames,Sv=Object.getPrototypeOf,wv=Object.prototype.hasOwnProperty,mh=function(e,t){return function(){return e&&(t=e(e=0)),t}},Ue=function(e,t){return function(){return t||e((t={exports:{}}).exports,t),t.exports}},Iv=function(e,t,i,a){if(t&&typeof t=="object"||typeof t=="function")for(var r=kv(t),n=0,s=r.length,o;n<s;n++)o=r[n],!wv.call(e,o)&&o!==i&&hh(e,o,{get:(function(l){return t[l]}).bind(null,o),enumerable:!(a=Av(t,o))||a.enumerable});return e},je=function(e,t,i){return i=e!=null?Tv(Sv(e)):{},Iv(!e||!e.__esModule?hh(i,"default",{value:e,enumerable:!0}):i,e)},vt=Ue(function(e,t){var i;typeof window<"u"?i=window:typeof global<"u"?i=global:typeof self<"u"?i=self:i={},t.exports=i});function Qi(e,t){return t!=null&&typeof Symbol<"u"&&t[Symbol.hasInstance]?!!t[Symbol.hasInstance](e):Qi(e,t)}var Zi=mh(function(){Zi()});function ph(e){"@swc/helpers - typeof";return e&&typeof Symbol<"u"&&e.constructor===Symbol?"symbol":typeof e}var vh=mh(function(){}),fh=Ue(function(e,t){var i=Array.prototype.slice;t.exports=a;function a(r,n){for(("length"in r)||(r=[r]),r=i.call(r);r.length;){var s=r.shift(),o=n(s);if(o)return o;s.childNodes&&s.childNodes.length&&(r=i.call(s.childNodes).concat(r))}}}),Rv=Ue(function(e,t){Zi(),t.exports=i;function i(a,r){if(!Qi(this,i))return new i(a,r);this.data=a,this.nodeValue=a,this.length=a.length,this.ownerDocument=r||null}i.prototype.nodeType=8,i.prototype.nodeName="#comment",i.prototype.toString=function(){return"[object Comment]"}}),Cv=Ue(function(e,t){Zi(),t.exports=i;function i(a,r){if(!Qi(this,i))return new i(a);this.data=a||"",this.length=this.data.length,this.ownerDocument=r||null}i.prototype.type="DOMTextNode",i.prototype.nodeType=3,i.prototype.nodeName="#text",i.prototype.toString=function(){return this.data},i.prototype.replaceData=function(a,r,n){var s=this.data,o=s.substring(0,a),l=s.substring(a+r,s.length);this.data=o+n+l,this.length=this.data.length}}),Eh=Ue(function(e,t){t.exports=i;function i(a){var r=this,n=a.type;a.target||(a.target=r),r.listeners||(r.listeners={});var s=r.listeners[n];if(s)return s.forEach(function(o){a.currentTarget=r,typeof o=="function"?o(a):o.handleEvent(a)});r.parentNode&&r.parentNode.dispatchEvent(a)}}),_h=Ue(function(e,t){t.exports=i;function i(a,r){var n=this;n.listeners||(n.listeners={}),n.listeners[a]||(n.listeners[a]=[]),n.listeners[a].indexOf(r)===-1&&n.listeners[a].push(r)}}),bh=Ue(function(e,t){t.exports=i;function i(a,r){var n=this;if(n.listeners&&n.listeners[a]){var s=n.listeners[a],o=s.indexOf(r);o!==-1&&s.splice(o,1)}}}),Dv=Ue(function(e,t){vh(),t.exports=a;var i=["area","base","br","col","embed","hr","img","input","keygen","link","menuitem","meta","param","source","track","wbr"];function a(h){switch(h.nodeType){case 3:return m(h.data);case 8:return"<!--"+h.data+"-->";default:return r(h)}}function r(h){var c=[],v=h.tagName;return h.namespaceURI==="http://www.w3.org/1999/xhtml"&&(v=v.toLowerCase()),c.push("<"+v+d(h)+o(h)),i.indexOf(v)>-1?c.push(" />"):(c.push(">"),h.childNodes.length?c.push.apply(c,h.childNodes.map(a)):h.textContent||h.innerText?c.push(m(h.textContent||h.innerText)):h.innerHTML&&c.push(h.innerHTML),c.push("</"+v+">")),c.join("")}function n(h,c){var v=ph(h[c]);return c==="style"&&Object.keys(h.style).length>0?!0:h.hasOwnProperty(c)&&(v==="string"||v==="boolean"||v==="number")&&c!=="nodeName"&&c!=="className"&&c!=="tagName"&&c!=="textContent"&&c!=="innerText"&&c!=="namespaceURI"&&c!=="innerHTML"}function s(h){if(typeof h=="string")return h;var c="";return Object.keys(h).forEach(function(v){var g=h[v];v=v.replace(/[A-Z]/g,function(_){return"-"+_.toLowerCase()}),c+=v+":"+g+";"}),c}function o(h){var c=h.dataset,v=[];for(var g in c)v.push({name:"data-"+g,value:c[g]});return v.length?l(v):""}function l(h){var c=[];return h.forEach(function(v){var g=v.name,_=v.value;g==="style"&&(_=s(_)),c.push(g+'="'+p(_)+'"')}),c.length?" "+c.join(" "):""}function d(h){var c=[];for(var v in h)n(h,v)&&c.push({name:v,value:h[v]});for(var g in h._attributes)for(var _ in h._attributes[g]){var y=h._attributes[g][_],T=(y.prefix?y.prefix+":":"")+_;c.push({name:T,value:y.value})}return h.className&&c.push({name:"class",value:h.className}),c.length?l(c):""}function m(h){var c="";return typeof h=="string"?c=h:h&&(c=h.toString()),c.replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;")}function p(h){return m(h).replace(/"/g,"&quot;")}}),gh=Ue(function(e,t){Zi();var i=fh(),a=Eh(),r=_h(),n=bh(),s=Dv(),o="http://www.w3.org/1999/xhtml";t.exports=l;function l(d,m,p){if(!Qi(this,l))return new l(d);var h=p===void 0?o:p||null;this.tagName=h===o?String(d).toUpperCase():d,this.nodeName=this.tagName,this.className="",this.dataset={},this.childNodes=[],this.parentNode=null,this.style={},this.ownerDocument=m||null,this.namespaceURI=h,this._attributes={},this.tagName==="INPUT"&&(this.type="text")}l.prototype.type="DOMElement",l.prototype.nodeType=1,l.prototype.appendChild=function(d){return d.parentNode&&d.parentNode.removeChild(d),this.childNodes.push(d),d.parentNode=this,d},l.prototype.replaceChild=function(d,m){d.parentNode&&d.parentNode.removeChild(d);var p=this.childNodes.indexOf(m);return m.parentNode=null,this.childNodes[p]=d,d.parentNode=this,m},l.prototype.removeChild=function(d){var m=this.childNodes.indexOf(d);return this.childNodes.splice(m,1),d.parentNode=null,d},l.prototype.insertBefore=function(d,m){d.parentNode&&d.parentNode.removeChild(d);var p=m==null?-1:this.childNodes.indexOf(m);return p>-1?this.childNodes.splice(p,0,d):this.childNodes.push(d),d.parentNode=this,d},l.prototype.setAttributeNS=function(d,m,p){var h=null,c=m,v=m.indexOf(":");if(v>-1&&(h=m.substr(0,v),c=m.substr(v+1)),this.tagName==="INPUT"&&m==="type")this.type=p;else{var g=this._attributes[d]||(this._attributes[d]={});g[c]={value:p,prefix:h}}},l.prototype.getAttributeNS=function(d,m){var p=this._attributes[d],h=p&&p[m]&&p[m].value;return this.tagName==="INPUT"&&m==="type"?this.type:typeof h!="string"?null:h},l.prototype.removeAttributeNS=function(d,m){var p=this._attributes[d];p&&delete p[m]},l.prototype.hasAttributeNS=function(d,m){var p=this._attributes[d];return!!p&&m in p},l.prototype.setAttribute=function(d,m){return this.setAttributeNS(null,d,m)},l.prototype.getAttribute=function(d){return this.getAttributeNS(null,d)},l.prototype.removeAttribute=function(d){return this.removeAttributeNS(null,d)},l.prototype.hasAttribute=function(d){return this.hasAttributeNS(null,d)},l.prototype.removeEventListener=n,l.prototype.addEventListener=r,l.prototype.dispatchEvent=a,l.prototype.focus=function(){},l.prototype.toString=function(){return s(this)},l.prototype.getElementsByClassName=function(d){var m=d.split(" "),p=[];return i(this,function(h){if(h.nodeType===1){var c=h.className||"",v=c.split(" ");m.every(function(g){return v.indexOf(g)!==-1})&&p.push(h)}}),p},l.prototype.getElementsByTagName=function(d){d=d.toLowerCase();var m=[];return i(this.childNodes,function(p){p.nodeType===1&&(d==="*"||p.tagName.toLowerCase()===d)&&m.push(p)}),m},l.prototype.contains=function(d){return i(this,function(m){return d===m})||!1}}),Lv=Ue(function(e,t){Zi();var i=gh();t.exports=a;function a(r){if(!Qi(this,a))return new a;this.childNodes=[],this.parentNode=null,this.ownerDocument=r||null}a.prototype.type="DocumentFragment",a.prototype.nodeType=11,a.prototype.nodeName="#document-fragment",a.prototype.appendChild=i.prototype.appendChild,a.prototype.replaceChild=i.prototype.replaceChild,a.prototype.removeChild=i.prototype.removeChild,a.prototype.toString=function(){return this.childNodes.map(function(r){return String(r)}).join("")}}),Mv=Ue(function(e,t){t.exports=i;function i(a){}i.prototype.initEvent=function(a,r,n){this.type=a,this.bubbles=r,this.cancelable=n},i.prototype.preventDefault=function(){}}),xv=Ue(function(e,t){Zi();var i=fh(),a=Rv(),r=Cv(),n=gh(),s=Lv(),o=Mv(),l=Eh(),d=_h(),m=bh();t.exports=p;function p(){if(!Qi(this,p))return new p;this.head=this.createElement("head"),this.body=this.createElement("body"),this.documentElement=this.createElement("html"),this.documentElement.appendChild(this.head),this.documentElement.appendChild(this.body),this.childNodes=[this.documentElement],this.nodeType=9}var h=p.prototype;h.createTextNode=function(c){return new r(c,this)},h.createElementNS=function(c,v){var g=c===null?null:String(c);return new n(v,this,g)},h.createElement=function(c){return new n(c,this)},h.createDocumentFragment=function(){return new s(this)},h.createEvent=function(c){return new o(c)},h.createComment=function(c){return new a(c,this)},h.getElementById=function(c){c=String(c);var v=i(this.childNodes,function(g){if(String(g.id)===c)return g});return v||null},h.getElementsByClassName=n.prototype.getElementsByClassName,h.getElementsByTagName=n.prototype.getElementsByTagName,h.contains=n.prototype.contains,h.removeEventListener=m,h.addEventListener=d,h.dispatchEvent=l}),Ov=Ue(function(e,t){var i=xv();t.exports=new i}),yh=Ue(function(e,t){var i=typeof global<"u"?global:typeof window<"u"?window:{},a=Ov(),r;typeof document<"u"?r=document:(r=i["__GLOBAL_DOCUMENT_CACHE@4"],r||(r=i["__GLOBAL_DOCUMENT_CACHE@4"]=a)),t.exports=r});function Nv(e){if(Array.isArray(e))return e}function Pv(e,t){var i=e==null?null:typeof Symbol<"u"&&e[Symbol.iterator]||e["@@iterator"];if(i!=null){var a=[],r=!0,n=!1,s,o;try{for(i=i.call(e);!(r=(s=i.next()).done)&&(a.push(s.value),!(t&&a.length===t));r=!0);}catch(l){n=!0,o=l}finally{try{!r&&i.return!=null&&i.return()}finally{if(n)throw o}}return a}}function $v(){throw new TypeError(`Invalid attempt to destructure non-iterable instance.
In order to be iterable, non-array objects must have a [Symbol.iterator]() method.`)}function Vo(e,t){(t==null||t>e.length)&&(t=e.length);for(var i=0,a=new Array(t);i<t;i++)a[i]=e[i];return a}function Th(e,t){if(e){if(typeof e=="string")return Vo(e,t);var i=Object.prototype.toString.call(e).slice(8,-1);if(i==="Object"&&e.constructor&&(i=e.constructor.name),i==="Map"||i==="Set")return Array.from(i);if(i==="Arguments"||/^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(i))return Vo(e,t)}}function Xt(e,t){return Nv(e)||Pv(e,t)||Th(e,t)||$v()}var Rr=je(vt()),Pu=je(vt()),Uv=je(vt()),Hv={now:function(){var e=Uv.default.performance,t=e&&e.timing,i=t&&t.navigationStart,a=typeof i=="number"&&typeof e.now=="function"?i+e.now():Date.now();return Math.round(a)}},_e=Hv,Br=function(){var e,t,i;if(typeof((e=Pu.default.crypto)===null||e===void 0?void 0:e.getRandomValues)=="function"){i=new Uint8Array(32),Pu.default.crypto.getRandomValues(i);for(var a=0;a<32;a++)i[a]=i[a]%16}else{i=[];for(var r=0;r<32;r++)i[r]=Math.random()*16|0}var n=0;t="xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g,function(l){var d=l==="x"?i[n]:i[n]&3|8;return n++,d.toString(16)});var s=_e.now(),o=s?.toString(16).substring(3);return o?t.substring(0,28)+o:t},Ah=function(){return("000000"+(Math.random()*Math.pow(36,6)<<0).toString(36)).slice(-6)},ot=function(e){if(e&&typeof e.nodeName<"u")return e.muxId||(e.muxId=Ah()),e.muxId;var t;try{t=document.querySelector(e)}catch{}return t&&!t.muxId&&(t.muxId=e),t?.muxId||e},Cs=function(e){var t;e&&typeof e.nodeName<"u"?(t=e,e=ot(t)):t=document.querySelector(e);var i=t&&t.nodeName?t.nodeName.toLowerCase():"";return[t,e,i]};function Bv(e){if(Array.isArray(e))return Vo(e)}function Wv(e){if(typeof Symbol<"u"&&e[Symbol.iterator]!=null||e["@@iterator"]!=null)return Array.from(e)}function Fv(){throw new TypeError(`Invalid attempt to spread non-iterable instance.
In order to be iterable, non-array objects must have a [Symbol.iterator]() method.`)}function lt(e){return Bv(e)||Wv(e)||Th(e)||Fv()}var Ni={TRACE:0,DEBUG:1,INFO:2,WARN:3,ERROR:4},Kv=function(e){var t=arguments.length>1&&arguments[1]!==void 0?arguments[1]:3,i,a,r,n,s,o=[console,e],l=(i=console.trace).bind.apply(i,lt(o)),d=(a=console.info).bind.apply(a,lt(o)),m=(r=console.debug).bind.apply(r,lt(o)),p=(n=console.warn).bind.apply(n,lt(o)),h=(s=console.error).bind.apply(s,lt(o)),c=t;return{trace:function(){for(var v=arguments.length,g=new Array(v),_=0;_<v;_++)g[_]=arguments[_];if(!(c>Ni.TRACE))return l.apply(void 0,lt(g))},debug:function(){for(var v=arguments.length,g=new Array(v),_=0;_<v;_++)g[_]=arguments[_];if(!(c>Ni.DEBUG))return m.apply(void 0,lt(g))},info:function(){for(var v=arguments.length,g=new Array(v),_=0;_<v;_++)g[_]=arguments[_];if(!(c>Ni.INFO))return d.apply(void 0,lt(g))},warn:function(){for(var v=arguments.length,g=new Array(v),_=0;_<v;_++)g[_]=arguments[_];if(!(c>Ni.WARN))return p.apply(void 0,lt(g))},error:function(){for(var v=arguments.length,g=new Array(v),_=0;_<v;_++)g[_]=arguments[_];if(!(c>Ni.ERROR))return h.apply(void 0,lt(g))},get level(){return c},set level(v){v!==this.level&&(c=v??t)}}},J=Kv("[mux]"),go=je(vt());function qo(){var e=go.default.doNotTrack||go.default.navigator&&go.default.navigator.doNotTrack;return e==="1"}function N(e){if(e===void 0)throw new ReferenceError("this hasn't been initialised - super() hasn't been called");return e}Zi();function we(e,t){if(!Qi(e,t))throw new TypeError("Cannot call a class as a function")}function Vv(e,t){for(var i=0;i<t.length;i++){var a=t[i];a.enumerable=a.enumerable||!1,a.configurable=!0,"value"in a&&(a.writable=!0),Object.defineProperty(e,a.key,a)}}function Bt(e,t,i){return t&&Vv(e.prototype,t),e}function k(e,t,i){return t in e?Object.defineProperty(e,t,{value:i,enumerable:!0,configurable:!0,writable:!0}):e[t]=i,e}function Na(e){return Na=Object.setPrototypeOf?Object.getPrototypeOf:function(t){return t.__proto__||Object.getPrototypeOf(t)},Na(e)}function qv(e,t){for(;!Object.prototype.hasOwnProperty.call(e,t)&&(e=Na(e),e!==null););return e}function kn(e,t,i){return typeof Reflect<"u"&&Reflect.get?kn=Reflect.get:kn=function(a,r,n){var s=qv(a,r);if(s){var o=Object.getOwnPropertyDescriptor(s,r);return o.get?o.get.call(n||a):o.value}},kn(e,t,i||e)}function Yo(e,t){return Yo=Object.setPrototypeOf||function(i,a){return i.__proto__=a,i},Yo(e,t)}function Yv(e,t){if(typeof t!="function"&&t!==null)throw new TypeError("Super expression must either be null or a function");e.prototype=Object.create(t&&t.prototype,{constructor:{value:e,writable:!0,configurable:!0}}),t&&Yo(e,t)}function Gv(){if(typeof Reflect>"u"||!Reflect.construct||Reflect.construct.sham)return!1;if(typeof Proxy=="function")return!0;try{return Boolean.prototype.valueOf.call(Reflect.construct(Boolean,[],function(){})),!0}catch{return!1}}vh();function jv(e,t){return t&&(ph(t)==="object"||typeof t=="function")?t:N(e)}function Qv(e){var t=Gv();return function(){var i=Na(e),a;if(t){var r=Na(this).constructor;a=Reflect.construct(i,arguments,r)}else a=i.apply(this,arguments);return jv(this,a)}}var mt=function(e){return Wr(e)[0]},Wr=function(e){if(typeof e!="string"||e==="")return["localhost"];var t=/^(([^:\/?#]+):)?(\/\/([^\/?#]*))?([^?#]*)(\?([^#]*))?(#(.*))?/,i=e.match(t)||[],a=i[4],r;return a&&(r=(a.match(/[^\.]+\.[^\.]+$/)||[])[0]),[a,r]},yo=je(vt()),Zv={exists:function(){var e=yo.default.performance,t=e&&e.timing;return t!==void 0},domContentLoadedEventEnd:function(){var e=yo.default.performance,t=e&&e.timing;return t&&t.domContentLoadedEventEnd},navigationStart:function(){var e=yo.default.performance,t=e&&e.timing;return t&&t.navigationStart}},Ds=Zv;function Ee(e,t,i){i=i===void 0?1:i,e[t]=e[t]||0,e[t]+=i}function Ls(e){for(var t=1;t<arguments.length;t++){var i=arguments[t]!=null?arguments[t]:{},a=Object.keys(i);typeof Object.getOwnPropertySymbols=="function"&&(a=a.concat(Object.getOwnPropertySymbols(i).filter(function(r){return Object.getOwnPropertyDescriptor(i,r).enumerable}))),a.forEach(function(r){k(e,r,i[r])})}return e}function zv(e,t){var i=Object.keys(e);if(Object.getOwnPropertySymbols){var a=Object.getOwnPropertySymbols(e);i.push.apply(i,a)}return i}function ad(e,t){return t=t??{},Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(t)):zv(Object(t)).forEach(function(i){Object.defineProperty(e,i,Object.getOwnPropertyDescriptor(t,i))}),e}var Xv=["x-cdn","content-type"],kh=["x-request-id","cf-ray","x-amz-cf-id","x-akamai-request-id"],Jv=Xv.concat(kh);function rd(e){e=e||"";var t={},i=e.trim().split(/[\r\n]+/);return i.forEach(function(a){if(a){var r=a.split(": "),n=r.shift();n&&(Jv.indexOf(n.toLowerCase())>=0||n.toLowerCase().indexOf("x-litix-")===0)&&(t[n]=r.join(": "))}}),t}function Ms(e){if(e){var t=kh.find(function(i){return e[i]!==void 0});return t?e[t]:void 0}}var ef=function(e){var t={};for(var i in e){var a=e[i],r=a["DATA-ID"].search("io.litix.data.");if(r!==-1){var n=a["DATA-ID"].replace("io.litix.data.","");t[n]=a.VALUE}}return t},Sh=ef,on=function(e){if(!e)return{};var t=Ds.navigationStart(),i=e.loading,a=i?i.start:e.trequest,r=i?i.first:e.tfirst,n=i?i.end:e.tload;return{bytesLoaded:e.total,requestStart:Math.round(t+a),responseStart:Math.round(t+r),responseEnd:Math.round(t+n)}},ja=function(e){if(!(!e||typeof e.getAllResponseHeaders!="function"))return rd(e.getAllResponseHeaders())},tf=function(e,t,i){var a=arguments.length>4?arguments[4]:void 0,r=e.log,n=e.utils.secondsToMs,s=function(_){var y=parseInt(a.version),T;return y===1&&_.programDateTime!==null&&(T=_.programDateTime),y===0&&_.pdt!==null&&(T=_.pdt),T};if(!Ds.exists()){r.warn("performance timing not supported. Not tracking HLS.js.");return}var o=function(_,y){return e.emit(t,_,y)},l=function(_,y){var T=y.levels,f=y.audioTracks,S=y.url,D=y.stats,O=y.networkDetails,H=y.sessionData,Y={},Q={};T.forEach(function(ce,xe){Y[xe]={width:ce.width,height:ce.height,bitrate:ce.bitrate,attrs:ce.attrs}}),f.forEach(function(ce,xe){Q[xe]={name:ce.name,language:ce.lang,bitrate:ce.bitrate}});var W=on(D),P=W.bytesLoaded,De=W.requestStart,He=W.responseStart,Be=W.responseEnd;o("requestcompleted",ad(Ls({},Sh(H)),{request_event_type:_,request_bytes_loaded:P,request_start:De,request_response_start:He,request_response_end:Be,request_type:"manifest",request_hostname:mt(S),request_response_headers:ja(O),request_rendition_lists:{media:Y,audio:Q,video:{}}}))};i.on(a.Events.MANIFEST_LOADED,l);var d=function(_,y){var T=y.details,f=y.level,S=y.networkDetails,D=y.stats,O=on(D),H=O.bytesLoaded,Y=O.requestStart,Q=O.responseStart,W=O.responseEnd,P=T.fragments[T.fragments.length-1],De=s(P)+n(P.duration);o("requestcompleted",{request_event_type:_,request_bytes_loaded:H,request_start:Y,request_response_start:Q,request_response_end:W,request_current_level:f,request_type:"manifest",request_hostname:mt(T.url),request_response_headers:ja(S),video_holdback:T.holdBack&&n(T.holdBack),video_part_holdback:T.partHoldBack&&n(T.partHoldBack),video_part_target_duration:T.partTarget&&n(T.partTarget),video_target_duration:T.targetduration&&n(T.targetduration),video_source_is_live:T.live,player_manifest_newest_program_time:isNaN(De)?void 0:De})};i.on(a.Events.LEVEL_LOADED,d);var m=function(_,y){var T=y.details,f=y.networkDetails,S=y.stats,D=on(S),O=D.bytesLoaded,H=D.requestStart,Y=D.responseStart,Q=D.responseEnd;o("requestcompleted",{request_event_type:_,request_bytes_loaded:O,request_start:H,request_response_start:Y,request_response_end:Q,request_type:"manifest",request_hostname:mt(T.url),request_response_headers:ja(f)})};i.on(a.Events.AUDIO_TRACK_LOADED,m);var p=function(_,y){var T=y.stats,f=y.networkDetails,S=y.frag;T=T||S.stats;var D=on(T),O=D.bytesLoaded,H=D.requestStart,Y=D.responseStart,Q=D.responseEnd,W=f?ja(f):void 0,P={request_event_type:_,request_bytes_loaded:O,request_start:H,request_response_start:Y,request_response_end:Q,request_hostname:f?mt(f.responseURL):void 0,request_id:W?Ms(W):void 0,request_response_headers:W,request_media_duration:S.duration,request_url:f?.responseURL};S.type==="main"?(P.request_type="media",P.request_current_level=S.level,P.request_video_width=(i.levels[S.level]||{}).width,P.request_video_height=(i.levels[S.level]||{}).height,P.request_labeled_bitrate=(i.levels[S.level]||{}).bitrate):P.request_type=S.type,o("requestcompleted",P)};i.on(a.Events.FRAG_LOADED,p);var h=function(_,y){var T=y.frag,f=T.start,S=s(T),D={currentFragmentPDT:S,currentFragmentStart:n(f)};o("fragmentchange",D)};i.on(a.Events.FRAG_CHANGED,h);var c=function(_,y){var T=y.type,f=y.details,S=y.response,D=y.fatal,O=y.frag,H=y.networkDetails,Y=O?.url||y.url||"",Q=H?ja(H):void 0;if((f===a.ErrorDetails.MANIFEST_LOAD_ERROR||f===a.ErrorDetails.MANIFEST_LOAD_TIMEOUT||f===a.ErrorDetails.FRAG_LOAD_ERROR||f===a.ErrorDetails.FRAG_LOAD_TIMEOUT||f===a.ErrorDetails.LEVEL_LOAD_ERROR||f===a.ErrorDetails.LEVEL_LOAD_TIMEOUT||f===a.ErrorDetails.AUDIO_TRACK_LOAD_ERROR||f===a.ErrorDetails.AUDIO_TRACK_LOAD_TIMEOUT||f===a.ErrorDetails.SUBTITLE_LOAD_ERROR||f===a.ErrorDetails.SUBTITLE_LOAD_TIMEOUT||f===a.ErrorDetails.KEY_LOAD_ERROR||f===a.ErrorDetails.KEY_LOAD_TIMEOUT)&&o("requestfailed",{request_error:f,request_url:Y,request_hostname:mt(Y),request_id:Q?Ms(Q):void 0,request_type:f===a.ErrorDetails.FRAG_LOAD_ERROR||f===a.ErrorDetails.FRAG_LOAD_TIMEOUT?"media":f===a.ErrorDetails.AUDIO_TRACK_LOAD_ERROR||f===a.ErrorDetails.AUDIO_TRACK_LOAD_TIMEOUT?"audio":f===a.ErrorDetails.SUBTITLE_LOAD_ERROR||f===a.ErrorDetails.SUBTITLE_LOAD_TIMEOUT?"subtitle":f===a.ErrorDetails.KEY_LOAD_ERROR||f===a.ErrorDetails.KEY_LOAD_TIMEOUT?"encryption":"manifest",request_error_code:S?.code,request_error_text:S?.text}),D){var W,P="".concat(Y?"url: ".concat(Y,`
`):"")+"".concat(S&&(S.code||S.text)?"response: ".concat(S.code,", ").concat(S.text,`
`):"")+"".concat(y.reason?"failure reason: ".concat(y.reason,`
`):"")+"".concat(y.level?"level: ".concat(y.level,`
`):"")+"".concat(y.parent?"parent stream controller: ".concat(y.parent,`
`):"")+"".concat(y.buffer?"buffer length: ".concat(y.buffer,`
`):"")+"".concat(y.error?"error: ".concat(y.error,`
`):"")+"".concat(y.event?"event: ".concat(y.event,`
`):"")+"".concat(y.err?"error message: ".concat((W=y.err)===null||W===void 0?void 0:W.message,`
`):"");o("error",{player_error_code:T,player_error_message:f,player_error_context:P})}};i.on(a.Events.ERROR,c);var v=function(_,y){var T=y.frag,f=T&&T._url||"";o("requestcanceled",{request_event_type:_,request_url:f,request_type:"media",request_hostname:mt(f)})};i.on(a.Events.FRAG_LOAD_EMERGENCY_ABORTED,v);var g=function(_,y){var T=y.level,f=i.levels[T];if(f&&f.attrs&&f.attrs.BANDWIDTH){var S=f.attrs.BANDWIDTH,D,O=parseFloat(f.attrs["FRAME-RATE"]);isNaN(O)||(D=O),S?o("renditionchange",{video_source_fps:D,video_source_bitrate:S,video_source_width:f.width,video_source_height:f.height,video_source_rendition_name:f.name,video_source_codec:f?.videoCodec}):r.warn("missing BANDWIDTH from HLS manifest parsed by HLS.js")}};i.on(a.Events.LEVEL_SWITCHED,g),i._stopMuxMonitor=function(){i.off(a.Events.MANIFEST_LOADED,l),i.off(a.Events.LEVEL_LOADED,d),i.off(a.Events.AUDIO_TRACK_LOADED,m),i.off(a.Events.FRAG_LOADED,p),i.off(a.Events.FRAG_CHANGED,h),i.off(a.Events.ERROR,c),i.off(a.Events.FRAG_LOAD_EMERGENCY_ABORTED,v),i.off(a.Events.LEVEL_SWITCHED,g),i.off(a.Events.DESTROYING,i._stopMuxMonitor),delete i._stopMuxMonitor},i.on(a.Events.DESTROYING,i._stopMuxMonitor)},af=function(e){e&&typeof e._stopMuxMonitor=="function"&&e._stopMuxMonitor()},$u=function(e,t){if(!e||!e.requestEndDate)return{};var i=mt(e.url),a=e.url,r=e.bytesLoaded,n=new Date(e.requestStartDate).getTime(),s=new Date(e.firstByteDate).getTime(),o=new Date(e.requestEndDate).getTime(),l=isNaN(e.duration)?0:e.duration,d=typeof t.getMetricsFor=="function"?t.getMetricsFor(e.mediaType).HttpList:t.getDashMetrics().getHttpRequests(e.mediaType),m;d.length>0&&(m=rd(d[d.length-1]._responseHeaders||""));var p=m?Ms(m):void 0;return{requestStart:n,requestResponseStart:s,requestResponseEnd:o,requestBytesLoaded:r,requestResponseHeaders:m,requestMediaDuration:l,requestHostname:i,requestUrl:a,requestId:p}},rf=function(e,t){var i=t.getQualityFor(e),a=t.getCurrentTrackFor(e).bitrateList;return a?{currentLevel:i,renditionWidth:a[i].width||null,renditionHeight:a[i].height||null,renditionBitrate:a[i].bandwidth}:{}},nf=function(e){var t;return(t=e.match(/.*codecs\*?="(.*)"/))===null||t===void 0?void 0:t[1]},sf=function(e){try{var t,i,a=(i=e.getVersion)===null||i===void 0||(t=i.call(e))===null||t===void 0?void 0:t.split(".").map(function(r){return parseInt(r)})[0];return a}catch{return!1}},of=function(e,t,i){var a=e.log;if(!i||!i.on){a.warn("Invalid dash.js player reference. Monitoring blocked.");return}var r=sf(i),n=function(T,f){return e.emit(t,T,f)},s=function(T){var f=T.type,S=T.data,D=(S||{}).url;n("requestcompleted",{request_event_type:f,request_start:0,request_response_start:0,request_response_end:0,request_bytes_loaded:-1,request_type:"manifest",request_hostname:mt(D),request_url:D})};i.on("manifestLoaded",s);var o={},l=function(T){if(typeof T.getRequests!="function")return null;var f=T.getRequests({state:"executed"});return f.length===0?null:f[f.length-1]},d=function(T){var f=T.type,S=T.fragmentModel,D=T.chunk,O=l(S);m({type:f,request:O,chunk:D})},m=function(T){var f=T.type,S=T.chunk,D=T.request,O=(S||{}).mediaInfo,H=O||{},Y=H.type,Q=H.bitrateList;Q=Q||[];var W={};Q.forEach(function(Et,Ne){W[Ne]={},W[Ne].width=Et.width,W[Ne].height=Et.height,W[Ne].bitrate=Et.bandwidth,W[Ne].attrs={}}),Y==="video"?o.video=W:Y==="audio"?o.audio=W:o.media=W;var P=$u(D,i),De=P.requestStart,He=P.requestResponseStart,Be=P.requestResponseEnd,ce=P.requestResponseHeaders,xe=P.requestMediaDuration,ft=P.requestHostname,Oe=P.requestUrl,rt=P.requestId;n("requestcompleted",{request_event_type:f,request_start:De,request_response_start:He,request_response_end:Be,request_bytes_loaded:-1,request_type:Y+"_init",request_response_headers:ce,request_hostname:ft,request_id:rt,request_url:Oe,request_media_duration:xe,request_rendition_lists:o})};r>=4?i.on("initFragmentLoaded",m):i.on("initFragmentLoaded",d);var p=function(T){var f=T.type,S=T.fragmentModel,D=T.chunk,O=l(S);h({type:f,request:O,chunk:D})},h=function(T){var f=T.type,S=T.chunk,D=T.request,O=S||{},H=O.mediaInfo,Y=O.start,Q=H||{},W=Q.type,P=$u(D,i),De=P.requestStart,He=P.requestResponseStart,Be=P.requestResponseEnd,ce=P.requestBytesLoaded,xe=P.requestResponseHeaders,ft=P.requestMediaDuration,Oe=P.requestHostname,rt=P.requestUrl,Et=P.requestId,Ne=rf(W,i),We=Ne.currentLevel,Ze=Ne.renditionWidth,zi=Ne.renditionHeight,nn=Ne.renditionBitrate;n("requestcompleted",{request_event_type:f,request_start:De,request_response_start:He,request_response_end:Be,request_bytes_loaded:ce,request_type:W,request_response_headers:xe,request_hostname:Oe,request_id:Et,request_url:rt,request_media_start_time:Y,request_media_duration:ft,request_current_level:We,request_labeled_bitrate:nn,request_video_width:Ze,request_video_height:zi})};r>=4?i.on("mediaFragmentLoaded",h):i.on("mediaFragmentLoaded",p);var c={video:void 0,audio:void 0,totalBitrate:void 0},v=function(){if(c.video&&typeof c.video.bitrate=="number"){if(!(c.video.width&&c.video.height)){a.warn("have bitrate info for video but missing width/height");return}var T=c.video.bitrate;if(c.audio&&typeof c.audio.bitrate=="number"&&(T+=c.audio.bitrate),T!==c.totalBitrate)return c.totalBitrate=T,{video_source_bitrate:T,video_source_height:c.video.height,video_source_width:c.video.width,video_source_codec:nf(c.video.codec)}}},g=function(T,f,S){if(typeof T.newQuality!="number"){a.warn("missing evt.newQuality in qualityChangeRendered event",T);return}var D=T.mediaType;if(D==="audio"||D==="video"){var O=i.getBitrateInfoListFor(D).find(function(Y){var Q=Y.qualityIndex;return Q===T.newQuality});if(!(O&&typeof O.bitrate=="number")){a.warn("missing bitrate info for ".concat(D));return}c[D]=ad(Ls({},O),{codec:i.getCurrentTrackFor(D).codec});var H=v();H&&n("renditionchange",H)}};i.on("qualityChangeRendered",g);var _=function(T){var f=T.request,S=T.mediaType;f=f||{},n("requestcanceled",{request_event_type:f.type+"_"+f.action,request_url:f.url,request_type:S,request_hostname:mt(f.url)})};i.on("fragmentLoadingAbandoned",_);var y=function(T){var f=T.error,S,D,O=(f==null||(S=f.data)===null||S===void 0?void 0:S.request)||{},H=(f==null||(D=f.data)===null||D===void 0?void 0:D.response)||{};f?.code===27&&n("requestfailed",{request_error:O.type+"_"+O.action,request_url:O.url,request_hostname:mt(O.url),request_type:O.mediaType,request_error_code:H.status,request_error_text:H.statusText});var Y="".concat(O!=null&&O.url?"url: ".concat(O.url,`
`):"")+"".concat(H!=null&&H.status||H!=null&&H.statusText?"response: ".concat(H?.status,", ").concat(H?.statusText,`
`):"");n("error",{player_error_code:f?.code,player_error_message:f?.message,player_error_context:Y})};i.on("error",y),i._stopMuxMonitor=function(){i.off("manifestLoaded",s),i.off("initFragmentLoaded",m),i.off("mediaFragmentLoaded",h),i.off("qualityChangeRendered",g),i.off("error",y),i.off("fragmentLoadingAbandoned",_),delete i._stopMuxMonitor}},lf=function(e){e&&typeof e._stopMuxMonitor=="function"&&e._stopMuxMonitor()},Uu=0,df=(function(){function e(){we(this,e),k(this,"_listeners",void 0)}return Bt(e,[{key:"on",value:function(t,i,a){return i._eventEmitterGuid=i._eventEmitterGuid||++Uu,this._listeners=this._listeners||{},this._listeners[t]=this._listeners[t]||[],a&&(i=i.bind(a)),this._listeners[t].push(i),i}},{key:"off",value:function(t,i){var a=this._listeners&&this._listeners[t];a&&a.forEach(function(r,n){r._eventEmitterGuid===i._eventEmitterGuid&&a.splice(n,1)})}},{key:"one",value:function(t,i,a){var r=this;i._eventEmitterGuid=i._eventEmitterGuid||++Uu;var n=function(){r.off(t,n),i.apply(a||this,arguments)};n._eventEmitterGuid=i._eventEmitterGuid,this.on(t,n)}},{key:"emit",value:function(t,i){var a=this;if(this._listeners){i=i||{};var r=this._listeners["before*"]||[],n=this._listeners[t]||[],s=this._listeners["after"+t]||[],o=function(l,d){l=l.slice(),l.forEach(function(m){m.call(a,{type:t},d)})};o(r,i),o(n,i),o(s,i)}}}]),e})(),uf=df,To=je(vt()),cf=(function(){function e(t){var i=this;we(this,e),k(this,"_playbackHeartbeatInterval",void 0),k(this,"_playheadShouldBeProgressing",void 0),k(this,"pm",void 0),this.pm=t,this._playbackHeartbeatInterval=null,this._playheadShouldBeProgressing=!1,t.on("playing",function(){i._playheadShouldBeProgressing=!0}),t.on("play",this._startPlaybackHeartbeatInterval.bind(this)),t.on("playing",this._startPlaybackHeartbeatInterval.bind(this)),t.on("adbreakstart",this._startPlaybackHeartbeatInterval.bind(this)),t.on("adplay",this._startPlaybackHeartbeatInterval.bind(this)),t.on("adplaying",this._startPlaybackHeartbeatInterval.bind(this)),t.on("devicewake",this._startPlaybackHeartbeatInterval.bind(this)),t.on("viewstart",this._startPlaybackHeartbeatInterval.bind(this)),t.on("rebufferstart",this._startPlaybackHeartbeatInterval.bind(this)),t.on("pause",this._stopPlaybackHeartbeatInterval.bind(this)),t.on("ended",this._stopPlaybackHeartbeatInterval.bind(this)),t.on("viewend",this._stopPlaybackHeartbeatInterval.bind(this)),t.on("error",this._stopPlaybackHeartbeatInterval.bind(this)),t.on("aderror",this._stopPlaybackHeartbeatInterval.bind(this)),t.on("adpause",this._stopPlaybackHeartbeatInterval.bind(this)),t.on("adended",this._stopPlaybackHeartbeatInterval.bind(this)),t.on("adbreakend",this._stopPlaybackHeartbeatInterval.bind(this)),t.on("seeked",function(){t.data.player_is_paused?i._stopPlaybackHeartbeatInterval():i._startPlaybackHeartbeatInterval()}),t.on("timeupdate",function(){i._playbackHeartbeatInterval!==null&&t.emit("playbackheartbeat")}),t.on("devicesleep",function(a,r){i._playbackHeartbeatInterval!==null&&(To.default.clearInterval(i._playbackHeartbeatInterval),t.emit("playbackheartbeatend",{viewer_time:r.viewer_time}),i._playbackHeartbeatInterval=null)})}return Bt(e,[{key:"_startPlaybackHeartbeatInterval",value:function(){var t=this;this._playbackHeartbeatInterval===null&&(this.pm.emit("playbackheartbeat"),this._playbackHeartbeatInterval=To.default.setInterval(function(){t.pm.emit("playbackheartbeat")},this.pm.playbackHeartbeatTime))}},{key:"_stopPlaybackHeartbeatInterval",value:function(){this._playheadShouldBeProgressing=!1,this._playbackHeartbeatInterval!==null&&(To.default.clearInterval(this._playbackHeartbeatInterval),this.pm.emit("playbackheartbeatend"),this._playbackHeartbeatInterval=null)}}]),e})(),hf=cf,mf=function e(t){var i=this;we(this,e),k(this,"viewErrored",void 0),t.on("viewinit",function(){i.viewErrored=!1}),t.on("error",function(a,r){try{var n=t.errorTranslator({player_error_code:r.player_error_code,player_error_message:r.player_error_message,player_error_context:r.player_error_context,player_error_severity:r.player_error_severity,player_error_business_exception:r.player_error_business_exception});n&&(t.data.player_error_code=n.player_error_code||r.player_error_code,t.data.player_error_message=n.player_error_message||r.player_error_message,t.data.player_error_context=n.player_error_context||r.player_error_context,t.data.player_error_severity=n.player_error_severity||r.player_error_severity,t.data.player_error_business_exception=n.player_error_business_exception||r.player_error_business_exception,i.viewErrored=!0)}catch(s){t.mux.log.warn("Exception in error translator callback.",s),i.viewErrored=!0}}),t.on("aftererror",function(){var a,r,n,s,o;(a=t.data)===null||a===void 0||delete a.player_error_code,(r=t.data)===null||r===void 0||delete r.player_error_message,(n=t.data)===null||n===void 0||delete n.player_error_context,(s=t.data)===null||s===void 0||delete s.player_error_severity,(o=t.data)===null||o===void 0||delete o.player_error_business_exception})},pf=mf,vf=(function(){function e(t){we(this,e),k(this,"_watchTimeTrackerLastCheckedTime",void 0),k(this,"pm",void 0),this.pm=t,this._watchTimeTrackerLastCheckedTime=null,t.on("playbackheartbeat",this._updateWatchTime.bind(this)),t.on("playbackheartbeatend",this._clearWatchTimeState.bind(this))}return Bt(e,[{key:"_updateWatchTime",value:function(t,i){var a=i.viewer_time;this._watchTimeTrackerLastCheckedTime===null&&(this._watchTimeTrackerLastCheckedTime=a),Ee(this.pm.data,"view_watch_time",a-this._watchTimeTrackerLastCheckedTime),this._watchTimeTrackerLastCheckedTime=a}},{key:"_clearWatchTimeState",value:function(t,i){this._updateWatchTime(t,i),this._watchTimeTrackerLastCheckedTime=null}}]),e})(),ff=vf,Ef=(function(){function e(t){var i=this;we(this,e),k(this,"_playbackTimeTrackerLastPlayheadPosition",void 0),k(this,"_lastTime",void 0),k(this,"_isAdPlaying",void 0),k(this,"_callbackUpdatePlaybackTime",void 0),k(this,"pm",void 0),this.pm=t,this._playbackTimeTrackerLastPlayheadPosition=-1,this._lastTime=_e.now(),this._isAdPlaying=!1,this._callbackUpdatePlaybackTime=null;var a=this._startPlaybackTimeTracking.bind(this);t.on("playing",a),t.on("adplaying",a),t.on("seeked",a);var r=this._stopPlaybackTimeTracking.bind(this);t.on("playbackheartbeatend",r),t.on("seeking",r),t.on("adplaying",function(){i._isAdPlaying=!0}),t.on("adended",function(){i._isAdPlaying=!1}),t.on("adpause",function(){i._isAdPlaying=!1}),t.on("adbreakstart",function(){i._isAdPlaying=!1}),t.on("adbreakend",function(){i._isAdPlaying=!1}),t.on("adplay",function(){i._isAdPlaying=!1}),t.on("viewinit",function(){i._playbackTimeTrackerLastPlayheadPosition=-1,i._lastTime=_e.now(),i._isAdPlaying=!1,i._callbackUpdatePlaybackTime=null})}return Bt(e,[{key:"_startPlaybackTimeTracking",value:function(){this._callbackUpdatePlaybackTime===null&&(this._callbackUpdatePlaybackTime=this._updatePlaybackTime.bind(this),this._playbackTimeTrackerLastPlayheadPosition=this.pm.data.player_playhead_time,this.pm.on("playbackheartbeat",this._callbackUpdatePlaybackTime))}},{key:"_stopPlaybackTimeTracking",value:function(){this._callbackUpdatePlaybackTime&&(this._updatePlaybackTime(),this.pm.off("playbackheartbeat",this._callbackUpdatePlaybackTime),this._callbackUpdatePlaybackTime=null,this._playbackTimeTrackerLastPlayheadPosition=-1)}},{key:"_updatePlaybackTime",value:function(){var t=this.pm.data.player_playhead_time,i=_e.now(),a=-1;this._playbackTimeTrackerLastPlayheadPosition>=0&&t>this._playbackTimeTrackerLastPlayheadPosition?a=t-this._playbackTimeTrackerLastPlayheadPosition:this._isAdPlaying&&(a=i-this._lastTime),a>0&&a<=1e3&&Ee(this.pm.data,"view_content_playback_time",a),this._playbackTimeTrackerLastPlayheadPosition=t,this._lastTime=i}}]),e})(),_f=Ef,bf=(function(){function e(t){we(this,e),k(this,"pm",void 0),this.pm=t;var i=this._updatePlayheadTime.bind(this);t.on("playbackheartbeat",i),t.on("playbackheartbeatend",i),t.on("timeupdate",i),t.on("destroy",function(){t.off("timeupdate",i)})}return Bt(e,[{key:"_updateMaxPlayheadPosition",value:function(){this.pm.data.view_max_playhead_position=typeof this.pm.data.view_max_playhead_position>"u"?this.pm.data.player_playhead_time:Math.max(this.pm.data.view_max_playhead_position,this.pm.data.player_playhead_time)}},{key:"_updatePlayheadTime",value:function(t,i){var a=this,r=function(){a.pm.currentFragmentPDT&&a.pm.currentFragmentStart&&(a.pm.data.player_program_time=a.pm.currentFragmentPDT+a.pm.data.player_playhead_time-a.pm.currentFragmentStart)};if(i&&i.player_playhead_time)this.pm.data.player_playhead_time=i.player_playhead_time,r(),this._updateMaxPlayheadPosition();else if(this.pm.getPlayheadTime){var n=this.pm.getPlayheadTime();typeof n<"u"&&(this.pm.data.player_playhead_time=n,r(),this._updateMaxPlayheadPosition())}}}]),e})(),gf=bf,Hu=300*1e3,yf=function e(t){if(we(this,e),!t.disableRebufferTracking){var i,a=function(n,s){r(s),i=void 0},r=function(n){if(i){var s=n.viewer_time-i;Ee(t.data,"view_rebuffer_duration",s),i=n.viewer_time,t.data.view_rebuffer_duration>Hu&&(t.emit("viewend"),t.send("viewend"),t.mux.log.warn("Ending view after rebuffering for longer than ".concat(Hu,"ms, future events will be ignored unless a programchange or videochange occurs.")))}t.data.view_watch_time>=0&&t.data.view_rebuffer_count>0&&(t.data.view_rebuffer_frequency=t.data.view_rebuffer_count/t.data.view_watch_time,t.data.view_rebuffer_percentage=t.data.view_rebuffer_duration/t.data.view_watch_time)};t.on("playbackheartbeat",function(n,s){return r(s)}),t.on("rebufferstart",function(n,s){i||(Ee(t.data,"view_rebuffer_count",1),i=s.viewer_time,t.one("rebufferend",a))}),t.on("viewinit",function(){i=void 0,t.off("rebufferend",a)})}},Tf=yf,Af=(function(){function e(t){var i=this;we(this,e),k(this,"_lastCheckedTime",void 0),k(this,"_lastPlayheadTime",void 0),k(this,"_lastPlayheadTimeUpdatedTime",void 0),k(this,"_rebuffering",void 0),k(this,"pm",void 0),this.pm=t,!(t.disableRebufferTracking||t.disablePlayheadRebufferTracking)&&(this._lastCheckedTime=null,this._lastPlayheadTime=null,this._lastPlayheadTimeUpdatedTime=null,t.on("playbackheartbeat",this._checkIfRebuffering.bind(this)),t.on("playbackheartbeatend",this._cleanupRebufferTracker.bind(this)),t.on("seeking",function(){i._cleanupRebufferTracker(null,{viewer_time:_e.now()})}))}return Bt(e,[{key:"_checkIfRebuffering",value:function(t,i){if(this.pm.seekingTracker.isSeeking||this.pm.adTracker.isAdBreak||!this.pm.playbackHeartbeat._playheadShouldBeProgressing){this._cleanupRebufferTracker(t,i);return}if(this._lastCheckedTime===null){this._prepareRebufferTrackerState(i.viewer_time);return}if(this._lastPlayheadTime!==this.pm.data.player_playhead_time){this._cleanupRebufferTracker(t,i,!0);return}var a=i.viewer_time-this._lastPlayheadTimeUpdatedTime;typeof this.pm.sustainedRebufferThreshold=="number"&&a>=this.pm.sustainedRebufferThreshold&&(this._rebuffering||(this._rebuffering=!0,this.pm.emit("rebufferstart",{viewer_time:this._lastPlayheadTimeUpdatedTime}))),this._lastCheckedTime=i.viewer_time}},{key:"_clearRebufferTrackerState",value:function(){this._lastCheckedTime=null,this._lastPlayheadTime=null,this._lastPlayheadTimeUpdatedTime=null}},{key:"_prepareRebufferTrackerState",value:function(t){this._lastCheckedTime=t,this._lastPlayheadTime=this.pm.data.player_playhead_time,this._lastPlayheadTimeUpdatedTime=t}},{key:"_cleanupRebufferTracker",value:function(t,i){var a=arguments.length>2&&arguments[2]!==void 0?arguments[2]:!1;if(this._rebuffering)this._rebuffering=!1,this.pm.emit("rebufferend",{viewer_time:i.viewer_time});else{if(this._lastCheckedTime===null)return;var r=this.pm.data.player_playhead_time-this._lastPlayheadTime,n=i.viewer_time-this._lastPlayheadTimeUpdatedTime;typeof this.pm.minimumRebufferDuration=="number"&&r>0&&n-r>this.pm.minimumRebufferDuration&&(this._lastCheckedTime=null,this.pm.emit("rebufferstart",{viewer_time:this._lastPlayheadTimeUpdatedTime}),this.pm.emit("rebufferend",{viewer_time:this._lastPlayheadTimeUpdatedTime+n-r}))}a?this._prepareRebufferTrackerState(i.viewer_time):this._clearRebufferTrackerState()}}]),e})(),kf=Af,Sf=(function(){function e(t){var i=this;we(this,e),k(this,"NAVIGATION_START",void 0),k(this,"pm",void 0),this.pm=t,t.on("viewinit",function(){var a=t.data,r=a.view_id;if(!a.view_program_changed){var n=function(s,o){var l=o.viewer_time;(s.type==="playing"&&typeof t.data.view_time_to_first_frame>"u"||s.type==="adplaying"&&(typeof t.data.view_time_to_first_frame>"u"||i._inPrerollPosition()))&&i.calculateTimeToFirstFrame(l||_e.now(),r)};t.one("playing",n),t.one("adplaying",n),t.one("viewend",function(){t.off("playing",n),t.off("adplaying",n)})}})}return Bt(e,[{key:"_inPrerollPosition",value:function(){return typeof this.pm.data.view_content_playback_time>"u"||this.pm.data.view_content_playback_time<=1e3}},{key:"calculateTimeToFirstFrame",value:function(t,i){i===this.pm.data.view_id&&(this.pm.watchTimeTracker._updateWatchTime(null,{viewer_time:t}),this.pm.data.view_time_to_first_frame=this.pm.data.view_watch_time,(this.pm.data.player_autoplay_on||this.pm.data.video_is_autoplay)&&this.NAVIGATION_START&&(this.pm.data.view_aggregate_startup_time=this.pm.data.view_start+this.pm.data.view_watch_time-this.NAVIGATION_START))}}]),e})(),wf=Sf,If=function e(t){var i=this;we(this,e),k(this,"_lastPlayerHeight",void 0),k(this,"_lastPlayerWidth",void 0),k(this,"_lastPlayheadPosition",void 0),k(this,"_lastSourceHeight",void 0),k(this,"_lastSourceWidth",void 0),t.on("viewinit",function(){i._lastPlayheadPosition=-1});var a=["pause","rebufferstart","seeking","error","adbreakstart","hb","renditionchange","orientationchange","viewend"],r=["playing","hb","renditionchange","orientationchange"];a.forEach(function(n){t.on(n,function(){if(i._lastPlayheadPosition>=0&&t.data.player_playhead_time>=0&&i._lastPlayerWidth>=0&&i._lastSourceWidth>0&&i._lastPlayerHeight>=0&&i._lastSourceHeight>0){var s=t.data.player_playhead_time-i._lastPlayheadPosition;if(s<0){i._lastPlayheadPosition=-1;return}var o=Math.min(i._lastPlayerWidth/i._lastSourceWidth,i._lastPlayerHeight/i._lastSourceHeight),l=Math.max(0,o-1),d=Math.max(0,1-o);t.data.view_max_upscale_percentage=Math.max(t.data.view_max_upscale_percentage||0,l),t.data.view_max_downscale_percentage=Math.max(t.data.view_max_downscale_percentage||0,d),Ee(t.data,"view_total_content_playback_time",s),Ee(t.data,"view_total_upscaling",l*s),Ee(t.data,"view_total_downscaling",d*s)}i._lastPlayheadPosition=-1})}),r.forEach(function(n){t.on(n,function(){i._lastPlayheadPosition=t.data.player_playhead_time,i._lastPlayerWidth=t.data.player_width,i._lastPlayerHeight=t.data.player_height,i._lastSourceWidth=t.data.video_source_width,i._lastSourceHeight=t.data.video_source_height})})},Rf=If,Cf=2e3,Df=function e(t){var i=this;we(this,e),k(this,"isSeeking",void 0),this.isSeeking=!1;var a=-1,r=function(){var n=_e.now(),s=(t.data.viewer_time||n)-(a||n);Ee(t.data,"view_seek_duration",s),t.data.view_max_seek_time=Math.max(t.data.view_max_seek_time||0,s),i.isSeeking=!1,a=-1};t.on("seeking",function(n,s){if(Object.assign(t.data,s),i.isSeeking&&s.viewer_time-a<=Cf){a=s.viewer_time;return}i.isSeeking&&r(),i.isSeeking=!0,a=s.viewer_time,Ee(t.data,"view_seek_count",1),t.send("seeking")}),t.on("seeked",function(){r()}),t.on("viewend",function(){i.isSeeking&&(r(),t.send("seeked")),i.isSeeking=!1,a=-1})},Lf=Df,Bu=function(e,t){e.push(t),e.sort(function(i,a){return i.viewer_time-a.viewer_time})},Mf=["adbreakstart","adrequest","adresponse","adplay","adplaying","adpause","adended","adbreakend","aderror","adclicked","adskipped"],xf=(function(){function e(t){var i=this;we(this,e),k(this,"_adHasPlayed",void 0),k(this,"_adRequests",void 0),k(this,"_adResponses",void 0),k(this,"_currentAdRequestNumber",void 0),k(this,"_currentAdResponseNumber",void 0),k(this,"_prerollPlayTime",void 0),k(this,"_wouldBeNewAdPlay",void 0),k(this,"isAdBreak",void 0),k(this,"pm",void 0),this.pm=t,t.on("viewinit",function(){i.isAdBreak=!1,i._currentAdRequestNumber=0,i._currentAdResponseNumber=0,i._adRequests=[],i._adResponses=[],i._adHasPlayed=!1,i._wouldBeNewAdPlay=!0,i._prerollPlayTime=void 0}),Mf.forEach(function(r){return t.on(r,i._updateAdData.bind(i))});var a=function(){i.isAdBreak=!1};t.on("adbreakstart",function(){i.isAdBreak=!0}),t.on("play",a),t.on("playing",a),t.on("viewend",a),t.on("adrequest",function(r,n){n=Object.assign({ad_request_id:"generatedAdRequestId"+i._currentAdRequestNumber++},n),Bu(i._adRequests,n),Ee(t.data,"view_ad_request_count"),i.inPrerollPosition()&&(t.data.view_preroll_requested=!0,i._adHasPlayed||Ee(t.data,"view_preroll_request_count"))}),t.on("adresponse",function(r,n){n=Object.assign({ad_request_id:"generatedAdRequestId"+i._currentAdResponseNumber++},n),Bu(i._adResponses,n);var s=i.findAdRequest(n.ad_request_id);s&&Ee(t.data,"view_ad_request_time",Math.max(0,n.viewer_time-s.viewer_time))}),t.on("adplay",function(r,n){i._adHasPlayed=!0,i._wouldBeNewAdPlay&&(i._wouldBeNewAdPlay=!1,Ee(t.data,"view_ad_played_count")),i.inPrerollPosition()&&!t.data.view_preroll_played&&(t.data.view_preroll_played=!0,i._adRequests.length>0&&(t.data.view_preroll_request_time=Math.max(0,n.viewer_time-i._adRequests[0].viewer_time)),t.data.view_start&&(t.data.view_startup_preroll_request_time=Math.max(0,n.viewer_time-t.data.view_start)),i._prerollPlayTime=n.viewer_time)}),t.on("adplaying",function(r,n){i.inPrerollPosition()&&typeof t.data.view_preroll_load_time>"u"&&typeof i._prerollPlayTime<"u"&&(t.data.view_preroll_load_time=n.viewer_time-i._prerollPlayTime,t.data.view_startup_preroll_load_time=n.viewer_time-i._prerollPlayTime)}),t.on("adclicked",function(r,n){i._wouldBeNewAdPlay||Ee(t.data,"view_ad_clicked_count")}),t.on("adskipped",function(r,n){i._wouldBeNewAdPlay||Ee(t.data,"view_ad_skipped_count")}),t.on("adended",function(){i._wouldBeNewAdPlay=!0}),t.on("aderror",function(){i._wouldBeNewAdPlay=!0})}return Bt(e,[{key:"inPrerollPosition",value:function(){return typeof this.pm.data.view_content_playback_time>"u"||this.pm.data.view_content_playback_time<=1e3}},{key:"findAdRequest",value:function(t){for(var i=0;i<this._adRequests.length;i++)if(this._adRequests[i].ad_request_id===t)return this._adRequests[i]}},{key:"_updateAdData",value:function(t,i){if(this.inPrerollPosition()){if(!this.pm.data.view_preroll_ad_tag_hostname&&i.ad_tag_url){var a=Xt(Wr(i.ad_tag_url),2),r=a[0],n=a[1];this.pm.data.view_preroll_ad_tag_domain=n,this.pm.data.view_preroll_ad_tag_hostname=r}if(!this.pm.data.view_preroll_ad_asset_hostname&&i.ad_asset_url){var s=Xt(Wr(i.ad_asset_url),2),o=s[0],l=s[1];this.pm.data.view_preroll_ad_asset_domain=l,this.pm.data.view_preroll_ad_asset_hostname=o}}this.pm.data.ad_asset_url=i?.ad_asset_url,this.pm.data.ad_tag_url=i?.ad_tag_url,this.pm.data.ad_creative_id=i?.ad_creative_id,this.pm.data.ad_id=i?.ad_id,this.pm.data.ad_universal_id=i?.ad_universal_id}}]),e})(),Of=xf,Wu=je(vt()),Nf=function e(t){we(this,e);var i,a,r=function(){t.disableRebufferTracking||(Ee(t.data,"view_waiting_rebuffer_count",1),i=_e.now(),a=Wu.default.setInterval(function(){if(i){var d=_e.now();Ee(t.data,"view_waiting_rebuffer_duration",d-i),i=d}},250))},n=function(){t.disableRebufferTracking||i&&(Ee(t.data,"view_waiting_rebuffer_duration",_e.now()-i),i=!1,Wu.default.clearInterval(a))},s=!1,o=function(){s=!0},l=function(){s=!1,n()};t.on("waiting",function(){s&&r()}),t.on("playing",function(){n(),o()}),t.on("pause",l),t.on("seeking",l)},Pf=Nf,$f=function e(t){var i=this;we(this,e),k(this,"lastWallClockTime",void 0);var a=function(){i.lastWallClockTime=_e.now(),t.on("before*",r)},r=function(n){var s=_e.now(),o=i.lastWallClockTime;i.lastWallClockTime=s,s-o>3e4&&(t.emit("devicesleep",{viewer_time:o}),Object.assign(t.data,{viewer_time:o}),t.send("devicesleep"),t.emit("devicewake",{viewer_time:s}),Object.assign(t.data,{viewer_time:s}),t.send("devicewake"))};t.one("playbackheartbeat",a),t.on("playbackheartbeatend",function(){t.off("before*",r),t.one("playbackheartbeat",a)})},Uf=$f,Ao=je(vt()),wh=(function(e){return e()})(function(){var e=function(){for(var i=0,a={};i<arguments.length;i++){var r=arguments[i];for(var n in r)a[n]=r[n]}return a};function t(i){function a(r,n,s){var o;if(typeof document<"u"){if(arguments.length>1){if(s=e({path:"/"},a.defaults,s),typeof s.expires=="number"){var l=new Date;l.setMilliseconds(l.getMilliseconds()+s.expires*864e5),s.expires=l}try{o=JSON.stringify(n),/^[\{\[]/.test(o)&&(n=o)}catch{}return i.write?n=i.write(n,r):n=encodeURIComponent(String(n)).replace(/%(23|24|26|2B|3A|3C|3E|3D|2F|3F|40|5B|5D|5E|60|7B|7D|7C)/g,decodeURIComponent),r=encodeURIComponent(String(r)),r=r.replace(/%(23|24|26|2B|5E|60|7C)/g,decodeURIComponent),r=r.replace(/[\(\)]/g,escape),document.cookie=[r,"=",n,s.expires?"; expires="+s.expires.toUTCString():"",s.path?"; path="+s.path:"",s.domain?"; domain="+s.domain:"",s.secure?"; secure":""].join("")}r||(o={});for(var d=document.cookie?document.cookie.split("; "):[],m=/(%[0-9A-Z]{2})+/g,p=0;p<d.length;p++){var h=d[p].split("="),c=h.slice(1).join("=");c.charAt(0)==='"'&&(c=c.slice(1,-1));try{var v=h[0].replace(m,decodeURIComponent);if(c=i.read?i.read(c,v):i(c,v)||c.replace(m,decodeURIComponent),this.json)try{c=JSON.parse(c)}catch{}if(r===v){o=c;break}r||(o[v]=c)}catch{}}return o}}return a.set=a,a.get=function(r){return a.call(a,r)},a.getJSON=function(){return a.apply({json:!0},[].slice.call(arguments))},a.defaults={},a.remove=function(r,n){a(r,"",e(n,{expires:-1}))},a.withConverter=t,a}return t(function(){})}),Ih="muxData",Hf=function(e){return Object.entries(e).map(function(t){var i=Xt(t,2),a=i[0],r=i[1];return"".concat(a,"=").concat(r)}).join("&")},Bf=function(e){return e.split("&").reduce(function(t,i){var a=Xt(i.split("="),2),r=a[0],n=a[1],s=+n,o=n&&s==n?s:n;return t[r]=o,t},{})},Rh=function(){var e;try{e=Bf(wh.get(Ih)||"")}catch{e={}}return e},Ch=function(e){try{wh.set(Ih,Hf(e),{expires:365})}catch{}},Wf=function(){var e=Rh();return e.mux_viewer_id=e.mux_viewer_id||Br(),e.msn=e.msn||Math.random(),Ch(e),{mux_viewer_id:e.mux_viewer_id,mux_sample_number:e.msn}},Ff=function(){var e=Rh(),t=_e.now();return e.session_start&&(e.sst=e.session_start,delete e.session_start),e.session_id&&(e.sid=e.session_id,delete e.session_id),e.session_expires&&(e.sex=e.session_expires,delete e.session_expires),(!e.sex||e.sex<t)&&(e.sid=Br(),e.sst=t),e.sex=t+1500*1e3,Ch(e),{session_id:e.sid,session_start:e.sst,session_expires:e.sex}};function Kf(e,t){var i=t.beaconCollectionDomain,a=t.beaconDomain;if(i)return"https://"+i;e=e||"inferred";var r=a||"litix.io";return e.match(/^[a-z0-9]+$/)?"https://"+e+"."+r:"https://img.litix.io/a.gif"}var Vf=je(vt()),Dh=function(){var e;switch(Lh()){case"cellular":e="cellular";break;case"ethernet":e="wired";break;case"wifi":e="wifi";break;case void 0:break;default:e="other"}return e},Lh=function(){var e=Vf.default.navigator,t=e&&(e.connection||e.mozConnection||e.webkitConnection);return t&&t.type};Dh.getConnectionFromAPI=Lh;var qf=Dh,Yf={a:"env",b:"beacon",c:"custom",d:"ad",e:"event",f:"experiment",i:"internal",m:"mux",n:"response",p:"player",q:"request",r:"retry",s:"session",t:"timestamp",u:"viewer",v:"video",w:"page",x:"view",y:"sub"},Gf=Mh(Yf),jf={ad:"ad",af:"affiliate",ag:"aggregate",ap:"api",al:"application",ao:"audio",ar:"architecture",as:"asset",au:"autoplay",av:"average",bi:"bitrate",bn:"brand",br:"break",bw:"browser",by:"bytes",bz:"business",ca:"cached",cb:"cancel",cc:"codec",cd:"code",cg:"category",ch:"changed",ci:"client",ck:"clicked",cl:"canceled",cn:"config",co:"count",ce:"counter",cp:"complete",cq:"creator",cr:"creative",cs:"captions",ct:"content",cu:"current",cx:"connection",cz:"context",dg:"downscaling",dm:"domain",dn:"cdn",do:"downscale",dr:"drm",dp:"dropped",du:"duration",dv:"device",dy:"dynamic",eb:"enabled",ec:"encoding",ed:"edge",en:"end",eg:"engine",em:"embed",er:"error",ep:"experiments",es:"errorcode",et:"errortext",ee:"event",ev:"events",ex:"expires",ez:"exception",fa:"failed",fi:"first",fm:"family",ft:"format",fp:"fps",fq:"frequency",fr:"frame",fs:"fullscreen",ha:"has",hb:"holdback",he:"headers",ho:"host",hn:"hostname",ht:"height",id:"id",ii:"init",in:"instance",ip:"ip",is:"is",ke:"key",la:"language",lb:"labeled",le:"level",li:"live",ld:"loaded",lo:"load",ls:"lists",lt:"latency",ma:"max",md:"media",me:"message",mf:"manifest",mi:"mime",ml:"midroll",mm:"min",mn:"manufacturer",mo:"model",mx:"mux",ne:"newest",nm:"name",no:"number",on:"on",or:"origin",os:"os",pa:"paused",pb:"playback",pd:"producer",pe:"percentage",pf:"played",pg:"program",ph:"playhead",pi:"plugin",pl:"preroll",pn:"playing",po:"poster",pp:"pip",pr:"preload",ps:"position",pt:"part",py:"property",px:"pop",pz:"plan",ra:"rate",rd:"requested",re:"rebuffer",rf:"rendition",rg:"range",rm:"remote",ro:"ratio",rp:"response",rq:"request",rs:"requests",sa:"sample",sd:"skipped",se:"session",sh:"shift",sk:"seek",sm:"stream",so:"source",sq:"sequence",sr:"series",ss:"status",st:"start",su:"startup",sv:"server",sw:"software",sy:"severity",ta:"tag",tc:"tech",te:"text",tg:"target",th:"throughput",ti:"time",tl:"total",to:"to",tt:"title",ty:"type",ug:"upscaling",un:"universal",up:"upscale",ur:"url",us:"user",va:"variant",vd:"viewed",vi:"video",ve:"version",vw:"view",vr:"viewer",wd:"width",wa:"watch",wt:"waiting"},Fu=Mh(jf);function Mh(e){var t={};for(var i in e)e.hasOwnProperty(i)&&(t[e[i]]=i);return t}function Go(e){var t={},i={};return Object.keys(e).forEach(function(a){var r=!1;if(e.hasOwnProperty(a)&&e[a]!==void 0){var n=a.split("_"),s=n[0],o=Gf[s];o||(J.info("Data key word `"+n[0]+"` not expected in "+a),o=s+"_"),n.splice(1).forEach(function(l){l==="url"&&(r=!0),Fu[l]?o+=Fu[l]:Number.isInteger(Number(l))?o+=l:(J.info("Data key word `"+l+"` not expected in "+a),o+="_"+l+"_")}),r?i[o]=e[a]:t[o]=e[a]}}),Object.assign(t,i)}var Pi=je(vt()),Qf=je(yh()),Zf={maxBeaconSize:300,maxQueueLength:3600,baseTimeBetweenBeacons:1e4,maxPayloadKBSize:500},zf=56*1024,Xf=["hb","requestcompleted","requestfailed","requestcanceled"],Jf="https://img.litix.io",ei=function(e){var t=arguments.length>1&&arguments[1]!==void 0?arguments[1]:{};this._beaconUrl=e||Jf,this._eventQueue=[],this._postInFlight=!1,this._resendAfterPost=!1,this._failureCount=0,this._sendTimeout=!1,this._options=Object.assign({},Zf,t)};ei.prototype.queueEvent=function(e,t){var i=Object.assign({},t);return this._eventQueue.length<=this._options.maxQueueLength||e==="eventrateexceeded"?(this._eventQueue.push(i),this._sendTimeout||this._startBeaconSending(),this._eventQueue.length<=this._options.maxQueueLength):!1};ei.prototype.flushEvents=function(){var e=arguments.length>0&&arguments[0]!==void 0?arguments[0]:!1;if(e&&this._eventQueue.length===1){this._eventQueue.pop();return}this._eventQueue.length&&this._sendBeaconQueue(),this._startBeaconSending()};ei.prototype.destroy=function(){var e=arguments.length>0&&arguments[0]!==void 0?arguments[0]:!1;this.destroyed=!0,e?this._clearBeaconQueue():this.flushEvents(),Pi.default.clearTimeout(this._sendTimeout)};ei.prototype._clearBeaconQueue=function(){var e=this._eventQueue.length>this._options.maxBeaconSize?this._eventQueue.length-this._options.maxBeaconSize:0,t=this._eventQueue.slice(e);e>0&&Object.assign(t[t.length-1],Go({mux_view_message:"event queue truncated"}));var i=this._createPayload(t);xh(this._beaconUrl,i,!0,function(){})};ei.prototype._sendBeaconQueue=function(){var e=this;if(this._postInFlight){this._resendAfterPost=!0;return}var t=this._eventQueue.slice(0,this._options.maxBeaconSize);this._eventQueue=this._eventQueue.slice(this._options.maxBeaconSize),this._postInFlight=!0;var i=this._createPayload(t),a=_e.now();xh(this._beaconUrl,i,!1,function(r,n){n?(e._eventQueue=t.concat(e._eventQueue),e._failureCount+=1,J.info("Error sending beacon: "+n)):e._failureCount=0,e._roundTripTime=_e.now()-a,e._postInFlight=!1,e._resendAfterPost&&(e._resendAfterPost=!1,e._eventQueue.length>0&&e._sendBeaconQueue())})};ei.prototype._getNextBeaconTime=function(){if(!this._failureCount)return this._options.baseTimeBetweenBeacons;var e=Math.pow(2,this._failureCount-1);return e=e*Math.random(),(1+e)*this._options.baseTimeBetweenBeacons};ei.prototype._startBeaconSending=function(){var e=this;Pi.default.clearTimeout(this._sendTimeout),!this.destroyed&&(this._sendTimeout=Pi.default.setTimeout(function(){e._eventQueue.length&&e._sendBeaconQueue(),e._startBeaconSending()},this._getNextBeaconTime()))};ei.prototype._createPayload=function(e){var t=this,i={transmission_timestamp:Math.round(_e.now())};this._roundTripTime&&(i.rtt_ms=Math.round(this._roundTripTime));var a,r,n,s=function(){a=JSON.stringify({metadata:i,events:r||e}),n=a.length/1024},o=function(){return n<=t._options.maxPayloadKBSize};return s(),o()||(J.info("Payload size is too big ("+n+" kb). Removing unnecessary events."),r=e.filter(function(l){return Xf.indexOf(l.e)===-1}),s()),o()||(J.info("Payload size still too big ("+n+" kb). Cropping fields.."),r.forEach(function(l){for(var d in l){var m=l[d],p=50*1024;typeof m=="string"&&m.length>p&&(l[d]=m.substring(0,p))}}),s()),a};var eE=typeof Qf.default.exitPictureInPicture=="function"?function(e){return e.length<=zf}:function(e){return!1},xh=function(e,t,i,a){if(i&&navigator&&navigator.sendBeacon&&navigator.sendBeacon(e,t)){a();return}if(Pi.default.fetch){Pi.default.fetch(e,{method:"POST",body:t,headers:{"Content-Type":"text/plain"},keepalive:eE(t)}).then(function(n){return a(null,n.ok?null:"Error")}).catch(function(n){return a(null,n)});return}if(Pi.default.XMLHttpRequest){var r=new Pi.default.XMLHttpRequest;r.onreadystatechange=function(){if(r.readyState===4)return a(null,r.status!==200?"error":void 0)},r.open("POST",e),r.setRequestHeader("Content-Type","text/plain"),r.send(t);return}a()},tE=ei,iE=["env_key","view_id","view_sequence_number","player_sequence_number","beacon_domain","player_playhead_time","viewer_time","mux_api_version","event","video_id","player_instance_id","player_error_code","player_error_message","player_error_context","player_error_severity","player_error_business_exception"],aE=["adplay","adplaying","adpause","adfirstquartile","admidpoint","adthirdquartile","adended","adresponse","adrequest"],rE=["ad_id","ad_creative_id","ad_universal_id"],nE=["viewstart","error","ended","viewend"],sE=600*1e3,oE=(function(){function e(t,i){var a=arguments.length>2&&arguments[2]!==void 0?arguments[2]:{};we(this,e);var r,n,s,o,l,d,m,p,h,c,v,g;k(this,"mux",void 0),k(this,"envKey",void 0),k(this,"options",void 0),k(this,"eventQueue",void 0),k(this,"sampleRate",void 0),k(this,"disableCookies",void 0),k(this,"respectDoNotTrack",void 0),k(this,"previousBeaconData",void 0),k(this,"lastEventTime",void 0),k(this,"rateLimited",void 0),k(this,"pageLevelData",void 0),k(this,"viewerData",void 0),this.mux=t,this.envKey=i,this.options=a,this.previousBeaconData=null,this.lastEventTime=0,this.rateLimited=!1,this.eventQueue=new tE(Kf(this.envKey,this.options));var _;this.sampleRate=(_=this.options.sampleRate)!==null&&_!==void 0?_:1;var y;this.disableCookies=(y=this.options.disableCookies)!==null&&y!==void 0?y:!1;var T;this.respectDoNotTrack=(T=this.options.respectDoNotTrack)!==null&&T!==void 0?T:!1,this.previousBeaconData=null,this.lastEventTime=0,this.rateLimited=!1,this.pageLevelData={mux_api_version:this.mux.API_VERSION,mux_embed:this.mux.NAME,mux_embed_version:this.mux.VERSION,viewer_application_name:(r=this.options.platform)===null||r===void 0?void 0:r.name,viewer_application_version:(n=this.options.platform)===null||n===void 0?void 0:n.version,viewer_application_engine:(s=this.options.platform)===null||s===void 0?void 0:s.layout,viewer_device_name:(o=this.options.platform)===null||o===void 0?void 0:o.product,viewer_device_category:"",viewer_device_manufacturer:(l=this.options.platform)===null||l===void 0?void 0:l.manufacturer,viewer_os_family:(m=this.options.platform)===null||m===void 0||(d=m.os)===null||d===void 0?void 0:d.family,viewer_os_architecture:(h=this.options.platform)===null||h===void 0||(p=h.os)===null||p===void 0?void 0:p.architecture,viewer_os_version:(v=this.options.platform)===null||v===void 0||(c=v.os)===null||c===void 0?void 0:c.version,viewer_connection_type:qf(),page_url:Ao.default===null||Ao.default===void 0||(g=Ao.default.location)===null||g===void 0?void 0:g.href},this.viewerData=this.disableCookies?{}:Wf()}return Bt(e,[{key:"send",value:function(t,i){if(!(!t||!(i!=null&&i.view_id))){if(this.respectDoNotTrack&&qo())return J.info("Not sending `"+t+"` because Do Not Track is enabled");if(!i||typeof i!="object")return J.error("A data object was expected in send() but was not provided");var a=this.disableCookies?{}:Ff(),r=ad(Ls({},this.pageLevelData,i,a,this.viewerData),{event:t,env_key:this.envKey});r.user_id&&(r.viewer_user_id=r.user_id,delete r.user_id);var n,s=((n=r.mux_sample_number)!==null&&n!==void 0?n:0)>=this.sampleRate,o=this._deduplicateBeaconData(t,r),l=Go(o);if(this.lastEventTime=this.mux.utils.now(),s)return J.info("Not sending event due to sample rate restriction",t,r,l);if(this.envKey||J.info("Missing environment key (envKey) - beacons will be dropped if the video source is not a valid mux video URL",t,r,l),!this.rateLimited){if(J.info("Sending event",t,r,l),this.rateLimited=!this.eventQueue.queueEvent(t,l),this.mux.WINDOW_UNLOADING&&t==="viewend")this.eventQueue.destroy(!0);else if(this.mux.WINDOW_HIDDEN&&t==="hb"?this.eventQueue.flushEvents(!0):nE.indexOf(t)>=0&&this.eventQueue.flushEvents(),this.rateLimited)return r.event="eventrateexceeded",l=Go(r),this.eventQueue.queueEvent(r.event,l),J.error("Beaconing disabled due to rate limit.")}}}},{key:"destroy",value:function(){this.eventQueue.destroy(!1)}},{key:"_deduplicateBeaconData",value:function(t,i){var a=this,r={},n=i.view_id;if(n==="-1"||t==="viewstart"||t==="viewend"||!this.previousBeaconData||this.mux.utils.now()-this.lastEventTime>=sE)r=Ls({},i),n&&(this.previousBeaconData=r),n&&t==="viewend"&&(this.previousBeaconData=null);else{var s=t.indexOf("request")===0;Object.entries(i).forEach(function(o){var l=Xt(o,2),d=l[0],m=l[1];a.previousBeaconData&&(m!==a.previousBeaconData[d]||iE.indexOf(d)>-1||a.objectHasChanged(s,d,m,a.previousBeaconData[d])||a.eventRequiresKey(t,d))&&(r[d]=m,a.previousBeaconData[d]=m)})}return r}},{key:"objectHasChanged",value:function(t,i,a,r){return!t||i.indexOf("request_")!==0?!1:i==="request_response_headers"||typeof a!="object"||typeof r!="object"?!0:Object.keys(a||{}).length!==Object.keys(r||{}).length}},{key:"eventRequiresKey",value:function(t,i){return!!(t==="renditionchange"&&i.indexOf("video_source_")===0||rE.includes(i)&&aE.includes(t))}}]),e})(),lE=function e(t){we(this,e);var i=0,a=0,r=0,n=0,s=0,o=0,l=0,d=function(h,c){var v=c.request_start,g=c.request_response_start,_=c.request_response_end,y=c.request_bytes_loaded;n++;var T,f;if(g?(T=g-(v??0),f=(_??0)-g):f=(_??0)-(v??0),f>0&&y&&y>0){var S=y/f*8e3;s++,a+=y,r+=f,t.data.view_min_request_throughput=Math.min(t.data.view_min_request_throughput||1/0,S),t.data.view_average_request_throughput=a/r*8e3,t.data.view_request_count=n,T>0&&(i+=T,t.data.view_max_request_latency=Math.max(t.data.view_max_request_latency||0,T),t.data.view_average_request_latency=i/s)}},m=function(h,c){n++,o++,t.data.view_request_count=n,t.data.view_request_failed_count=o},p=function(h,c){n++,l++,t.data.view_request_count=n,t.data.view_request_canceled_count=l};t.on("requestcompleted",d),t.on("requestfailed",m),t.on("requestcanceled",p)},dE=lE,uE=3600*1e3,cE=function e(t){var i=this;we(this,e),k(this,"_lastEventTime",void 0),t.on("before*",function(a,r){var n=r.viewer_time,s=_e.now(),o=i._lastEventTime;if(i._lastEventTime=s,o&&s-o>uE){var l=Object.keys(t.data).reduce(function(m,p){return p.indexOf("video_")===0?Object.assign(m,k({},p,t.data[p])):m},{});t.mux.log.info("Received event after at least an hour inactivity, creating a new view");var d=t.playbackHeartbeat._playheadShouldBeProgressing;t._resetView(Object.assign({viewer_time:n},l)),t.playbackHeartbeat._playheadShouldBeProgressing=d,t.playbackHeartbeat._playheadShouldBeProgressing&&a.type!=="play"&&a.type!=="adbreakstart"&&(t.emit("play",{viewer_time:n}),a.type!=="playing"&&t.emit("playing",{viewer_time:n}))}})},hE=cE,mE=["viewstart","ended","loadstart","pause","play","playing","ratechange","waiting","adplay","adpause","adended","aderror","adplaying","adrequest","adresponse","adbreakstart","adbreakend","adfirstquartile","admidpoint","adthirdquartile","rebufferstart","rebufferend","seeked","error","hb","requestcompleted","requestfailed","requestcanceled","renditionchange"],pE=new Set(["requestcompleted","requestfailed","requestcanceled"]),vE=(function(e){Yv(i,e);var t=Qv(i);function i(a,r,n){we(this,i);var s;s=t.call(this),k(N(s),"DOM_CONTENT_LOADED_EVENT_END",void 0),k(N(s),"NAVIGATION_START",void 0),k(N(s),"_destroyed",void 0),k(N(s),"_heartBeatTimeout",void 0),k(N(s),"adTracker",void 0),k(N(s),"dashjs",void 0),k(N(s),"data",void 0),k(N(s),"disablePlayheadRebufferTracking",void 0),k(N(s),"disableRebufferTracking",void 0),k(N(s),"errorTracker",void 0),k(N(s),"errorTranslator",void 0),k(N(s),"emitTranslator",void 0),k(N(s),"getAdData",void 0),k(N(s),"getPlayheadTime",void 0),k(N(s),"getStateData",void 0),k(N(s),"stateDataTranslator",void 0),k(N(s),"hlsjs",void 0),k(N(s),"id",void 0),k(N(s),"longResumeTracker",void 0),k(N(s),"minimumRebufferDuration",void 0),k(N(s),"mux",void 0),k(N(s),"playbackEventDispatcher",void 0),k(N(s),"playbackHeartbeat",void 0),k(N(s),"playbackHeartbeatTime",void 0),k(N(s),"playheadTime",void 0),k(N(s),"seekingTracker",void 0),k(N(s),"sustainedRebufferThreshold",void 0),k(N(s),"watchTimeTracker",void 0),k(N(s),"currentFragmentPDT",void 0),k(N(s),"currentFragmentStart",void 0),s.DOM_CONTENT_LOADED_EVENT_END=Ds.domContentLoadedEventEnd(),s.NAVIGATION_START=Ds.navigationStart();var o={debug:!1,minimumRebufferDuration:250,sustainedRebufferThreshold:1e3,playbackHeartbeatTime:25,beaconDomain:"litix.io",sampleRate:1,disableCookies:!1,respectDoNotTrack:!1,disableRebufferTracking:!1,disablePlayheadRebufferTracking:!1,errorTranslator:function(h){return h},emitTranslator:function(){for(var h=arguments.length,c=new Array(h),v=0;v<h;v++)c[v]=arguments[v];return c},stateDataTranslator:function(h){return h}};s.mux=a,s.id=r,n!=null&&n.beaconDomain&&s.mux.log.warn("The `beaconDomain` setting has been deprecated in favor of `beaconCollectionDomain`. Please change your integration to use `beaconCollectionDomain` instead of `beaconDomain`."),n=Object.assign(o,n),n.data=n.data||{},n.data.property_key&&(n.data.env_key=n.data.property_key,delete n.data.property_key),J.level=n.debug?Ni.DEBUG:Ni.WARN,s.getPlayheadTime=n.getPlayheadTime,s.getStateData=n.getStateData||function(){return{}},s.getAdData=n.getAdData||function(){},s.minimumRebufferDuration=n.minimumRebufferDuration,s.sustainedRebufferThreshold=n.sustainedRebufferThreshold,s.playbackHeartbeatTime=n.playbackHeartbeatTime,s.disableRebufferTracking=n.disableRebufferTracking,s.disableRebufferTracking&&s.mux.log.warn("Disabling rebuffer tracking. This should only be used in specific circumstances as a last resort when your player is known to unreliably track rebuffering."),s.disablePlayheadRebufferTracking=n.disablePlayheadRebufferTracking,s.errorTranslator=n.errorTranslator,s.emitTranslator=n.emitTranslator,s.stateDataTranslator=n.stateDataTranslator,s.playbackEventDispatcher=new oE(a,n.data.env_key,n),s.data={player_instance_id:Br(),mux_sample_rate:n.sampleRate,beacon_domain:n.beaconCollectionDomain||n.beaconDomain},s.data.view_sequence_number=1,s.data.player_sequence_number=1;var l=(function(){typeof this.data.view_start>"u"&&(this.data.view_start=this.mux.utils.now(),this.emit("viewstart"))}).bind(N(s));if(s.on("viewinit",function(h,c){this._resetVideoData(),this._resetViewData(),this._resetErrorData(),this._updateStateData(),Object.assign(this.data,c),this._initializeViewData(),this.one("play",l),this.one("adbreakstart",l)}),s.on("videochange",function(h,c){this._resetView(c)}),s.on("programchange",function(h,c){this.data.player_is_paused&&this.mux.log.warn("The `programchange` event is intended to be used when the content changes mid playback without the video source changing, however the video is not currently playing. If the video source is changing please use the videochange event otherwise you will lose startup time information."),this._resetView(Object.assign(c,{view_program_changed:!0})),l(),this.emit("play"),this.emit("playing")}),s.on("fragmentchange",function(h,c){this.currentFragmentPDT=c.currentFragmentPDT,this.currentFragmentStart=c.currentFragmentStart}),s.on("destroy",s.destroy),typeof window<"u"&&typeof window.addEventListener=="function"&&typeof window.removeEventListener=="function"){var d=function(){var h=typeof s.data.view_start<"u";s.mux.WINDOW_HIDDEN=document.visibilityState==="hidden",h&&s.mux.WINDOW_HIDDEN&&(s.data.player_is_paused||s.emit("hb"))};window.addEventListener("visibilitychange",d,!1);var m=function(h){h.persisted||s.destroy()};window.addEventListener("pagehide",m,!1),s.on("destroy",function(){window.removeEventListener("visibilitychange",d),window.removeEventListener("pagehide",m)})}s.on("playerready",function(h,c){Object.assign(this.data,c)}),mE.forEach(function(h){s.on(h,function(c,v){h.indexOf("ad")!==0&&this._updateStateData(),Object.assign(this.data,v),this._sanitizeData()}),s.on("after"+h,function(){(h!=="error"||this.errorTracker.viewErrored)&&this.send(h)})}),s.on("viewend",function(h,c){Object.assign(s.data,c)});var p=function(h){var c=this.mux.utils.now();this.data.player_init_time&&(this.data.player_startup_time=c-this.data.player_init_time),!this.mux.PLAYER_TRACKED&&this.NAVIGATION_START&&(this.mux.PLAYER_TRACKED=!0,(this.data.player_init_time||this.DOM_CONTENT_LOADED_EVENT_END)&&(this.data.page_load_time=Math.min(this.data.player_init_time||1/0,this.DOM_CONTENT_LOADED_EVENT_END||1/0)-this.NAVIGATION_START)),this.send("playerready"),delete this.data.player_startup_time,delete this.data.page_load_time};return s.one("playerready",p),s.longResumeTracker=new hE(N(s)),s.errorTracker=new pf(N(s)),new Uf(N(s)),s.seekingTracker=new Lf(N(s)),s.playheadTime=new gf(N(s)),s.playbackHeartbeat=new hf(N(s)),new Rf(N(s)),s.watchTimeTracker=new ff(N(s)),new _f(N(s)),s.adTracker=new Of(N(s)),new kf(N(s)),new Tf(N(s)),new wf(N(s)),new Pf(N(s)),new dE(N(s)),n.hlsjs&&s.addHLSJS(n),n.dashjs&&s.addDashJS(n),s.emit("viewinit",n.data),s}return Bt(i,[{key:"emit",value:function(a,r){var n,s=Object.assign({viewer_time:this.mux.utils.now()},r),o=[a,s];if(this.emitTranslator)try{o=this.emitTranslator(a,s)}catch(l){this.mux.log.warn("Exception in emit translator callback.",l)}o!=null&&o.length&&(n=kn(Na(i.prototype),"emit",this)).call.apply(n,[this].concat(lt(o)))}},{key:"destroy",value:function(){this._destroyed||(this._destroyed=!0,typeof this.data.view_start<"u"&&(this.emit("viewend"),this.send("viewend")),this.playbackEventDispatcher.destroy(),this.removeHLSJS(),this.removeDashJS(),window.clearTimeout(this._heartBeatTimeout))}},{key:"send",value:function(a){if(this.data.view_id){var r=Object.assign({},this.data),n=["player_program_time","player_manifest_newest_program_time","player_live_edge_program_time","player_program_time","video_holdback","video_part_holdback","video_target_duration","video_part_target_duration"];if(r.video_source_is_live===void 0&&(r.player_source_duration===1/0||r.video_source_duration===1/0?r.video_source_is_live=!0:(r.player_source_duration>0||r.video_source_duration>0)&&(r.video_source_is_live=!1)),r.video_source_is_live||n.forEach(function(d){r[d]=void 0}),r.video_source_url=r.video_source_url||r.player_source_url,r.video_source_url){var s=Xt(Wr(r.video_source_url),2),o=s[0],l=s[1];r.video_source_domain=l,r.video_source_hostname=o}delete r.ad_request_id,this.playbackEventDispatcher.send(a,r),this.data.view_sequence_number++,this.data.player_sequence_number++,pE.has(a)||this._restartHeartBeat(),a==="viewend"&&delete this.data.view_id}}},{key:"_resetView",value:function(a){this.emit("viewend"),this.send("viewend"),this.emit("viewinit",a)}},{key:"_updateStateData",value:function(){var a=this.getStateData();if(typeof this.stateDataTranslator=="function")try{a=this.stateDataTranslator(a)}catch(r){this.mux.log.warn("Exception in stateDataTranslator translator callback.",r)}Object.assign(this.data,a),this.playheadTime._updatePlayheadTime(),this._sanitizeData()}},{key:"_sanitizeData",value:function(){var a=this,r=["player_width","player_height","video_source_width","video_source_height","player_playhead_time","video_source_bitrate"];r.forEach(function(s){var o=parseInt(a.data[s],10);a.data[s]=isNaN(o)?void 0:o});var n=["player_source_url","video_source_url"];n.forEach(function(s){if(a.data[s]){var o=a.data[s].toLowerCase();(o.indexOf("data:")===0||o.indexOf("blob:")===0)&&(a.data[s]="MSE style URL")}})}},{key:"_resetVideoData",value:function(){var a=this;Object.keys(this.data).forEach(function(r){r.indexOf("video_")===0&&delete a.data[r]})}},{key:"_resetViewData",value:function(){var a=this;Object.keys(this.data).forEach(function(r){r.indexOf("view_")===0&&delete a.data[r]}),this.data.view_sequence_number=1}},{key:"_resetErrorData",value:function(){delete this.data.player_error_code,delete this.data.player_error_message,delete this.data.player_error_context,delete this.data.player_error_severity,delete this.data.player_error_business_exception}},{key:"_initializeViewData",value:function(){var a=this,r=this.data.view_id=Br(),n=function(){r===a.data.view_id&&Ee(a.data,"player_view_count",1)};this.data.player_is_paused?this.one("play",n):n()}},{key:"_restartHeartBeat",value:function(){var a=this;window.clearTimeout(this._heartBeatTimeout),this._heartBeatTimeout=window.setTimeout(function(){a.data.player_is_paused||a.emit("hb")},1e4)}},{key:"addHLSJS",value:function(a){if(!a.hlsjs){this.mux.log.warn("You must pass a valid hlsjs instance in order to track it.");return}if(this.hlsjs){this.mux.log.warn("An instance of HLS.js is already being monitored for this player.");return}this.hlsjs=a.hlsjs,tf(this.mux,this.id,a.hlsjs,{},a.Hls||window.Hls)}},{key:"removeHLSJS",value:function(){this.hlsjs&&(af(this.hlsjs),this.hlsjs=void 0)}},{key:"addDashJS",value:function(a){if(!a.dashjs){this.mux.log.warn("You must pass a valid dashjs instance in order to track it.");return}if(this.dashjs){this.mux.log.warn("An instance of Dash.js is already being monitored for this player.");return}this.dashjs=a.dashjs,of(this.mux,this.id,a.dashjs)}},{key:"removeDashJS",value:function(){this.dashjs&&(lf(this.dashjs),this.dashjs=void 0)}}]),i})(uf),fE=vE,Qa=je(yh());function EE(){return Qa.default&&!!(Qa.default.fullscreenElement||Qa.default.webkitFullscreenElement||Qa.default.mozFullScreenElement||Qa.default.msFullscreenElement)}var _E=["loadstart","pause","play","playing","seeking","seeked","timeupdate","ratechange","stalled","waiting","error","ended"],bE={1:"MEDIA_ERR_ABORTED",2:"MEDIA_ERR_NETWORK",3:"MEDIA_ERR_DECODE",4:"MEDIA_ERR_SRC_NOT_SUPPORTED"};function gE(e,t,i){var a=Xt(Cs(t),3),r=a[0],n=a[1],s=a[2],o=e.log,l=e.utils.getComputedStyle,d=e.utils.secondsToMs,m={automaticErrorTracking:!0};if(r){if(s!=="video"&&s!=="audio")return o.error("The element of `"+n+"` was not a media element.")}else return o.error("No element was found with the `"+n+"` query selector.");r.mux&&(r.mux.destroy(),delete r.mux,o.warn("Already monitoring this video element, replacing existing event listeners"));var p={getPlayheadTime:function(){return d(r.currentTime)},getStateData:function(){var c,v,g,_=((c=(v=this).getPlayheadTime)===null||c===void 0?void 0:c.call(v))||d(r.currentTime),y=this.hlsjs&&this.hlsjs.url,T=this.dashjs&&typeof this.dashjs.getSource=="function"&&this.dashjs.getSource(),f={player_is_paused:r.paused,player_width:parseInt(l(r,"width")),player_height:parseInt(l(r,"height")),player_autoplay_on:r.autoplay,player_preload_on:r.preload,player_language_code:r.lang,player_is_fullscreen:EE(),video_poster_url:r.poster,video_source_url:y||T||r.currentSrc,video_source_duration:d(r.duration),video_source_height:r.videoHeight,video_source_width:r.videoWidth,view_dropped_frame_count:r==null||(g=r.getVideoPlaybackQuality)===null||g===void 0?void 0:g.call(r).droppedVideoFrames};if(r.getStartDate&&_>0){var S=r.getStartDate();if(S&&typeof S.getTime=="function"&&S.getTime()){var D=S.getTime();if(f.player_program_time=D+_,r.seekable.length>0){var O=D+r.seekable.end(r.seekable.length-1);f.player_live_edge_program_time=O}}}return f}};i=Object.assign(m,i,p),i.data=Object.assign({player_software:"HTML5 Video Element",player_mux_plugin_name:"VideoElementMonitor",player_mux_plugin_version:e.VERSION},i.data),r.mux=r.mux||{},r.mux.deleted=!1,r.mux.emit=function(c,v){e.emit(n,c,v)},r.mux.updateData=function(c){r.mux.emit("hb",c)};var h=function(){o.error("The monitor for this video element has already been destroyed.")};r.mux.destroy=function(){Object.keys(r.mux.listeners).forEach(function(c){r.removeEventListener(c,r.mux.listeners[c],!1)}),delete r.mux.listeners,r.mux.destroy=h,r.mux.swapElement=h,r.mux.emit=h,r.mux.addHLSJS=h,r.mux.addDashJS=h,r.mux.removeHLSJS=h,r.mux.removeDashJS=h,r.mux.updateData=h,r.mux.setEmitTranslator=h,r.mux.setStateDataTranslator=h,r.mux.setGetPlayheadTime=h,r.mux.deleted=!0,e.emit(n,"destroy")},r.mux.swapElement=function(c){var v=Xt(Cs(c),3),g=v[0],_=v[1],y=v[2];if(g){if(y!=="video"&&y!=="audio")return e.log.error("The element of `"+_+"` was not a media element.")}else return e.log.error("No element was found with the `"+_+"` query selector.");g.muxId=r.muxId,delete r.muxId,g.mux=g.mux||{},g.mux.listeners=Object.assign({},r.mux.listeners),delete r.mux.listeners,Object.keys(g.mux.listeners).forEach(function(T){r.removeEventListener(T,g.mux.listeners[T],!1),g.addEventListener(T,g.mux.listeners[T],!1)}),g.mux.swapElement=r.mux.swapElement,g.mux.destroy=r.mux.destroy,delete r.mux,r=g},r.mux.addHLSJS=function(c){e.addHLSJS(n,c)},r.mux.addDashJS=function(c){e.addDashJS(n,c)},r.mux.removeHLSJS=function(){e.removeHLSJS(n)},r.mux.removeDashJS=function(){e.removeDashJS(n)},r.mux.setEmitTranslator=function(c){e.setEmitTranslator(n,c)},r.mux.setStateDataTranslator=function(c){e.setStateDataTranslator(n,c)},r.mux.setGetPlayheadTime=function(c){c||(c=i.getPlayheadTime),e.setGetPlayheadTime(n,c)},e.init(n,i),e.emit(n,"playerready"),r.paused||(e.emit(n,"play"),r.readyState>2&&e.emit(n,"playing")),r.mux.listeners={},_E.forEach(function(c){c==="error"&&!i.automaticErrorTracking||(r.mux.listeners[c]=function(){var v={};if(c==="error"){if(!r.error||r.error.code===1)return;v.player_error_code=r.error.code,v.player_error_message=bE[r.error.code]||r.error.message}e.emit(n,c,v)},r.addEventListener(c,r.mux.listeners[c],!1))})}function yE(e,t,i,a){var r=a;if(e&&typeof e[t]=="function")try{r=e[t].apply(e,i)}catch(n){J.info("safeCall error",n)}return r}var Cr=je(vt()),na;Cr.default&&Cr.default.WeakMap&&(na=new WeakMap);function TE(e,t){if(!e||!t||!Cr.default||typeof Cr.default.getComputedStyle!="function")return"";var i;return na&&na.has(e)&&(i=na.get(e)),i||(i=Cr.default.getComputedStyle(e,null),na&&na.set(e,i)),i.getPropertyValue(t)}function AE(e){return Math.floor(e*1e3)}var Ti={TARGET_DURATION:"#EXT-X-TARGETDURATION",PART_INF:"#EXT-X-PART-INF",SERVER_CONTROL:"#EXT-X-SERVER-CONTROL",INF:"#EXTINF",PROGRAM_DATE_TIME:"#EXT-X-PROGRAM-DATE-TIME",VERSION:"#EXT-X-VERSION",SESSION_DATA:"#EXT-X-SESSION-DATA"},eo=function(e){return this.buffer="",this.manifest={segments:[],serverControl:{},sessionData:{}},this.currentUri={},this.process(e),this.manifest};eo.prototype.process=function(e){var t;for(this.buffer+=e,t=this.buffer.indexOf(`
`);t>-1;t=this.buffer.indexOf(`
`))this.processLine(this.buffer.substring(0,t)),this.buffer=this.buffer.substring(t+1)};eo.prototype.processLine=function(e){var t=e.indexOf(":"),i=IE(e,t),a=i[0],r=i.length===2?nd(i[1]):void 0;if(a[0]!=="#")this.currentUri.uri=a,this.manifest.segments.push(this.currentUri),this.manifest.targetDuration&&!("duration"in this.currentUri)&&(this.currentUri.duration=this.manifest.targetDuration),this.currentUri={};else switch(a){case Ti.TARGET_DURATION:{if(!isFinite(r)||r<0)return;this.manifest.targetDuration=r,this.setHoldBack();break}case Ti.PART_INF:{ko(this.manifest,i),this.manifest.partInf.partTarget&&(this.manifest.partTargetDuration=this.manifest.partInf.partTarget),this.setHoldBack();break}case Ti.SERVER_CONTROL:{ko(this.manifest,i),this.setHoldBack();break}case Ti.INF:{r===0?this.currentUri.duration=.01:r>0&&(this.currentUri.duration=r);break}case Ti.PROGRAM_DATE_TIME:{var n=r,s=new Date(n);this.manifest.dateTimeString||(this.manifest.dateTimeString=n,this.manifest.dateTimeObject=s),this.currentUri.dateTimeString=n,this.currentUri.dateTimeObject=s;break}case Ti.VERSION:{ko(this.manifest,i);break}case Ti.SESSION_DATA:{var o=RE(i[1]),l=Sh(o);Object.assign(this.manifest.sessionData,l)}}};eo.prototype.setHoldBack=function(){var e=this.manifest,t=e.serverControl,i=e.targetDuration,a=e.partTargetDuration;if(t){var r="holdBack",n="partHoldBack",s=i&&i*3,o=a&&a*2;i&&!t.hasOwnProperty(r)&&(t[r]=s),s&&t[r]<s&&(t[r]=s),a&&!t.hasOwnProperty(n)&&(t[n]=a*3),a&&t[n]<o&&(t[n]=o)}};var ko=function(e,t){var i=Oh(t[0].replace("#EXT-X-","")),a;wE(t[1])?(a={},a=Object.assign(SE(t[1]),a)):a=nd(t[1]),e[i]=a},Oh=function(e){return e.toLowerCase().replace(/-(\w)/g,function(t){return t[1].toUpperCase()})},nd=function(e){if(e.toLowerCase()==="yes"||e.toLowerCase()==="no")return e.toLowerCase()==="yes";var t=e.indexOf(":")!==-1?e:parseFloat(e);return isNaN(t)?e:t},kE=function(e){var t={},i=e.split("=");if(i.length>1){var a=Oh(i[0]);t[a]=nd(i[1])}return t},SE=function(e){for(var t=e.split(","),i={},a=0;t.length>a;a++){var r=t[a],n=kE(r);i=Object.assign(n,i)}return i},wE=function(e){return e.indexOf("=")>-1},IE=function(e,t){return t===-1?[e]:[e.substring(0,t),e.substring(t+1)]},RE=function(e){var t={};if(e){var i=e.search(","),a=e.slice(0,i),r=e.slice(i+1),n=[a,r];return n.forEach(function(s,o){for(var l=s.replace(/['"]+/g,"").split("="),d=0;d<l.length;d++)l[d]==="DATA-ID"&&(t["DATA-ID"]=l[1-d]),l[d]==="VALUE"&&(t.VALUE=l[1-d])}),{data:t}}},CE=eo,DE={safeCall:yE,safeIncrement:Ee,getComputedStyle:TE,secondsToMs:AE,assign:Object.assign,headersStringToObject:rd,cdnHeadersToRequestId:Ms,extractHostnameAndDomain:Wr,extractHostname:mt,manifestParser:CE,generateShortID:Ah,generateUUID:Br,now:_e.now,findMediaElement:Cs},LE=DE,ME={PLAYER_READY:"playerready",VIEW_INIT:"viewinit",VIDEO_CHANGE:"videochange",PLAY:"play",PAUSE:"pause",PLAYING:"playing",TIME_UPDATE:"timeupdate",SEEKING:"seeking",SEEKED:"seeked",REBUFFER_START:"rebufferstart",REBUFFER_END:"rebufferend",ERROR:"error",ENDED:"ended",RENDITION_CHANGE:"renditionchange",ORIENTATION_CHANGE:"orientationchange",AD_REQUEST:"adrequest",AD_RESPONSE:"adresponse",AD_BREAK_START:"adbreakstart",AD_PLAY:"adplay",AD_PLAYING:"adplaying",AD_PAUSE:"adpause",AD_FIRST_QUARTILE:"adfirstquartile",AD_MID_POINT:"admidpoint",AD_THIRD_QUARTILE:"adthirdquartile",AD_ENDED:"adended",AD_BREAK_END:"adbreakend",AD_ERROR:"aderror",REQUEST_COMPLETED:"requestcompleted",REQUEST_FAILED:"requestfailed",REQUEST_CANCELLED:"requestcanceled",HEARTBEAT:"hb",DESTROY:"destroy"},xE=ME,OE="mux-embed",NE="5.9.0",PE="2.1",he={},_i=function(e){var t=arguments;typeof e=="string"?_i.hasOwnProperty(e)?Rr.default.setTimeout(function(){t=Array.prototype.splice.call(t,1),_i[e].apply(null,t)},0):J.warn("`"+e+"` is an unknown task"):typeof e=="function"?Rr.default.setTimeout(function(){e(_i)},0):J.warn("`"+e+"` is invalid.")},$E={loaded:_e.now(),NAME:OE,VERSION:NE,API_VERSION:PE,PLAYER_TRACKED:!1,monitor:function(e,t){return gE(_i,e,t)},destroyMonitor:function(e){var t=Xt(Cs(e),1),i=t[0];i&&i.mux&&typeof i.mux.destroy=="function"?i.mux.destroy():J.error("A video element monitor for `"+e+"` has not been initialized via `mux.monitor`.")},addHLSJS:function(e,t){var i=ot(e);he[i]?he[i].addHLSJS(t):J.error("A monitor for `"+i+"` has not been initialized.")},addDashJS:function(e,t){var i=ot(e);he[i]?he[i].addDashJS(t):J.error("A monitor for `"+i+"` has not been initialized.")},removeHLSJS:function(e){var t=ot(e);he[t]?he[t].removeHLSJS():J.error("A monitor for `"+t+"` has not been initialized.")},removeDashJS:function(e){var t=ot(e);he[t]?he[t].removeDashJS():J.error("A monitor for `"+t+"` has not been initialized.")},init:function(e,t){qo()&&t&&t.respectDoNotTrack&&J.info("The browser's Do Not Track flag is enabled - Mux beaconing is disabled.");var i=ot(e);he[i]=new fE(_i,i,t)},emit:function(e,t,i){var a=ot(e);he[a]?(he[a].emit(t,i),t==="destroy"&&delete he[a]):J.error("A monitor for `"+a+"` has not been initialized.")},updateData:function(e,t){var i=ot(e);he[i]?he[i].emit("hb",t):J.error("A monitor for `"+i+"` has not been initialized.")},setEmitTranslator:function(e,t){var i=ot(e);he[i]?he[i].emitTranslator=t:J.error("A monitor for `"+i+"` has not been initialized.")},setStateDataTranslator:function(e,t){var i=ot(e);he[i]?he[i].stateDataTranslator=t:J.error("A monitor for `"+i+"` has not been initialized.")},setGetPlayheadTime:function(e,t){var i=ot(e);he[i]?he[i].getPlayheadTime=t:J.error("A monitor for `"+i+"` has not been initialized.")},checkDoNotTrack:qo,log:J,utils:LE,events:xE,WINDOW_HIDDEN:!1,WINDOW_UNLOADING:!1};Object.assign(_i,$E);typeof Rr.default<"u"&&typeof Rr.default.addEventListener=="function"&&Rr.default.addEventListener("pagehide",function(e){e.persisted||(_i.WINDOW_UNLOADING=!0)},!1);var sd=_i;var B=gv,te={VIDEO:"video",THUMBNAIL:"thumbnail",STORYBOARD:"storyboard",DRM:"drm"},x={NOT_AN_ERROR:0,NETWORK_OFFLINE:2000002,NETWORK_UNKNOWN_ERROR:2e6,NETWORK_NO_STATUS:2000001,NETWORK_INVALID_URL:24e5,NETWORK_NOT_FOUND:2404e3,NETWORK_NOT_READY:2412e3,NETWORK_GENERIC_SERVER_FAIL:25e5,NETWORK_TOKEN_MISSING:2403201,NETWORK_TOKEN_MALFORMED:2412202,NETWORK_TOKEN_EXPIRED:2403210,NETWORK_TOKEN_AUD_MISSING:2403221,NETWORK_TOKEN_AUD_MISMATCH:2403222,NETWORK_TOKEN_SUB_MISMATCH:2403232,ENCRYPTED_ERROR:5e6,ENCRYPTED_UNSUPPORTED_KEY_SYSTEM:5000001,ENCRYPTED_GENERATE_REQUEST_FAILED:5000002,ENCRYPTED_UPDATE_LICENSE_FAILED:5000003,ENCRYPTED_UPDATE_SERVER_CERT_FAILED:5000004,ENCRYPTED_CDM_ERROR:5000005,ENCRYPTED_OUTPUT_RESTRICTED:5000006,ENCRYPTED_MISSING_TOKEN:5000002},to=e=>e===te.VIDEO?"playback":e,si=class or extends Error{constructor(t,i=or.MEDIA_ERR_CUSTOM,a,r){var n;super(t),this.name="MediaError",this.code=i,this.context=r,this.fatal=a??(i>=or.MEDIA_ERR_NETWORK&&i<=or.MEDIA_ERR_ENCRYPTED),this.message||(this.message=(n=or.defaultMessages[this.code])!=null?n:"")}};si.MEDIA_ERR_ABORTED=1,si.MEDIA_ERR_NETWORK=2,si.MEDIA_ERR_DECODE=3,si.MEDIA_ERR_SRC_NOT_SUPPORTED=4,si.MEDIA_ERR_ENCRYPTED=5,si.MEDIA_ERR_CUSTOM=100,si.defaultMessages={1:"You aborted the media playback",2:"A network error caused the media download to fail.",3:"A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.",4:"An unsupported error occurred. The server or network failed, or your browser does not support this format.",5:"The media is encrypted and there are no keys to decrypt it."};var w=si,UE=e=>e==null,od=(e,t)=>UE(t)?!1:e in t,jo={ANY:"any",MUTED:"muted"},G={ON_DEMAND:"on-demand",LIVE:"live",UNKNOWN:"unknown"},Nt={MSE:"mse",NATIVE:"native"},lr={HEADER:"header",QUERY:"query",NONE:"none"},xs=Object.values(lr),Zt={M3U8:"application/vnd.apple.mpegurl",MP4:"video/mp4"},Ku={HLS:Zt.M3U8};[...Object.values(Zt)];var gT={upTo720p:"720p",upTo1080p:"1080p",upTo1440p:"1440p",upTo2160p:"2160p"},yT={noLessThan480p:"480p",noLessThan540p:"540p",noLessThan720p:"720p",noLessThan1080p:"1080p",noLessThan1440p:"1440p",noLessThan2160p:"2160p"},TT={DESCENDING:"desc"},HE="en",Qo={code:HE},pe=(e,t,i,a,r=e)=>{r.addEventListener(t,i,a),e.addEventListener("teardown",()=>{r.removeEventListener(t,i)},{once:!0})};function BE(e,t,i){t&&i>t&&(i=t);for(let a=0;a<e.length;a++)if(e.start(a)<=i&&e.end(a)>=i)return!0;return!1}var ld=e=>{let t=e.indexOf("?");if(t<0)return[e];let i=e.slice(0,t),a=e.slice(t);return[i,a]},io=e=>{let{type:t}=e;if(t){let i=t.toUpperCase();return od(i,Ku)?Ku[i]:t}return WE(e)},Nh=e=>e==="VOD"?G.ON_DEMAND:G.LIVE,Ph=e=>e==="EVENT"?Number.POSITIVE_INFINITY:e==="VOD"?Number.NaN:0,WE=e=>{let{src:t}=e;if(!t)return"";let i="";try{i=new URL(t).pathname}catch{console.error("invalid url")}let a=i.lastIndexOf(".");if(a<0)return KE(e)?Zt.M3U8:"";let r=i.slice(a+1).toUpperCase();return od(r,Zt)?Zt[r]:""},FE="mux.com",KE=({src:e,customDomain:t=FE})=>{let i;try{i=new URL(`${e}`)}catch{return!1}let a=i.protocol==="https:",r=i.hostname===`stream.${t}`.toLowerCase(),n=i.pathname.split("/"),s=n.length===2,o=!(n!=null&&n[1].includes("."));return a&&r&&s&&o},Ia=e=>{let t=(e??"").split(".")[1];if(t)try{let i=t.replace(/-/g,"+").replace(/_/g,"/"),a=decodeURIComponent(atob(i).split("").map(function(r){return"%"+("00"+r.charCodeAt(0).toString(16)).slice(-2)}).join(""));return JSON.parse(a)}catch{return}},VE=({exp:e},t=Date.now())=>!e||e*1e3<t,qE=({sub:e},t)=>e!==t,YE=({aud:e},t)=>!e,GE=({aud:e},t)=>e!==t,$h="en";function L(e,t=!0){var i,a;let r=t&&(a=(i=Qo)==null?void 0:i[e])!=null?a:e,n=t?Qo.code:$h;return new jE(r,n)}var jE=class{constructor(t,i=(a=>(a=Qo)!=null?a:$h)()){this.message=t,this.locale=i}format(t){return this.message.replace(/\{(\w+)\}/g,(i,a)=>{var r;return(r=t[a])!=null?r:""})}toString(){return this.message}},QE=Object.values(jo),Vu=e=>typeof e=="boolean"||typeof e=="string"&&QE.includes(e),ZE=(e,t,i)=>{let{autoplay:a}=e,r=!1,n=!1,s=Vu(a)?a:!!a,o=()=>{r||pe(t,"playing",()=>{r=!0},{once:!0})};if(o(),pe(t,"loadstart",()=>{r=!1,o(),So(t,s)},{once:!0}),pe(t,"loadstart",()=>{i||(e.streamType&&e.streamType!==G.UNKNOWN?n=e.streamType===G.LIVE:n=!Number.isFinite(t.duration)),So(t,s)},{once:!0}),i&&i.once(B.Events.LEVEL_LOADED,(l,d)=>{var m;e.streamType&&e.streamType!==G.UNKNOWN?n=e.streamType===G.LIVE:n=(m=d.details.live)!=null?m:!1}),!s){let l=()=>{!n||Number.isFinite(e.startTime)||(i!=null&&i.liveSyncPosition?t.currentTime=i.liveSyncPosition:Number.isFinite(t.seekable.end(0))&&(t.currentTime=t.seekable.end(0)))};i&&pe(t,"play",()=>{t.preload==="metadata"?i.once(B.Events.LEVEL_UPDATED,l):l()},{once:!0})}return l=>{r||(s=Vu(l)?l:!!l,So(t,s))}},So=(e,t)=>{if(!t)return;let i=e.muted,a=()=>e.muted=i;switch(t){case jo.ANY:e.play().catch(()=>{e.muted=!0,e.play().catch(a)});break;case jo.MUTED:e.muted=!0,e.play().catch(a);break;default:e.play().catch(()=>{});break}},zE=({preload:e,src:t},i,a)=>{let r=p=>{p!=null&&["","none","metadata","auto"].includes(p)?i.setAttribute("preload",p):i.removeAttribute("preload")};if(!a)return r(e),r;let n=!1,s=!1,o=a.config.maxBufferLength,l=a.config.maxBufferSize,d=p=>{r(p);let h=p??i.preload;s||h==="none"||(h==="metadata"?(a.config.maxBufferLength=1,a.config.maxBufferSize=1):(a.config.maxBufferLength=o,a.config.maxBufferSize=l),m())},m=()=>{!n&&t&&(n=!0,a.loadSource(t))};return pe(i,"play",()=>{s=!0,a.config.maxBufferLength=o,a.config.maxBufferSize=l,m()},{once:!0}),d(e),d};function XE(e,t){var i;if(!("videoTracks"in e))return;let a=new WeakMap;t.on(B.Events.MANIFEST_PARSED,function(l,d){o();let m=e.addVideoTrack("main");m.selected=!0;for(let[p,h]of d.levels.entries()){let c=m.addRendition(h.url[0],h.width,h.height,h.videoCodec,h.bitrate);a.set(h,`${p}`),c.id=`${p}`}}),t.on(B.Events.AUDIO_TRACKS_UPDATED,function(l,d){s();for(let m of d.audioTracks){let p=m.default?"main":"alternative",h=e.addAudioTrack(p,m.name,m.lang);h.id=`${m.id}`,m.default&&(h.enabled=!0)}}),e.audioTracks.addEventListener("change",()=>{var l;let d=+((l=[...e.audioTracks].find(p=>p.enabled))==null?void 0:l.id),m=t.audioTracks.map(p=>p.id);d!=t.audioTrack&&m.includes(d)&&(t.audioTrack=d)}),t.on(B.Events.LEVELS_UPDATED,function(l,d){var m;let p=e.videoTracks[(m=e.videoTracks.selectedIndex)!=null?m:0];if(!p)return;let h=d.levels.map(c=>a.get(c));for(let c of e.videoRenditions)c.id&&!h.includes(c.id)&&p.removeRendition(c)});let r=l=>{let d=l.target.selectedIndex;d!=t.nextLevel&&(t.nextLevel=d)};(i=e.videoRenditions)==null||i.addEventListener("change",r);let n=()=>{for(let l of e.videoTracks)e.removeVideoTrack(l)},s=()=>{for(let l of e.audioTracks)e.removeAudioTrack(l)},o=()=>{n(),s()};t.once(B.Events.DESTROYING,o)}var wo=e=>"time"in e?e.time:e.startTime;function JE(e,t){t.on(B.Events.NON_NATIVE_TEXT_TRACKS_FOUND,(r,{tracks:n})=>{n.forEach(s=>{var o,l;let d=(o=s.subtitleTrack)!=null?o:s.closedCaptions,m=t.subtitleTracks.findIndex(({lang:h,name:c,type:v})=>h==d?.lang&&c===s.label&&v.toLowerCase()===s.kind),p=((l=s._id)!=null?l:s.default)?"default":`${s.kind}${m}`;dd(e,s.kind,s.label,d?.lang,p,s.default)})});let i=()=>{if(!t.subtitleTracks.length)return;let r=Array.from(e.textTracks).find(o=>o.id&&o.mode==="showing"&&["subtitles","captions"].includes(o.kind));if(!r)return;let n=t.subtitleTracks[t.subtitleTrack],s=n?n.default?"default":`${t.subtitleTracks[t.subtitleTrack].type.toLowerCase()}${t.subtitleTrack}`:void 0;if(t.subtitleTrack<0||r?.id!==s){let o=t.subtitleTracks.findIndex(({lang:l,name:d,type:m,default:p})=>r.id==="default"&&p||l==r.language&&d===r.label&&m.toLowerCase()===r.kind);t.subtitleTrack=o}r?.id===s&&r.cues&&Array.from(r.cues).forEach(o=>{r.addCue(o)})};e.textTracks.addEventListener("change",i),t.on(B.Events.CUES_PARSED,(r,{track:n,cues:s})=>{let o=e.textTracks.getTrackById(n);if(!o)return;let l=o.mode==="disabled";l&&(o.mode="hidden"),s.forEach(d=>{var m;(m=o.cues)!=null&&m.getCueById(d.id)||o.addCue(d)}),l&&(o.mode="disabled")}),t.once(B.Events.DESTROYING,()=>{e.textTracks.removeEventListener("change",i),e.querySelectorAll("track[data-removeondestroy]").forEach(r=>{r.remove()})});let a=()=>{Array.from(e.textTracks).forEach(r=>{var n,s;if(!["subtitles","caption"].includes(r.kind)&&(r.label==="thumbnails"||r.kind==="chapters")){if(!((n=r.cues)!=null&&n.length)){let o="track";r.kind&&(o+=`[kind="${r.kind}"]`),r.label&&(o+=`[label="${r.label}"]`);let l=e.querySelector(o),d=(s=l?.getAttribute("src"))!=null?s:"";l?.removeAttribute("src"),setTimeout(()=>{l?.setAttribute("src",d)},0)}r.mode!=="hidden"&&(r.mode="hidden")}})};t.once(B.Events.MANIFEST_LOADED,a),t.once(B.Events.MEDIA_ATTACHED,a)}function dd(e,t,i,a,r,n){let s=document.createElement("track");return s.kind=t,s.label=i,a&&(s.srclang=a),r&&(s.id=r),n&&(s.default=!0),s.track.mode=["subtitles","captions"].includes(t)?"disabled":"hidden",s.setAttribute("data-removeondestroy",""),e.append(s),s.track}function e_(e,t){let i=Array.prototype.find.call(e.querySelectorAll("track"),a=>a.track===t);i?.remove()}function an(e,t,i){var a;return(a=Array.from(e.querySelectorAll("track")).find(r=>r.track.label===t&&r.track.kind===i))==null?void 0:a.track}async function Uh(e,t,i,a){let r=an(e,i,a);return r||(r=dd(e,a,i),r.mode="hidden",await new Promise(n=>setTimeout(()=>n(void 0),0))),r.mode!=="hidden"&&(r.mode="hidden"),[...t].sort((n,s)=>wo(s)-wo(n)).forEach(n=>{var s,o;let l=n.value,d=wo(n);if("endTime"in n&&n.endTime!=null)r?.addCue(new VTTCue(d,n.endTime,a==="chapters"?l:JSON.stringify(l??null)));else{let m=Array.prototype.findIndex.call(r?.cues,v=>v.startTime>=d),p=(s=r?.cues)==null?void 0:s[m],h=p?p.startTime:Number.isFinite(e.duration)?e.duration:Number.MAX_SAFE_INTEGER,c=(o=r?.cues)==null?void 0:o[m-1];c&&(c.endTime=d),r?.addCue(new VTTCue(d,h,a==="chapters"?l:JSON.stringify(l??null)))}}),e.textTracks.dispatchEvent(new Event("change",{bubbles:!0,composed:!0})),r}var ud="cuepoints",Hh=Object.freeze({label:ud});async function Bh(e,t,i=Hh){return Uh(e,t,i.label,"metadata")}var Zo=e=>({time:e.startTime,value:JSON.parse(e.text)});function t_(e,t={label:ud}){let i=an(e,t.label,"metadata");return i!=null&&i.cues?Array.from(i.cues,a=>Zo(a)):[]}function Wh(e,t={label:ud}){var i,a;let r=an(e,t.label,"metadata");if(!((i=r?.activeCues)!=null&&i.length))return;if(r.activeCues.length===1)return Zo(r.activeCues[0]);let{currentTime:n}=e,s=Array.prototype.find.call((a=r.activeCues)!=null?a:[],({startTime:o,endTime:l})=>o<=n&&l>n);return Zo(s||r.activeCues[0])}async function i_(e,t=Hh){return new Promise(i=>{pe(e,"loadstart",async()=>{let a=await Bh(e,[],t);pe(e,"cuechange",()=>{let r=Wh(e);if(r){let n=new CustomEvent("cuepointchange",{composed:!0,bubbles:!0,detail:r});e.dispatchEvent(n)}},{},a),i(a)})})}var cd="chapters",Fh=Object.freeze({label:cd}),zo=e=>({startTime:e.startTime,endTime:e.endTime,value:e.text});async function Kh(e,t,i=Fh){return Uh(e,t,i.label,"chapters")}function a_(e,t={label:cd}){var i;let a=an(e,t.label,"chapters");return(i=a?.cues)!=null&&i.length?Array.from(a.cues,r=>zo(r)):[]}function Vh(e,t={label:cd}){var i,a;let r=an(e,t.label,"chapters");if(!((i=r?.activeCues)!=null&&i.length))return;if(r.activeCues.length===1)return zo(r.activeCues[0]);let{currentTime:n}=e,s=Array.prototype.find.call((a=r.activeCues)!=null?a:[],({startTime:o,endTime:l})=>o<=n&&l>n);return zo(s||r.activeCues[0])}async function r_(e,t=Fh){return new Promise(i=>{pe(e,"loadstart",async()=>{let a=await Kh(e,[],t);pe(e,"cuechange",()=>{let r=Vh(e);if(r){let n=new CustomEvent("chapterchange",{composed:!0,bubbles:!0,detail:r});e.dispatchEvent(n)}},{},a),i(a)})})}function n_(e,t){if(t){let i=t.playingDate;if(i!=null)return new Date(i.getTime()-e.currentTime*1e3)}return typeof e.getStartDate=="function"?e.getStartDate():new Date(NaN)}function s_(e,t){if(t&&t.playingDate)return t.playingDate;if(typeof e.getStartDate=="function"){let i=e.getStartDate();return new Date(i.getTime()+e.currentTime*1e3)}return new Date(NaN)}var Dr={VIDEO:"v",THUMBNAIL:"t",STORYBOARD:"s",DRM:"d"},o_=e=>{if(e===te.VIDEO)return Dr.VIDEO;if(e===te.DRM)return Dr.DRM},l_=(e,t)=>{var i,a;let r=to(e),n=`${r}Token`;return(i=t.tokens)!=null&&i[r]?(a=t.tokens)==null?void 0:a[r]:od(n,t)?t[n]:void 0},Os=(e,t,i,a,r=!1,n=!(s=>(s=globalThis.navigator)==null?void 0:s.onLine)())=>{var s,o;if(n){let y=L("Your device appears to be offline",r),T,f=w.MEDIA_ERR_NETWORK,S=new w(y,f,!1,T);return S.errorCategory=t,S.muxCode=x.NETWORK_OFFLINE,S.data=e,S}let l="status"in e?e.status:e.code,d=Date.now(),m=w.MEDIA_ERR_NETWORK;if(l===200)return;let p=to(t),h=l_(t,i),c=o_(t),[v]=ld((s=i.playbackId)!=null?s:"");if(!l||!v)return;let g=Ia(h);if(h&&!g){let y=L("The {tokenNamePrefix}-token provided is invalid or malformed.",r).format({tokenNamePrefix:p}),T=L("Compact JWT string: {token}",r).format({token:h}),f=new w(y,m,!0,T);return f.errorCategory=t,f.muxCode=x.NETWORK_TOKEN_MALFORMED,f.data=e,f}if(l>=500){let y=new w("",m,a??!0);return y.errorCategory=t,y.muxCode=x.NETWORK_UNKNOWN_ERROR,y}if(l===403)if(g){if(VE(g,d)){let y={timeStyle:"medium",dateStyle:"medium"},T=L("The video’s secured {tokenNamePrefix}-token has expired.",r).format({tokenNamePrefix:p}),f=L("Expired at: {expiredDate}. Current time: {currentDate}.",r).format({expiredDate:new Intl.DateTimeFormat("en",y).format((o=g.exp)!=null?o:0*1e3),currentDate:new Intl.DateTimeFormat("en",y).format(d)}),S=new w(T,m,!0,f);return S.errorCategory=t,S.muxCode=x.NETWORK_TOKEN_EXPIRED,S.data=e,S}if(qE(g,v)){let y=L("The video’s playback ID does not match the one encoded in the {tokenNamePrefix}-token.",r).format({tokenNamePrefix:p}),T=L("Specified playback ID: {playbackId} and the playback ID encoded in the {tokenNamePrefix}-token: {tokenPlaybackId}",r).format({tokenNamePrefix:p,playbackId:v,tokenPlaybackId:g.sub}),f=new w(y,m,!0,T);return f.errorCategory=t,f.muxCode=x.NETWORK_TOKEN_SUB_MISMATCH,f.data=e,f}if(YE(g)){let y=L("The {tokenNamePrefix}-token is formatted with incorrect information.",r).format({tokenNamePrefix:p}),T=L("The {tokenNamePrefix}-token has no aud value. aud value should be {expectedAud}.",r).format({tokenNamePrefix:p,expectedAud:c}),f=new w(y,m,!0,T);return f.errorCategory=t,f.muxCode=x.NETWORK_TOKEN_AUD_MISSING,f.data=e,f}if(GE(g,c)){let y=L("The {tokenNamePrefix}-token is formatted with incorrect information.",r).format({tokenNamePrefix:p}),T=L("The {tokenNamePrefix}-token has an incorrect aud value: {aud}. aud value should be {expectedAud}.",r).format({tokenNamePrefix:p,expectedAud:c,aud:g.aud}),f=new w(y,m,!0,T);return f.errorCategory=t,f.muxCode=x.NETWORK_TOKEN_AUD_MISMATCH,f.data=e,f}}else{let y=L("Authorization error trying to access this {category} URL. If this is a signed URL, you might need to provide a {tokenNamePrefix}-token.",r).format({tokenNamePrefix:p,category:t}),T=L("Specified playback ID: {playbackId}",r).format({playbackId:v}),f=new w(y,m,a??!0,T);return f.errorCategory=t,f.muxCode=x.NETWORK_TOKEN_MISSING,f.data=e,f}if(l===412){let y=L("This playback-id may belong to a live stream that is not currently active or an asset that is not ready.",r),T=L("Specified playback ID: {playbackId}",r).format({playbackId:v}),f=new w(y,m,a??!0,T);return f.errorCategory=t,f.muxCode=x.NETWORK_NOT_READY,f.streamType=i.streamType===G.LIVE?"live":i.streamType===G.ON_DEMAND?"on-demand":"unknown",f.data=e,f}if(l===404){let y=L("This URL or playback-id does not exist. You may have used an Asset ID or an ID from a different resource.",r),T=L("Specified playback ID: {playbackId}",r).format({playbackId:v}),f=new w(y,m,a??!0,T);return f.errorCategory=t,f.muxCode=x.NETWORK_NOT_FOUND,f.data=e,f}if(l===400){let y=L("The URL or playback-id was invalid. You may have used an invalid value as a playback-id."),T=L("Specified playback ID: {playbackId}",r).format({playbackId:v}),f=new w(y,m,a??!0,T);return f.errorCategory=t,f.muxCode=x.NETWORK_INVALID_URL,f.data=e,f}let _=new w("",m,a??!0);return _.errorCategory=t,_.muxCode=x.NETWORK_UNKNOWN_ERROR,_.data=e,_},qu=B.DefaultConfig.capLevelController,qh=class Yh extends qu{constructor(t){super(t)}get levels(){var t;return(t=this.hls.levels)!=null?t:[]}getValidLevels(t){return this.levels.filter((i,a)=>this.isLevelAllowed(i)&&a<=t)}getMaxLevel(t){let i=super.getMaxLevel(t),a=this.getValidLevels(t);if(!a[i])return i;let r=Math.min(a[i].width,a[i].height),n=Yh.minMaxResolution;return r>=n?i:qu.getMaxLevelByMediaSize(a,n*(16/9),n)}};qh.minMaxResolution=720;var d_=qh,u_=d_,Sn={FAIRPLAY:"fairplay",PLAYREADY:"playready",WIDEVINE:"widevine"},c_=e=>{if(e.includes("fps"))return Sn.FAIRPLAY;if(e.includes("playready"))return Sn.PLAYREADY;if(e.includes("widevine"))return Sn.WIDEVINE},h_=e=>{let t=e.split(`
`).find((i,a,r)=>a&&r[a-1].startsWith("#EXT-X-STREAM-INF"));return fetch(t).then(i=>i.status!==200?Promise.reject(i):i.text())},m_=e=>{let t=e.split(`
`).filter(a=>a.startsWith("#EXT-X-SESSION-DATA"));if(!t.length)return{};let i={};for(let a of t){let r=v_(a),n=r["DATA-ID"];n&&(i[n]={...r})}return{sessionData:i}},p_=/([A-Z0-9-]+)="?(.*?)"?(?:,|$)/g;function v_(e){let t=[...e.matchAll(p_)];return Object.fromEntries(t.map(([,i,a])=>[i,a]))}var f_=e=>{var t,i,a;let r=e.split(`
`),n=(i=((t=r.find(d=>d.startsWith("#EXT-X-PLAYLIST-TYPE")))!=null?t:"").split(":")[1])==null?void 0:i.trim(),s=Nh(n),o=Ph(n),l;if(s===G.LIVE){let d=r.find(m=>m.startsWith("#EXT-X-PART-INF"));if(d)l=+d.split(":")[1].split("=")[1]*2;else{let m=r.find(h=>h.startsWith("#EXT-X-TARGETDURATION")),p=(a=m?.split(":"))==null?void 0:a[1];l=+(p??6)*3}}return{streamType:s,targetLiveWindow:o,liveEdgeStartOffset:l}},E_=async(e,t)=>{if(t===Zt.MP4)return{streamType:G.ON_DEMAND,targetLiveWindow:Number.NaN,liveEdgeStartOffset:void 0,sessionData:void 0};if(t===Zt.M3U8){let i=await fetch(e);if(!i.ok)return Promise.reject(i);let a=await i.text(),r=await h_(a);return{...m_(a),...f_(r)}}return console.error(`Media type ${t} is an unrecognized or unsupported type for src ${e}.`),{streamType:void 0,targetLiveWindow:void 0,liveEdgeStartOffset:void 0,sessionData:void 0}},__=async(e,t,i=io({src:e}))=>{var a,r,n,s;let{streamType:o,targetLiveWindow:l,liveEdgeStartOffset:d,sessionData:m}=await E_(e,i),p=m?.["com.apple.hls.chapters"];(p!=null&&p.URI||p!=null&&p.VALUE.toLocaleLowerCase().startsWith("http"))&&hd((a=p.URI)!=null?a:p.VALUE,t),((r=le.get(t))!=null?r:{}).liveEdgeStartOffset=d,((n=le.get(t))!=null?n:{}).targetLiveWindow=l,t.dispatchEvent(new CustomEvent("targetlivewindowchange",{composed:!0,bubbles:!0})),((s=le.get(t))!=null?s:{}).streamType=o,t.dispatchEvent(new CustomEvent("streamtypechange",{composed:!0,bubbles:!0}))},hd=async(e,t)=>{var i,a;try{let r=await fetch(e);if(!r.ok)throw new Error(`Failed to fetch Mux metadata: ${r.status} ${r.statusText}`);let n=await r.json(),s={};if(!((i=n?.[0])!=null&&i.metadata))return;for(let l of n[0].metadata)l.key&&l.value&&(s[l.key]=l.value);((a=le.get(t))!=null?a:{}).metadata=s;let o=new CustomEvent("muxmetadata");t.dispatchEvent(o)}catch(r){console.error(r)}},b_=e=>{var t;let i=e.type,a=Nh(i),r=Ph(i),n,s=!!((t=e.partList)!=null&&t.length);return a===G.LIVE&&(n=s?e.partTarget*2:e.targetduration*3),{streamType:a,targetLiveWindow:r,liveEdgeStartOffset:n,lowLatency:s}},g_=(e,t,i)=>{var a,r,n,s,o,l,d,m;let{streamType:p,targetLiveWindow:h,liveEdgeStartOffset:c,lowLatency:v}=b_(e);if(p===G.LIVE){v?(i.config.backBufferLength=(a=i.userConfig.backBufferLength)!=null?a:4,i.config.maxFragLookUpTolerance=(r=i.userConfig.maxFragLookUpTolerance)!=null?r:.001,i.config.abrBandWidthUpFactor=(n=i.userConfig.abrBandWidthUpFactor)!=null?n:i.config.abrBandWidthFactor):i.config.backBufferLength=(s=i.userConfig.backBufferLength)!=null?s:8;let g=Object.freeze({get length(){return t.seekable.length},start(_){return t.seekable.start(_)},end(_){var y;return _>this.length||_<0||Number.isFinite(t.duration)?t.seekable.end(_):(y=i.liveSyncPosition)!=null?y:t.seekable.end(_)}});((o=le.get(t))!=null?o:{}).seekable=g}((l=le.get(t))!=null?l:{}).liveEdgeStartOffset=c,((d=le.get(t))!=null?d:{}).targetLiveWindow=h,t.dispatchEvent(new CustomEvent("targetlivewindowchange",{composed:!0,bubbles:!0})),((m=le.get(t))!=null?m:{}).streamType=p,t.dispatchEvent(new CustomEvent("streamtypechange",{composed:!0,bubbles:!0}))},Yu,Gu,y_=(Gu=(Yu=globalThis?.navigator)==null?void 0:Yu.userAgent)!=null?Gu:"",ju,Qu,Zu,T_=(Zu=(Qu=(ju=globalThis?.navigator)==null?void 0:ju.userAgentData)==null?void 0:Qu.platform)!=null?Zu:"",A_=y_.toLowerCase().includes("android")||["x11","android"].some(e=>T_.toLowerCase().includes(e)),le=new WeakMap,zt="mux.com",zu,Xu,Gh=(Xu=(zu=B).isSupported)==null?void 0:Xu.call(zu),k_=A_,md=()=>sd.utils.now(),S_=sd.utils.generateUUID,Xo=({playbackId:e,customDomain:t=zt,maxResolution:i,minResolution:a,renditionOrder:r,programStartTime:n,programEndTime:s,assetStartTime:o,assetEndTime:l,playbackToken:d,tokens:{playback:m=d}={},extraSourceParams:p={}}={})=>{if(!e)return;let[h,c=""]=ld(e),v=new URL(`https://stream.${t}/${h}.m3u8${c}`);return m||v.searchParams.has("token")?(v.searchParams.forEach((g,_)=>{_!="token"&&v.searchParams.delete(_)}),m&&v.searchParams.set("token",m)):(i&&v.searchParams.set("max_resolution",i),a&&(v.searchParams.set("min_resolution",a),i&&+i.slice(0,-1)<+a.slice(0,-1)&&console.error("minResolution must be <= maxResolution","minResolution",a,"maxResolution",i)),r&&v.searchParams.set("rendition_order",r),n&&v.searchParams.set("program_start_time",`${n}`),s&&v.searchParams.set("program_end_time",`${s}`),o&&v.searchParams.set("asset_start_time",`${o}`),l&&v.searchParams.set("asset_end_time",`${l}`),Object.entries(p).forEach(([g,_])=>{_!=null&&v.searchParams.set(g,_)})),v.toString()},ao=e=>{if(!e)return;let[t]=e.split("?");return t||void 0},pd=e=>{if(!e||!e.startsWith("https://stream."))return;let[t]=new URL(e).pathname.slice(1).split(/\.m3u8|\//);return t||void 0},w_=e=>{var t,i,a;return(t=e?.metadata)!=null&&t.video_id?e.metadata.video_id:tm(e)&&(a=(i=ao(e.playbackId))!=null?i:pd(e.src))!=null?a:e.src},jh=e=>{var t;return(t=le.get(e))==null?void 0:t.error},I_=e=>{var t;return(t=le.get(e))==null?void 0:t.metadata},Jo=e=>{var t,i;return(i=(t=le.get(e))==null?void 0:t.streamType)!=null?i:G.UNKNOWN},R_=e=>{var t,i;return(i=(t=le.get(e))==null?void 0:t.targetLiveWindow)!=null?i:Number.NaN},vd=e=>{var t,i;return(i=(t=le.get(e))==null?void 0:t.seekable)!=null?i:e.seekable},C_=e=>{var t;let i=(t=le.get(e))==null?void 0:t.liveEdgeStartOffset;if(typeof i!="number")return Number.NaN;let a=vd(e);return a.length?a.end(a.length-1)-i:Number.NaN},fd=.034,D_=(e,t,i=fd)=>Math.abs(e-t)<=i,Qh=(e,t,i=fd)=>e>t||D_(e,t,i),L_=(e,t=fd)=>e.paused&&Qh(e.currentTime,e.duration,t),Zh=(e,t)=>{var i,a,r;if(!t||!e.buffered.length)return;if(e.readyState>2)return!1;let n=t.currentLevel>=0?(a=(i=t.levels)==null?void 0:i[t.currentLevel])==null?void 0:a.details:(r=t.levels.find(p=>!!p.details))==null?void 0:r.details;if(!n||n.live)return;let{fragments:s}=n;if(!(s!=null&&s.length))return;if(e.currentTime<e.duration-(n.targetduration+.5))return!1;let o=s[s.length-1];if(e.currentTime<=o.start)return!1;let l=o.start+o.duration/2,d=e.buffered.start(e.buffered.length-1),m=e.buffered.end(e.buffered.length-1);return l>d&&l<m},zh=(e,t)=>e.ended||e.loop?e.ended:t&&Zh(e,t)?!0:L_(e),M_=(e,t,i)=>{Xh(t,i,e);let{metadata:a={}}=e,{view_session_id:r=S_()}=a,n=w_(e);a.view_session_id=r,a.video_id=n,e.metadata=a;let s=m=>{var p;(p=t.mux)==null||p.emit("hb",{view_drm_type:m})};e.drmTypeCb=s,le.set(t,{retryCount:0});let o=x_(e,t),l=zE(e,t,o);e!=null&&e.muxDataKeepSession&&t!=null&&t.mux&&!t.mux.deleted?o&&t.mux.addHLSJS({hlsjs:o,Hls:o?B:void 0}):H_(e,t,o),B_(e,t,o),i_(t),r_(t);let d=ZE(e,t,o);return{engine:o,setAutoplay:d,setPreload:l}},Xh=(e,t,i)=>{let a=t?.engine;e!=null&&e.mux&&!e.mux.deleted&&(i!=null&&i.muxDataKeepSession?a&&e.mux.removeHLSJS():(e.mux.destroy(),delete e.mux)),a&&(a.detachMedia(),a.destroy()),e&&(e.hasAttribute("src")&&(e.removeAttribute("src"),e.load()),e.removeEventListener("error",am),e.removeEventListener("error",el),e.removeEventListener("durationchange",im),le.delete(e),e.dispatchEvent(new Event("teardown")))};function Jh(e,t){var i;let a=io(e);if(a!==Zt.M3U8)return!0;let r=!a||((i=t.canPlayType(a))!=null?i:!0),{preferPlayback:n}=e,s=n===Nt.MSE,o=n===Nt.NATIVE;return r&&(o||!(Gh&&(s||k_)))}var x_=(e,t)=>{let{debug:i,streamType:a,startTime:r=-1,metadata:n,preferCmcd:s,_hlsConfig:o={}}=e,l=io(e)===Zt.M3U8,d=Jh(e,t);if(l&&!d&&Gh){let m={backBufferLength:30,renderTextTracksNatively:!1,liveDurationInfinity:!0,capLevelToPlayerSize:!0,capLevelOnFPSDrop:!0},p=O_(a),h=N_(e),c=[lr.QUERY,lr.HEADER].includes(s)?{useHeaders:s===lr.HEADER,sessionId:n?.view_session_id,contentId:n?.video_id}:void 0,v=new B({debug:i,startPosition:r,cmcd:c,xhrSetup:(g,_)=>{var y,T;if(s&&s!==lr.QUERY)return;let f=new URL(_);if(!f.searchParams.has("CMCD"))return;let S=((T=(y=f.searchParams.get("CMCD"))==null?void 0:y.split(","))!=null?T:[]).filter(D=>D.startsWith("sid")||D.startsWith("cid")).join(",");f.searchParams.set("CMCD",S),g.open("GET",f)},capLevelController:u_,...m,...p,...h,...o});return v.on(B.Events.MANIFEST_PARSED,async function(g,_){var y,T;let f=(y=_.sessionData)==null?void 0:y["com.apple.hls.chapters"];(f!=null&&f.URI||f!=null&&f.VALUE.toLocaleLowerCase().startsWith("http"))&&hd((T=f?.URI)!=null?T:f?.VALUE,t)}),v}},O_=e=>e===G.LIVE?{backBufferLength:8}:{},N_=e=>{let{tokens:{drm:t}={},playbackId:i,drmTypeCb:a}=e,r=ao(i);return!t||!r?{}:{emeEnabled:!0,drmSystems:{"com.apple.fps":{licenseUrl:wn(e,"fairplay"),serverCertificateUrl:em(e,"fairplay")},"com.widevine.alpha":{licenseUrl:wn(e,"widevine")},"com.microsoft.playready":{licenseUrl:wn(e,"playready")}},requestMediaKeySystemAccessFunc:(n,s)=>(n==="com.widevine.alpha"&&(s=[...s.map(o=>{var l;let d=(l=o.videoCapabilities)==null?void 0:l.map(m=>({...m,robustness:"HW_SECURE_ALL"}));return{...o,videoCapabilities:d}}),...s]),navigator.requestMediaKeySystemAccess(n,s).then(o=>{let l=c_(n);return a?.(l),o}))}},P_=async e=>{let t=await fetch(e);return t.status!==200?Promise.reject(t):await t.arrayBuffer()},$_=async(e,t)=>{let i=await fetch(t,{method:"POST",headers:{"Content-type":"application/octet-stream"},body:e});if(i.status!==200)return Promise.reject(i);let a=await i.arrayBuffer();return new Uint8Array(a)},U_=(e,t)=>{pe(t,"encrypted",async i=>{try{let a=i.initDataType;if(a!=="skd"){console.error(`Received unexpected initialization data type "${a}"`);return}if(!t.mediaKeys){let l=await navigator.requestMediaKeySystemAccess("com.apple.fps",[{initDataTypes:[a],videoCapabilities:[{contentType:"application/vnd.apple.mpegurl",robustness:""}],distinctiveIdentifier:"not-allowed",persistentState:"not-allowed",sessionTypes:["temporary"]}]).then(m=>{var p;return(p=e.drmTypeCb)==null||p.call(e,Sn.FAIRPLAY),m}).catch(()=>{let m=L("Cannot play DRM-protected content with current security configuration on this browser. Try playing in another browser."),p=new w(m,w.MEDIA_ERR_ENCRYPTED,!0);p.errorCategory=te.DRM,p.muxCode=x.ENCRYPTED_UNSUPPORTED_KEY_SYSTEM,tt(t,p)});if(!l)return;let d=await l.createMediaKeys();try{let m=await P_(em(e,"fairplay")).catch(p=>{if(p instanceof Response){let h=Os(p,te.DRM,e);return console.error("mediaError",h?.message,h?.context),h?Promise.reject(h):Promise.reject(new Error("Unexpected error in app cert request"))}return Promise.reject(p)});await d.setServerCertificate(m).catch(()=>{let p=L("Your server certificate failed when attempting to set it. This may be an issue with a no longer valid certificate."),h=new w(p,w.MEDIA_ERR_ENCRYPTED,!0);return h.errorCategory=te.DRM,h.muxCode=x.ENCRYPTED_UPDATE_SERVER_CERT_FAILED,Promise.reject(h)})}catch(m){tt(t,m);return}await t.setMediaKeys(d)}let r=i.initData;if(r==null){console.error(`Could not start encrypted playback due to missing initData in ${i.type} event`);return}let n=t.mediaKeys.createSession();n.addEventListener("keystatuseschange",()=>{n.keyStatuses.forEach(l=>{let d;if(l==="internal-error"){let m=L("The DRM Content Decryption Module system had an internal failure. Try reloading the page, upading your browser, or playing in another browser.");d=new w(m,w.MEDIA_ERR_ENCRYPTED,!0),d.errorCategory=te.DRM,d.muxCode=x.ENCRYPTED_CDM_ERROR}else if(l==="output-restricted"||l==="output-downscaled"){let m=L("DRM playback is being attempted in an environment that is not sufficiently secure. User may see black screen.");d=new w(m,w.MEDIA_ERR_ENCRYPTED,!1),d.errorCategory=te.DRM,d.muxCode=x.ENCRYPTED_OUTPUT_RESTRICTED}d&&tt(t,d)})});let s=await Promise.all([n.generateRequest(a,r).catch(()=>{let l=L("Failed to generate a DRM license request. This may be an issue with the player or your protected content."),d=new w(l,w.MEDIA_ERR_ENCRYPTED,!0);d.errorCategory=te.DRM,d.muxCode=x.ENCRYPTED_GENERATE_REQUEST_FAILED,tt(t,d)}),new Promise(l=>{n.addEventListener("message",d=>{l(d.message)},{once:!0})})]).then(([,l])=>l),o=await $_(s,wn(e,"fairplay")).catch(l=>{if(l instanceof Response){let d=Os(l,te.DRM,e);return console.error("mediaError",d?.message,d?.context),d?Promise.reject(d):Promise.reject(new Error("Unexpected error in license key request"))}return Promise.reject(l)});await n.update(o).catch(()=>{let l=L("Failed to update DRM license. This may be an issue with the player or your protected content."),d=new w(l,w.MEDIA_ERR_ENCRYPTED,!0);return d.errorCategory=te.DRM,d.muxCode=x.ENCRYPTED_UPDATE_LICENSE_FAILED,Promise.reject(d)})}catch(a){tt(t,a);return}})},wn=({playbackId:e,tokens:{drm:t}={},customDomain:i=zt},a)=>{let r=ao(e);return`https://license.${i.toLocaleLowerCase().endsWith(zt)?i:zt}/license/${a}/${r}?token=${t}`},em=({playbackId:e,tokens:{drm:t}={},customDomain:i=zt},a)=>{let r=ao(e);return`https://license.${i.toLocaleLowerCase().endsWith(zt)?i:zt}/appcert/${a}/${r}?token=${t}`},tm=({playbackId:e,src:t,customDomain:i})=>{if(e)return!0;if(typeof t!="string")return!1;let a=window?.location.href,r=new URL(t,a).hostname.toLocaleLowerCase();return r.includes(zt)||!!i&&r.includes(i.toLocaleLowerCase())},H_=(e,t,i)=>{var a;let{envKey:r,disableTracking:n,muxDataSDK:s=sd,muxDataSDKOptions:o={}}=e,l=tm(e);if(!n&&(r||l)){let{playerInitTime:d,playerSoftwareName:m,playerSoftwareVersion:p,beaconCollectionDomain:h,debug:c,disableCookies:v}=e,g={...e.metadata,video_title:((a=e?.metadata)==null?void 0:a.video_title)||void 0},_=y=>typeof y.player_error_code=="string"?!1:typeof e.errorTranslator=="function"?e.errorTranslator(y):y;s.monitor(t,{debug:c,beaconCollectionDomain:h,hlsjs:i,Hls:i?B:void 0,automaticErrorTracking:!1,errorTranslator:_,disableCookies:v,...o,data:{...r?{env_key:r}:{},player_software_name:m,player_software:m,player_software_version:p,player_init_time:d,...g}})}},B_=(e,t,i)=>{var a,r;let n=Jh(e,t),{src:s,customDomain:o=zt}=e,l=()=>{t.ended||!zh(t,i)||(Zh(t,i)?t.currentTime=t.buffered.end(t.buffered.length-1):t.dispatchEvent(new Event("ended")))},d,m,p=()=>{let h=vd(t),c,v;h.length>0&&(c=h.start(0),v=h.end(0)),(m!==v||d!==c)&&t.dispatchEvent(new CustomEvent("seekablechange",{composed:!0})),d=c,m=v};if(pe(t,"durationchange",p),t&&n){let h=io(e);if(typeof s=="string"){if(s.endsWith(".mp4")&&s.includes(o)){let g=pd(s),_=new URL(`https://stream.${o}/${g}/metadata.json`);hd(_.toString(),t)}let c=()=>{if(Jo(t)!==G.LIVE||Number.isFinite(t.duration))return;let g=setInterval(p,1e3);t.addEventListener("teardown",()=>{clearInterval(g)},{once:!0}),pe(t,"durationchange",()=>{Number.isFinite(t.duration)&&clearInterval(g)})},v=async()=>__(s,t,h).then(c).catch(g=>{if(g instanceof Response){let _=Os(g,te.VIDEO,e);if(_){tt(t,_);return}}});if(t.preload==="none"){let g=()=>{v(),t.removeEventListener("loadedmetadata",_)},_=()=>{v(),t.removeEventListener("play",g)};pe(t,"play",g,{once:!0}),pe(t,"loadedmetadata",_,{once:!0})}else v();(a=e.tokens)!=null&&a.drm?U_(e,t):pe(t,"encrypted",()=>{let g=L("Attempting to play DRM-protected content without providing a DRM token."),_=new w(g,w.MEDIA_ERR_ENCRYPTED,!0);_.errorCategory=te.DRM,_.muxCode=x.ENCRYPTED_MISSING_TOKEN,tt(t,_)},{once:!0}),t.setAttribute("src",s),e.startTime&&(((r=le.get(t))!=null?r:{}).startTime=e.startTime,t.addEventListener("durationchange",im,{once:!0}))}else t.removeAttribute("src");t.addEventListener("error",am),t.addEventListener("error",el),t.addEventListener("emptied",()=>{t.querySelectorAll("track[data-removeondestroy]").forEach(c=>{c.remove()})},{once:!0}),pe(t,"pause",l),pe(t,"seeked",l),pe(t,"play",()=>{t.ended||Qh(t.currentTime,t.duration)&&(t.currentTime=t.seekable.length?t.seekable.start(0):0)})}else i&&s?(i.once(B.Events.LEVEL_LOADED,(h,c)=>{g_(c.details,t,i),p(),Jo(t)===G.LIVE&&!Number.isFinite(t.duration)&&(i.on(B.Events.LEVEL_UPDATED,p),pe(t,"durationchange",()=>{Number.isFinite(t.duration)&&i.off(B.Events.LEVELS_UPDATED,p)}))}),i.on(B.Events.ERROR,(h,c)=>{var v,g;let _=W_(c,e);if(_.muxCode===x.NETWORK_NOT_READY){let y=(v=le.get(t))!=null?v:{},T=(g=y.retryCount)!=null?g:0;if(T<6){let f=T===0?5e3:6e4,S=new w(`Retrying in ${f/1e3} seconds...`,_.code,_.fatal);Object.assign(S,_),tt(t,S),setTimeout(()=>{y.retryCount=T+1,c.details==="manifestLoadError"&&c.url&&i.loadSource(c.url)},f);return}else{y.retryCount=0;let f=new w('Try again later or <a href="#" onclick="window.location.reload(); return false;" style="color: #4a90e2;">click here to retry</a>',_.code,_.fatal);Object.assign(f,_),tt(t,f);return}}tt(t,_)}),i.on(B.Events.MANIFEST_LOADED,()=>{let h=le.get(t);h&&h.error&&(h.error=null,h.retryCount=0,t.dispatchEvent(new Event("emptied")),t.dispatchEvent(new Event("loadstart")))}),t.addEventListener("error",el),pe(t,"waiting",l),XE(e,i),JE(t,i),i.attachMedia(t)):console.error("It looks like the video you're trying to play will not work on this system! If possible, try upgrading to the newest versions of your browser or software.")};function im(e){var t;let i=e.target,a=(t=le.get(i))==null?void 0:t.startTime;if(a&&BE(i.seekable,i.duration,a)){let r=i.preload==="auto";r&&(i.preload="none"),i.currentTime=a,r&&(i.preload="auto")}}async function am(e){if(!e.isTrusted)return;e.stopImmediatePropagation();let t=e.target;if(!(t!=null&&t.error))return;let{message:i,code:a}=t.error,r=new w(i,a);if(t.src&&a===w.MEDIA_ERR_SRC_NOT_SUPPORTED&&t.readyState===HTMLMediaElement.HAVE_NOTHING){setTimeout(()=>{var n;let s=(n=jh(t))!=null?n:t.error;s?.code===w.MEDIA_ERR_SRC_NOT_SUPPORTED&&tt(t,r)},500);return}if(t.src&&(a!==w.MEDIA_ERR_DECODE||a!==void 0))try{let{status:n}=await fetch(t.src);r.data={response:{code:n}}}catch{}tt(t,r)}function tt(e,t){var i;t.fatal&&(((i=le.get(e))!=null?i:{}).error=t,e.dispatchEvent(new CustomEvent("error",{detail:t})))}function el(e){var t,i;if(!(e instanceof CustomEvent)||!(e.detail instanceof w))return;let a=e.target,r=e.detail;!r||!r.fatal||(((t=le.get(a))!=null?t:{}).error=r,(i=a.mux)==null||i.emit("error",{player_error_code:r.code,player_error_message:r.message,player_error_context:r.context}))}var W_=(e,t)=>{var i,a,r;console.error("getErrorFromHlsErrorData()",e);let n={[B.ErrorTypes.NETWORK_ERROR]:w.MEDIA_ERR_NETWORK,[B.ErrorTypes.MEDIA_ERROR]:w.MEDIA_ERR_DECODE,[B.ErrorTypes.KEY_SYSTEM_ERROR]:w.MEDIA_ERR_ENCRYPTED},s=m=>[B.ErrorDetails.KEY_SYSTEM_LICENSE_REQUEST_FAILED,B.ErrorDetails.KEY_SYSTEM_SERVER_CERTIFICATE_REQUEST_FAILED].includes(m.details)?w.MEDIA_ERR_NETWORK:n[m.type],o=m=>{if(m.type===B.ErrorTypes.KEY_SYSTEM_ERROR)return te.DRM;if(m.type===B.ErrorTypes.NETWORK_ERROR)return te.VIDEO},l,d=s(e);if(d===w.MEDIA_ERR_NETWORK&&e.response){let m=(i=o(e))!=null?i:te.VIDEO;l=(a=Os(e.response,m,t,e.fatal))!=null?a:new w("",d,e.fatal)}else if(d===w.MEDIA_ERR_ENCRYPTED)if(e.details===B.ErrorDetails.KEY_SYSTEM_NO_CONFIGURED_LICENSE){let m=L("Attempting to play DRM-protected content without providing a DRM token.");l=new w(m,w.MEDIA_ERR_ENCRYPTED,e.fatal),l.errorCategory=te.DRM,l.muxCode=x.ENCRYPTED_MISSING_TOKEN}else if(e.details===B.ErrorDetails.KEY_SYSTEM_NO_ACCESS){let m=L("Cannot play DRM-protected content with current security configuration on this browser. Try playing in another browser.");l=new w(m,w.MEDIA_ERR_ENCRYPTED,e.fatal),l.errorCategory=te.DRM,l.muxCode=x.ENCRYPTED_UNSUPPORTED_KEY_SYSTEM}else if(e.details===B.ErrorDetails.KEY_SYSTEM_NO_SESSION){let m=L("Failed to generate a DRM license request. This may be an issue with the player or your protected content.");l=new w(m,w.MEDIA_ERR_ENCRYPTED,!0),l.errorCategory=te.DRM,l.muxCode=x.ENCRYPTED_GENERATE_REQUEST_FAILED}else if(e.details===B.ErrorDetails.KEY_SYSTEM_SESSION_UPDATE_FAILED){let m=L("Failed to update DRM license. This may be an issue with the player or your protected content.");l=new w(m,w.MEDIA_ERR_ENCRYPTED,e.fatal),l.errorCategory=te.DRM,l.muxCode=x.ENCRYPTED_UPDATE_LICENSE_FAILED}else if(e.details===B.ErrorDetails.KEY_SYSTEM_SERVER_CERTIFICATE_UPDATE_FAILED){let m=L("Your server certificate failed when attempting to set it. This may be an issue with a no longer valid certificate.");l=new w(m,w.MEDIA_ERR_ENCRYPTED,e.fatal),l.errorCategory=te.DRM,l.muxCode=x.ENCRYPTED_UPDATE_SERVER_CERT_FAILED}else if(e.details===B.ErrorDetails.KEY_SYSTEM_STATUS_INTERNAL_ERROR){let m=L("The DRM Content Decryption Module system had an internal failure. Try reloading the page, upading your browser, or playing in another browser.");l=new w(m,w.MEDIA_ERR_ENCRYPTED,e.fatal),l.errorCategory=te.DRM,l.muxCode=x.ENCRYPTED_CDM_ERROR}else if(e.details===B.ErrorDetails.KEY_SYSTEM_STATUS_OUTPUT_RESTRICTED){let m=L("DRM playback is being attempted in an environment that is not sufficiently secure. User may see black screen.");l=new w(m,w.MEDIA_ERR_ENCRYPTED,!1),l.errorCategory=te.DRM,l.muxCode=x.ENCRYPTED_OUTPUT_RESTRICTED}else l=new w(e.error.message,w.MEDIA_ERR_ENCRYPTED,e.fatal),l.errorCategory=te.DRM,l.muxCode=x.ENCRYPTED_ERROR;else l=new w("",d,e.fatal);return l.context||(l.context=`${e.url?`url: ${e.url}
`:""}${e.response&&(e.response.code||e.response.text)?`response: ${e.response.code}, ${e.response.text}
`:""}${e.reason?`failure reason: ${e.reason}
`:""}${e.level?`level: ${e.level}
`:""}${e.parent?`parent stream controller: ${e.parent}
`:""}${e.buffer?`buffer length: ${e.buffer}
`:""}${e.error?`error: ${e.error}
`:""}${e.event?`event: ${e.event}
`:""}${e.err?`error message: ${(r=e.err)==null?void 0:r.message}
`:""}`),l.data=e,l},rm=e=>{throw TypeError(e)},Ed=(e,t,i)=>t.has(e)||rm("Cannot "+i),Re=(e,t,i)=>(Ed(e,t,"read from private field"),i?i.call(e):t.get(e)),st=(e,t,i)=>t.has(e)?rm("Cannot add the same private member more than once"):t instanceof WeakSet?t.add(e):t.set(e,i),Je=(e,t,i,a)=>(Ed(e,t,"write to private field"),t.set(e,i),i),Io=(e,t,i)=>(Ed(e,t,"access private method"),i),F_=()=>{try{return"0.26.1"}catch{}return"UNKNOWN"},K_=F_(),V_=()=>K_,q_=`
<svg xmlns="http://www.w3.org/2000/svg" xml:space="preserve" part="logo" style="fill-rule:evenodd;clip-rule:evenodd;stroke-linejoin:round;stroke-miterlimit:2" viewBox="0 0 1600 500"><g fill="#fff"><path d="M994.287 93.486c-17.121 0-31-13.879-31-31 0-17.121 13.879-31 31-31 17.121 0 31 13.879 31 31 0 17.121-13.879 31-31 31m0-93.486c-34.509 0-62.484 27.976-62.484 62.486v187.511c0 68.943-56.09 125.033-125.032 125.033s-125.03-56.09-125.03-125.033V62.486C681.741 27.976 653.765 0 619.256 0s-62.484 27.976-62.484 62.486v187.511C556.772 387.85 668.921 500 806.771 500c137.851 0 250.001-112.15 250.001-250.003V62.486c0-34.51-27.976-62.486-62.485-62.486M1537.51 468.511c-17.121 0-31-13.879-31-31 0-17.121 13.879-31 31-31 17.121 0 31 13.879 31 31 0 17.121-13.879 31-31 31m-275.883-218.509-143.33 143.329c-24.402 24.402-24.402 63.966 0 88.368 24.402 24.402 63.967 24.402 88.369 0l143.33-143.329 143.328 143.329c24.402 24.4 63.967 24.402 88.369 0 24.403-24.402 24.403-63.966.001-88.368l-143.33-143.329.001-.004 143.329-143.329c24.402-24.402 24.402-63.965 0-88.367s-63.967-24.402-88.369 0L1349.996 161.63 1206.667 18.302c-24.402-24.401-63.967-24.402-88.369 0s-24.402 63.965 0 88.367l143.329 143.329v.004ZM437.511 468.521c-17.121 0-31-13.879-31-31 0-17.121 13.879-31 31-31 17.121 0 31 13.879 31 31 0 17.121-13.879 31-31 31M461.426 4.759C438.078-4.913 411.2.432 393.33 18.303L249.999 161.632 106.669 18.303C88.798.432 61.922-4.913 38.573 4.759 15.224 14.43-.001 37.214-.001 62.488v375.026c0 34.51 27.977 62.486 62.487 62.486 34.51 0 62.486-27.976 62.486-62.486V213.341l80.843 80.844c24.404 24.402 63.965 24.402 88.369 0l80.843-80.844v224.173c0 34.51 27.976 62.486 62.486 62.486s62.486-27.976 62.486-62.486V62.488c0-25.274-15.224-48.058-38.573-57.729" style="fill-rule:nonzero"/></g></svg>`,b={BEACON_COLLECTION_DOMAIN:"beacon-collection-domain",CUSTOM_DOMAIN:"custom-domain",DEBUG:"debug",DISABLE_TRACKING:"disable-tracking",DISABLE_COOKIES:"disable-cookies",DRM_TOKEN:"drm-token",PLAYBACK_TOKEN:"playback-token",ENV_KEY:"env-key",MAX_RESOLUTION:"max-resolution",MIN_RESOLUTION:"min-resolution",RENDITION_ORDER:"rendition-order",PROGRAM_START_TIME:"program-start-time",PROGRAM_END_TIME:"program-end-time",ASSET_START_TIME:"asset-start-time",ASSET_END_TIME:"asset-end-time",METADATA_URL:"metadata-url",PLAYBACK_ID:"playback-id",PLAYER_SOFTWARE_NAME:"player-software-name",PLAYER_SOFTWARE_VERSION:"player-software-version",PLAYER_INIT_TIME:"player-init-time",PREFER_CMCD:"prefer-cmcd",PREFER_PLAYBACK:"prefer-playback",START_TIME:"start-time",STREAM_TYPE:"stream-type",TARGET_LIVE_WINDOW:"target-live-window",LIVE_EDGE_OFFSET:"live-edge-offset",TYPE:"type",LOGO:"logo"},Y_=Object.values(b),Ju=V_(),ec="mux-video",yt,dr,In,ur,Rn,Cn,Dn,Ln,Mn,cr,hr,xn,G_=class extends sn{constructor(){super(),st(this,hr),st(this,yt),st(this,dr),st(this,In),st(this,ur,{}),st(this,Rn,{}),st(this,Cn),st(this,Dn),st(this,Ln),st(this,Mn),st(this,cr,""),Je(this,In,md()),this.nativeEl.addEventListener("muxmetadata",e=>{var t;let i=I_(this.nativeEl),a=(t=this.metadata)!=null?t:{};this.metadata={...i,...a},i?.["com.mux.video.branding"]==="mux-free-plan"&&(Je(this,cr,"default"),this.updateLogo())})}static get NAME(){return ec}static get VERSION(){return Ju}static get observedAttributes(){var e;return[...Y_,...(e=sn.observedAttributes)!=null?e:[]]}static getLogoHTML(e){return!e||e==="false"?"":e==="default"?q_:`<img part="logo" src="${e}" />`}static getTemplateHTML(e={}){var t;return`
      ${sn.getTemplateHTML(e)}
      <style>
        :host {
          position: relative;
        }
        slot[name="logo"] {
          display: flex;
          justify-content: end;
          position: absolute;
          top: 1rem;
          right: 1rem;
          opacity: 0;
          transition: opacity 0.25s ease-in-out;
          z-index: 1;
        }
        slot[name="logo"]:has([part="logo"]) {
          opacity: 1;
        }
        slot[name="logo"] [part="logo"] {
          width: 5rem;
          pointer-events: none;
          user-select: none;
        }
      </style>
      <slot name="logo">
        ${this.getLogoHTML((t=e[b.LOGO])!=null?t:"")}
      </slot>
    `}get preferCmcd(){var e;return(e=this.getAttribute(b.PREFER_CMCD))!=null?e:void 0}set preferCmcd(e){e!==this.preferCmcd&&(e?xs.includes(e)?this.setAttribute(b.PREFER_CMCD,e):console.warn(`Invalid value for preferCmcd. Must be one of ${xs.join()}`):this.removeAttribute(b.PREFER_CMCD))}get playerInitTime(){return this.hasAttribute(b.PLAYER_INIT_TIME)?+this.getAttribute(b.PLAYER_INIT_TIME):Re(this,In)}set playerInitTime(e){e!=this.playerInitTime&&(e==null?this.removeAttribute(b.PLAYER_INIT_TIME):this.setAttribute(b.PLAYER_INIT_TIME,`${+e}`))}get playerSoftwareName(){var e;return(e=Re(this,Ln))!=null?e:ec}set playerSoftwareName(e){Je(this,Ln,e)}get playerSoftwareVersion(){var e;return(e=Re(this,Dn))!=null?e:Ju}set playerSoftwareVersion(e){Je(this,Dn,e)}get _hls(){var e;return(e=Re(this,yt))==null?void 0:e.engine}get mux(){var e;return(e=this.nativeEl)==null?void 0:e.mux}get error(){var e;return(e=jh(this.nativeEl))!=null?e:null}get errorTranslator(){return Re(this,Mn)}set errorTranslator(e){Je(this,Mn,e)}get src(){return this.getAttribute("src")}set src(e){e!==this.src&&(e==null?this.removeAttribute("src"):this.setAttribute("src",e))}get type(){var e;return(e=this.getAttribute(b.TYPE))!=null?e:void 0}set type(e){e!==this.type&&(e?this.setAttribute(b.TYPE,e):this.removeAttribute(b.TYPE))}get preload(){let e=this.getAttribute("preload");return e===""?"auto":["none","metadata","auto"].includes(e)?e:super.preload}set preload(e){e!=this.getAttribute("preload")&&(["","none","metadata","auto"].includes(e)?this.setAttribute("preload",e):this.removeAttribute("preload"))}get debug(){return this.getAttribute(b.DEBUG)!=null}set debug(e){e!==this.debug&&(e?this.setAttribute(b.DEBUG,""):this.removeAttribute(b.DEBUG))}get disableTracking(){return this.hasAttribute(b.DISABLE_TRACKING)}set disableTracking(e){e!==this.disableTracking&&this.toggleAttribute(b.DISABLE_TRACKING,!!e)}get disableCookies(){return this.hasAttribute(b.DISABLE_COOKIES)}set disableCookies(e){e!==this.disableCookies&&(e?this.setAttribute(b.DISABLE_COOKIES,""):this.removeAttribute(b.DISABLE_COOKIES))}get startTime(){let e=this.getAttribute(b.START_TIME);if(e==null)return;let t=+e;return Number.isNaN(t)?void 0:t}set startTime(e){e!==this.startTime&&(e==null?this.removeAttribute(b.START_TIME):this.setAttribute(b.START_TIME,`${e}`))}get playbackId(){var e;return this.hasAttribute(b.PLAYBACK_ID)?this.getAttribute(b.PLAYBACK_ID):(e=pd(this.src))!=null?e:void 0}set playbackId(e){e!==this.playbackId&&(e?this.setAttribute(b.PLAYBACK_ID,e):this.removeAttribute(b.PLAYBACK_ID))}get maxResolution(){var e;return(e=this.getAttribute(b.MAX_RESOLUTION))!=null?e:void 0}set maxResolution(e){e!==this.maxResolution&&(e?this.setAttribute(b.MAX_RESOLUTION,e):this.removeAttribute(b.MAX_RESOLUTION))}get minResolution(){var e;return(e=this.getAttribute(b.MIN_RESOLUTION))!=null?e:void 0}set minResolution(e){e!==this.minResolution&&(e?this.setAttribute(b.MIN_RESOLUTION,e):this.removeAttribute(b.MIN_RESOLUTION))}get renditionOrder(){var e;return(e=this.getAttribute(b.RENDITION_ORDER))!=null?e:void 0}set renditionOrder(e){e!==this.renditionOrder&&(e?this.setAttribute(b.RENDITION_ORDER,e):this.removeAttribute(b.RENDITION_ORDER))}get programStartTime(){let e=this.getAttribute(b.PROGRAM_START_TIME);if(e==null)return;let t=+e;return Number.isNaN(t)?void 0:t}set programStartTime(e){e==null?this.removeAttribute(b.PROGRAM_START_TIME):this.setAttribute(b.PROGRAM_START_TIME,`${e}`)}get programEndTime(){let e=this.getAttribute(b.PROGRAM_END_TIME);if(e==null)return;let t=+e;return Number.isNaN(t)?void 0:t}set programEndTime(e){e==null?this.removeAttribute(b.PROGRAM_END_TIME):this.setAttribute(b.PROGRAM_END_TIME,`${e}`)}get assetStartTime(){let e=this.getAttribute(b.ASSET_START_TIME);if(e==null)return;let t=+e;return Number.isNaN(t)?void 0:t}set assetStartTime(e){e==null?this.removeAttribute(b.ASSET_START_TIME):this.setAttribute(b.ASSET_START_TIME,`${e}`)}get assetEndTime(){let e=this.getAttribute(b.ASSET_END_TIME);if(e==null)return;let t=+e;return Number.isNaN(t)?void 0:t}set assetEndTime(e){e==null?this.removeAttribute(b.ASSET_END_TIME):this.setAttribute(b.ASSET_END_TIME,`${e}`)}get customDomain(){var e;return(e=this.getAttribute(b.CUSTOM_DOMAIN))!=null?e:void 0}set customDomain(e){e!==this.customDomain&&(e?this.setAttribute(b.CUSTOM_DOMAIN,e):this.removeAttribute(b.CUSTOM_DOMAIN))}get drmToken(){var e;return(e=this.getAttribute(b.DRM_TOKEN))!=null?e:void 0}set drmToken(e){e!==this.drmToken&&(e?this.setAttribute(b.DRM_TOKEN,e):this.removeAttribute(b.DRM_TOKEN))}get playbackToken(){var e,t,i,a;if(this.hasAttribute(b.PLAYBACK_TOKEN))return(e=this.getAttribute(b.PLAYBACK_TOKEN))!=null?e:void 0;if(this.hasAttribute(b.PLAYBACK_ID)){let[,r]=ld((t=this.playbackId)!=null?t:"");return(i=new URLSearchParams(r).get("token"))!=null?i:void 0}if(this.src)return(a=new URLSearchParams(this.src).get("token"))!=null?a:void 0}set playbackToken(e){e!==this.playbackToken&&(e?this.setAttribute(b.PLAYBACK_TOKEN,e):this.removeAttribute(b.PLAYBACK_TOKEN))}get tokens(){let e=this.getAttribute(b.PLAYBACK_TOKEN),t=this.getAttribute(b.DRM_TOKEN);return{...Re(this,Rn),...e!=null?{playback:e}:{},...t!=null?{drm:t}:{}}}set tokens(e){Je(this,Rn,e??{})}get ended(){return zh(this.nativeEl,this._hls)}get envKey(){var e;return(e=this.getAttribute(b.ENV_KEY))!=null?e:void 0}set envKey(e){e!==this.envKey&&(e?this.setAttribute(b.ENV_KEY,e):this.removeAttribute(b.ENV_KEY))}get beaconCollectionDomain(){var e;return(e=this.getAttribute(b.BEACON_COLLECTION_DOMAIN))!=null?e:void 0}set beaconCollectionDomain(e){e!==this.beaconCollectionDomain&&(e?this.setAttribute(b.BEACON_COLLECTION_DOMAIN,e):this.removeAttribute(b.BEACON_COLLECTION_DOMAIN))}get streamType(){var e;return(e=this.getAttribute(b.STREAM_TYPE))!=null?e:Jo(this.nativeEl)}set streamType(e){e!==this.streamType&&(e?this.setAttribute(b.STREAM_TYPE,e):this.removeAttribute(b.STREAM_TYPE))}get targetLiveWindow(){return this.hasAttribute(b.TARGET_LIVE_WINDOW)?+this.getAttribute(b.TARGET_LIVE_WINDOW):R_(this.nativeEl)}set targetLiveWindow(e){e!=this.targetLiveWindow&&(e==null?this.removeAttribute(b.TARGET_LIVE_WINDOW):this.setAttribute(b.TARGET_LIVE_WINDOW,`${+e}`))}get liveEdgeStart(){var e,t;if(this.hasAttribute(b.LIVE_EDGE_OFFSET)){let{liveEdgeOffset:i}=this,a=(e=this.nativeEl.seekable.end(0))!=null?e:0,r=(t=this.nativeEl.seekable.start(0))!=null?t:0;return Math.max(r,a-i)}return C_(this.nativeEl)}get liveEdgeOffset(){if(this.hasAttribute(b.LIVE_EDGE_OFFSET))return+this.getAttribute(b.LIVE_EDGE_OFFSET)}set liveEdgeOffset(e){e!=this.liveEdgeOffset&&(e==null?this.removeAttribute(b.LIVE_EDGE_OFFSET):this.setAttribute(b.LIVE_EDGE_OFFSET,`${+e}`))}get seekable(){return vd(this.nativeEl)}async addCuePoints(e){return Bh(this.nativeEl,e)}get activeCuePoint(){return Wh(this.nativeEl)}get cuePoints(){return t_(this.nativeEl)}async addChapters(e){return Kh(this.nativeEl,e)}get activeChapter(){return Vh(this.nativeEl)}get chapters(){return a_(this.nativeEl)}getStartDate(){return n_(this.nativeEl,this._hls)}get currentPdt(){return s_(this.nativeEl,this._hls)}get preferPlayback(){let e=this.getAttribute(b.PREFER_PLAYBACK);if(e===Nt.MSE||e===Nt.NATIVE)return e}set preferPlayback(e){e!==this.preferPlayback&&(e===Nt.MSE||e===Nt.NATIVE?this.setAttribute(b.PREFER_PLAYBACK,e):this.removeAttribute(b.PREFER_PLAYBACK))}get metadata(){return{...this.getAttributeNames().filter(e=>e.startsWith("metadata-")&&![b.METADATA_URL].includes(e)).reduce((e,t)=>{let i=this.getAttribute(t);return i!=null&&(e[t.replace(/^metadata-/,"").replace(/-/g,"_")]=i),e},{}),...Re(this,ur)}}set metadata(e){Je(this,ur,e??{}),this.mux&&this.mux.emit("hb",Re(this,ur))}get _hlsConfig(){return Re(this,Cn)}set _hlsConfig(e){Je(this,Cn,e)}get logo(){var e;return(e=this.getAttribute(b.LOGO))!=null?e:Re(this,cr)}set logo(e){e?this.setAttribute(b.LOGO,e):this.removeAttribute(b.LOGO)}load(){Je(this,yt,M_(this,this.nativeEl,Re(this,yt)))}unload(){Xh(this.nativeEl,Re(this,yt),this),Je(this,yt,void 0)}attributeChangedCallback(e,t,i){var a,r;switch(sn.observedAttributes.includes(e)&&!["src","autoplay","preload"].includes(e)&&super.attributeChangedCallback(e,t,i),e){case b.PLAYER_SOFTWARE_NAME:this.playerSoftwareName=i??void 0;break;case b.PLAYER_SOFTWARE_VERSION:this.playerSoftwareVersion=i??void 0;break;case"src":{let n=!!t,s=!!i;!n&&s?Io(this,hr,xn).call(this):n&&!s?this.unload():n&&s&&(this.unload(),Io(this,hr,xn).call(this));break}case"autoplay":if(i===t)break;(a=Re(this,yt))==null||a.setAutoplay(this.autoplay);break;case"preload":if(i===t)break;(r=Re(this,yt))==null||r.setPreload(i);break;case b.PLAYBACK_ID:this.src=Xo(this);break;case b.DEBUG:{let n=this.debug;this.mux&&console.info("Cannot toggle debug mode of mux data after initialization. Make sure you set all metadata to override before setting the src."),this._hls&&(this._hls.config.debug=n);break}case b.METADATA_URL:i&&fetch(i).then(n=>n.json()).then(n=>this.metadata=n).catch(()=>console.error(`Unable to load or parse metadata JSON from metadata-url ${i}!`));break;case b.STREAM_TYPE:(i==null||i!==t)&&this.dispatchEvent(new CustomEvent("streamtypechange",{composed:!0,bubbles:!0}));break;case b.TARGET_LIVE_WINDOW:(i==null||i!==t)&&this.dispatchEvent(new CustomEvent("targetlivewindowchange",{composed:!0,bubbles:!0,detail:this.targetLiveWindow}));break;case b.LOGO:(i==null||i!==t)&&this.updateLogo();break}}updateLogo(){if(!this.shadowRoot)return;let e=this.shadowRoot.querySelector('slot[name="logo"]');if(!e)return;let t=this.constructor.getLogoHTML(Re(this,cr)||this.logo);e.innerHTML=t}connectedCallback(){var e;(e=super.connectedCallback)==null||e.call(this),this.nativeEl&&this.src&&!Re(this,yt)&&Io(this,hr,xn).call(this)}disconnectedCallback(){this.unload()}handleEvent(e){e.target===this.nativeEl&&this.dispatchEvent(new CustomEvent(e.type,{composed:!0,detail:e.detail}))}};yt=new WeakMap,dr=new WeakMap,In=new WeakMap,ur=new WeakMap,Rn=new WeakMap,Cn=new WeakMap,Dn=new WeakMap,Ln=new WeakMap,Mn=new WeakMap,cr=new WeakMap,hr=new WeakSet,xn=async function(){Re(this,dr)||(await Je(this,dr,Promise.resolve()),Je(this,dr,null),this.load())};const Fi=new WeakMap;class Ro extends Error{}class j_ extends Error{}const Q_=["application/x-mpegURL","application/vnd.apple.mpegurl","audio/mpegurl"],Z_=globalThis.WeakRef?class extends Set{add(e){super.add(new WeakRef(e))}forEach(e){super.forEach(t=>{const i=t.deref();i&&e(i)})}}:Set;function z_(e){globalThis.chrome?.cast?.isAvailable?globalThis.cast?.framework?e():customElements.whenDefined("google-cast-button").then(e):globalThis.__onGCastApiAvailable=()=>{customElements.whenDefined("google-cast-button").then(e)}}function X_(){return globalThis.chrome}function J_(){const e="https://www.gstatic.com/cv/js/sender/v1/cast_sender.js?loadCastFramework=1";if(globalThis.chrome?.cast||document.querySelector(`script[src="${e}"]`))return;const t=document.createElement("script");t.src=e,document.head.append(t)}function Ei(){return globalThis.cast?.framework?.CastContext.getInstance()}function _d(){return Ei()?.getCurrentSession()}function bd(){return _d()?.getSessionObj().media[0]}function eb(e){return new Promise((t,i)=>{bd().editTracksInfo(e,t,i)})}function tb(e){return new Promise((t,i)=>{bd().getStatus(e,t,i)})}function tc(e){return Ei().setOptions({...nm(),...e})}function nm(){return{receiverApplicationId:"CC1AD845",autoJoinPolicy:"origin_scoped",androidReceiverCompatible:!1,language:"en-US",resumeSavedSession:!0}}function ib(e){if(!e)return;const t=/\.([a-zA-Z0-9]+)(?:\?.*)?$/,i=e.match(t);return i?i[1]:null}function ab(e){const t=e.split(`
`),i=[];for(let a=0;a<t.length;a++)if(t[a].trim().startsWith("#EXT-X-STREAM-INF")){const n=t[a+1]?t[a+1].trim():"";n&&!n.startsWith("#")&&i.push(n)}return i}function rb(e){return e.split(`
`).find(a=>!a.trim().startsWith("#")&&a.trim()!=="")}async function nb(e){try{const i=(await fetch(e,{method:"HEAD"})).headers.get("Content-Type");return Q_.some(a=>i===a)}catch(t){return console.error("Error while trying to get the Content-Type of the manifest",t),!1}}async function sb(e){try{const t=await(await fetch(e)).text();let i=t;const a=ab(t);if(a.length>0){const s=new URL(a[0],e).toString();i=await(await fetch(s)).text()}const r=rb(i);return ib(r)}catch(t){console.error("Error while trying to parse the manifest playlist",t);return}}const On=new Z_,ii=new WeakSet;let be;z_(()=>{if(!globalThis.chrome?.cast?.isAvailable){console.debug("chrome.cast.isAvailable",globalThis.chrome?.cast?.isAvailable);return}be||(be=cast.framework,Ei().addEventListener(be.CastContextEventType.CAST_STATE_CHANGED,e=>{On.forEach(t=>Fi.get(t).onCastStateChanged?.(e))}),Ei().addEventListener(be.CastContextEventType.SESSION_STATE_CHANGED,e=>{On.forEach(t=>Fi.get(t).onSessionStateChanged?.(e))}),On.forEach(e=>Fi.get(e).init?.()))});let ic=0;class ob extends EventTarget{#t;#s;#i;#a;#e="disconnected";#r=!1;#o=new Set;#c=new WeakMap;constructor(t){super(),this.#t=t,On.add(this),Fi.set(this,{init:()=>this.#d(),onCastStateChanged:()=>this.#l(),onSessionStateChanged:()=>this.#p(),getCastPlayer:()=>this.#n}),this.#d()}get#n(){if(ii.has(this.#t))return this.#i}get state(){return this.#e}async watchAvailability(t){if(this.#t.disableRemotePlayback)throw new Ro("disableRemotePlayback attribute is present.");return this.#c.set(t,++ic),this.#o.add(t),queueMicrotask(()=>t(this.#m())),ic}async cancelWatchAvailability(t){if(this.#t.disableRemotePlayback)throw new Ro("disableRemotePlayback attribute is present.");t?this.#o.delete(t):this.#o.clear()}async prompt(){if(this.#t.disableRemotePlayback)throw new Ro("disableRemotePlayback attribute is present.");if(!globalThis.chrome?.cast?.isAvailable)throw new j_("The RemotePlayback API is disabled on this platform.");const t=ii.has(this.#t);ii.add(this.#t),tc(this.#t.castOptions),Object.entries(this.#a).forEach(([i,a])=>{this.#i.controller.addEventListener(i,a)});try{await Ei().requestSession()}catch(i){if(t||ii.delete(this.#t),i==="cancel")return;throw new Error(i)}Fi.get(this.#t)?.loadOnPrompt?.()}#h(){ii.has(this.#t)&&(Object.entries(this.#a).forEach(([t,i])=>{this.#i.controller.removeEventListener(t,i)}),ii.delete(this.#t),this.#t.muted=this.#i.isMuted,this.#t.currentTime=this.#i.savedPlayerState.currentTime,this.#i.savedPlayerState.isPaused===!1&&this.#t.play())}#m(){const t=Ei()?.getCastState();return t&&t!=="NO_DEVICES_AVAILABLE"}#l(){const t=Ei().getCastState();if(ii.has(this.#t)&&t==="CONNECTING"&&(this.#e="connecting",this.dispatchEvent(new Event("connecting"))),!this.#r&&t?.includes("CONNECT")){this.#r=!0;for(let i of this.#o)i(!0)}else if(this.#r&&(!t||t==="NO_DEVICES_AVAILABLE")){this.#r=!1;for(let i of this.#o)i(!1)}}async#p(){const{SESSION_RESUMED:t}=be.SessionState;if(Ei().getSessionState()===t&&this.#t.castSrc===bd()?.media.contentId){ii.add(this.#t),Object.entries(this.#a).forEach(([i,a])=>{this.#i.controller.addEventListener(i,a)});try{await tb(new chrome.cast.media.GetStatusRequest)}catch(i){console.error(i)}this.#a[be.RemotePlayerEventType.IS_PAUSED_CHANGED](),this.#a[be.RemotePlayerEventType.PLAYER_STATE_CHANGED]()}}#d(){!be||this.#s||(this.#s=!0,tc(this.#t.castOptions),this.#t.textTracks.addEventListener("change",()=>this.#u()),this.#l(),this.#i=new be.RemotePlayer,new be.RemotePlayerController(this.#i),this.#a={[be.RemotePlayerEventType.IS_CONNECTED_CHANGED]:({value:t})=>{t===!0?(this.#e="connected",this.dispatchEvent(new Event("connect"))):(this.#h(),this.#e="disconnected",this.dispatchEvent(new Event("disconnect")))},[be.RemotePlayerEventType.DURATION_CHANGED]:()=>{this.#t.dispatchEvent(new Event("durationchange"))},[be.RemotePlayerEventType.VOLUME_LEVEL_CHANGED]:()=>{this.#t.dispatchEvent(new Event("volumechange"))},[be.RemotePlayerEventType.IS_MUTED_CHANGED]:()=>{this.#t.dispatchEvent(new Event("volumechange"))},[be.RemotePlayerEventType.CURRENT_TIME_CHANGED]:()=>{this.#n?.isMediaLoaded&&this.#t.dispatchEvent(new Event("timeupdate"))},[be.RemotePlayerEventType.VIDEO_INFO_CHANGED]:()=>{this.#t.dispatchEvent(new Event("resize"))},[be.RemotePlayerEventType.IS_PAUSED_CHANGED]:()=>{this.#t.dispatchEvent(new Event(this.paused?"pause":"play"))},[be.RemotePlayerEventType.PLAYER_STATE_CHANGED]:()=>{this.#n?.playerState!==chrome.cast.media.PlayerState.PAUSED&&this.#t.dispatchEvent(new Event({[chrome.cast.media.PlayerState.PLAYING]:"playing",[chrome.cast.media.PlayerState.BUFFERING]:"waiting",[chrome.cast.media.PlayerState.IDLE]:"emptied"}[this.#n?.playerState]))},[be.RemotePlayerEventType.IS_MEDIA_LOADED_CHANGED]:async()=>{this.#n?.isMediaLoaded&&(await Promise.resolve(),this.#v())}})}#v(){this.#u()}async#u(){if(!this.#n)return;const i=(this.#i.mediaInfo?.tracks??[]).filter(({type:p})=>p===chrome.cast.media.TrackType.TEXT),a=[...this.#t.textTracks].filter(({kind:p})=>p==="subtitles"||p==="captions"),r=i.map(({language:p,name:h,trackId:c})=>{const{mode:v}=a.find(g=>g.language===p&&g.label===h)??{};return v?{mode:v,trackId:c}:!1}).filter(Boolean),s=r.filter(({mode:p})=>p!=="showing").map(({trackId:p})=>p),o=r.find(({mode:p})=>p==="showing"),l=_d()?.getSessionObj().media[0]?.activeTrackIds??[];let d=l;if(l.length&&(d=d.filter(p=>!s.includes(p))),o?.trackId&&(d=[...d,o.trackId]),d=[...new Set(d)],!((p,h)=>p.length===h.length&&p.every(c=>h.includes(c)))(l,d))try{const p=new chrome.cast.media.EditTracksInfoRequest(d);await eb(p)}catch(p){console.error(p)}}}const lb=e=>class extends e{static observedAttributes=[...e.observedAttributes??[],"cast-src","cast-content-type","cast-stream-type","cast-receiver"];#t={paused:!1};#s=nm();#i;#a;get remote(){return this.#a?this.#a:X_()?(this.disableRemotePlayback||J_(),Fi.set(this,{loadOnPrompt:()=>this.#r()}),this.#a=new ob(this)):super.remote}get#e(){return Fi.get(this.remote)?.getCastPlayer?.()}attributeChangedCallback(i,a,r){if(super.attributeChangedCallback(i,a,r),i==="cast-receiver"&&r){this.#s.receiverApplicationId=r;return}if(this.#e)switch(i){case"cast-stream-type":case"cast-src":this.load();break}}async#r(){this.#t.paused=super.paused,super.pause(),this.muted=super.muted;try{await this.load()}catch(i){console.error(i)}}async load(){if(!this.#e)return super.load();const i=new chrome.cast.media.MediaInfo(this.castSrc,this.castContentType);i.customData=this.castCustomData;const a=[...this.querySelectorAll("track")].filter(({kind:o,src:l})=>l&&(o==="subtitles"||o==="captions")),r=[];let n=0;if(a.length&&(i.tracks=a.map(o=>{const l=++n;r.length===0&&o.track.mode==="showing"&&r.push(l);const d=new chrome.cast.media.Track(l,chrome.cast.media.TrackType.TEXT);return d.trackContentId=o.src,d.trackContentType="text/vtt",d.subtype=o.kind==="captions"?chrome.cast.media.TextTrackType.CAPTIONS:chrome.cast.media.TextTrackType.SUBTITLES,d.name=o.label,d.language=o.srclang,d})),this.castStreamType==="live"?i.streamType=chrome.cast.media.StreamType.LIVE:i.streamType=chrome.cast.media.StreamType.BUFFERED,i.metadata=new chrome.cast.media.GenericMediaMetadata,i.metadata.title=this.title,i.metadata.images=[{url:this.poster}],nb(this.castSrc)){const o=await sb(this.castSrc);(o?.includes("m4s")||o?.includes("mp4"))&&(i.hlsSegmentFormat=chrome.cast.media.HlsSegmentFormat.FMP4,i.hlsVideoSegmentFormat=chrome.cast.media.HlsVideoSegmentFormat.FMP4)}const s=new chrome.cast.media.LoadRequest(i);s.currentTime=super.currentTime??0,s.autoplay=!this.#t.paused,s.activeTrackIds=r,await _d()?.loadMedia(s),this.dispatchEvent(new Event("volumechange"))}play(){if(this.#e){this.#e.isPaused&&this.#e.controller?.playOrPause();return}return super.play()}pause(){if(this.#e){this.#e.isPaused||this.#e.controller?.playOrPause();return}super.pause()}get castOptions(){return this.#s}get castReceiver(){return this.getAttribute("cast-receiver")??void 0}set castReceiver(i){this.castReceiver!=i&&this.setAttribute("cast-receiver",`${i}`)}get castSrc(){return this.getAttribute("cast-src")??this.querySelector("source")?.src??this.currentSrc}set castSrc(i){this.castSrc!=i&&this.setAttribute("cast-src",`${i}`)}get castContentType(){return this.getAttribute("cast-content-type")??void 0}set castContentType(i){this.setAttribute("cast-content-type",`${i}`)}get castStreamType(){return this.getAttribute("cast-stream-type")??this.streamType??void 0}set castStreamType(i){this.setAttribute("cast-stream-type",`${i}`)}get castCustomData(){return this.#i}set castCustomData(i){const a=typeof i;if(!["object","undefined"].includes(a)){console.error(`castCustomData must be nullish or an object but value was of type ${a}`);return}this.#i=i}get readyState(){if(this.#e)switch(this.#e.playerState){case chrome.cast.media.PlayerState.IDLE:return 0;case chrome.cast.media.PlayerState.BUFFERING:return 2;default:return 3}return super.readyState}get paused(){return this.#e?this.#e.isPaused:super.paused}get muted(){return this.#e?this.#e?.isMuted:super.muted}set muted(i){if(this.#e){(i&&!this.#e.isMuted||!i&&this.#e.isMuted)&&this.#e.controller?.muteOrUnmute();return}super.muted=i}get volume(){return this.#e?this.#e?.volumeLevel??1:super.volume}set volume(i){if(this.#e){this.#e.volumeLevel=+i,this.#e.controller?.setVolumeLevel();return}super.volume=i}get duration(){return this.#e&&this.#e?.isMediaLoaded?this.#e?.duration??NaN:super.duration}get currentTime(){return this.#e&&this.#e?.isMediaLoaded?this.#e?.currentTime??0:super.currentTime}set currentTime(i){if(this.#e){this.#e.currentTime=i,this.#e.controller?.seek();return}super.currentTime=i}};var sm=e=>{throw TypeError(e)},om=(e,t,i)=>t.has(e)||sm("Cannot "+i),db=(e,t,i)=>(om(e,t,"read from private field"),i?i.call(e):t.get(e)),ub=(e,t,i)=>t.has(e)?sm("Cannot add the same private member more than once"):t instanceof WeakSet?t.add(e):t.set(e,i),cb=(e,t,i,a)=>(om(e,t,"write to private field"),t.set(e,i),i),lm=class{addEventListener(){}removeEventListener(){}dispatchEvent(t){return!0}};if(typeof DocumentFragment>"u"){class e extends lm{}globalThis.DocumentFragment=e}var hb=class extends lm{},mb={get(e){},define(e,t,i){},getName(e){return null},upgrade(e){},whenDefined(e){return Promise.resolve(hb)}},pb={customElements:mb},vb=typeof window>"u"||typeof globalThis.customElements>"u",Co=vb?pb:globalThis,Nn,ac=class extends lb(yv(G_)){constructor(){super(...arguments),ub(this,Nn)}get autoplay(){let e=this.getAttribute("autoplay");return e===null?!1:e===""?!0:e}set autoplay(e){let t=this.autoplay;e!==t&&(e?this.setAttribute("autoplay",typeof e=="string"?e:""):this.removeAttribute("autoplay"))}get muxCastCustomData(){return{mux:{playbackId:this.playbackId,minResolution:this.minResolution,maxResolution:this.maxResolution,renditionOrder:this.renditionOrder,customDomain:this.customDomain,tokens:{drm:this.drmToken},envKey:this.envKey,metadata:this.metadata,disableCookies:this.disableCookies,disableTracking:this.disableTracking,beaconCollectionDomain:this.beaconCollectionDomain,startTime:this.startTime,preferCmcd:this.preferCmcd}}}get castCustomData(){var e;return(e=db(this,Nn))!=null?e:this.muxCastCustomData}set castCustomData(e){cb(this,Nn,e)}};Nn=new WeakMap;Co.customElements.get("mux-video")||(Co.customElements.define("mux-video",ac),Co.MuxVideoElement=ac);const R={MEDIA_PLAY_REQUEST:"mediaplayrequest",MEDIA_PAUSE_REQUEST:"mediapauserequest",MEDIA_MUTE_REQUEST:"mediamuterequest",MEDIA_UNMUTE_REQUEST:"mediaunmuterequest",MEDIA_VOLUME_REQUEST:"mediavolumerequest",MEDIA_SEEK_REQUEST:"mediaseekrequest",MEDIA_AIRPLAY_REQUEST:"mediaairplayrequest",MEDIA_ENTER_FULLSCREEN_REQUEST:"mediaenterfullscreenrequest",MEDIA_EXIT_FULLSCREEN_REQUEST:"mediaexitfullscreenrequest",MEDIA_PREVIEW_REQUEST:"mediapreviewrequest",MEDIA_ENTER_PIP_REQUEST:"mediaenterpiprequest",MEDIA_EXIT_PIP_REQUEST:"mediaexitpiprequest",MEDIA_ENTER_CAST_REQUEST:"mediaentercastrequest",MEDIA_EXIT_CAST_REQUEST:"mediaexitcastrequest",MEDIA_SHOW_TEXT_TRACKS_REQUEST:"mediashowtexttracksrequest",MEDIA_HIDE_TEXT_TRACKS_REQUEST:"mediahidetexttracksrequest",MEDIA_SHOW_SUBTITLES_REQUEST:"mediashowsubtitlesrequest",MEDIA_DISABLE_SUBTITLES_REQUEST:"mediadisablesubtitlesrequest",MEDIA_TOGGLE_SUBTITLES_REQUEST:"mediatogglesubtitlesrequest",MEDIA_PLAYBACK_RATE_REQUEST:"mediaplaybackraterequest",MEDIA_RENDITION_REQUEST:"mediarenditionrequest",MEDIA_AUDIO_TRACK_REQUEST:"mediaaudiotrackrequest",MEDIA_SEEK_TO_LIVE_REQUEST:"mediaseektoliverequest",REGISTER_MEDIA_STATE_RECEIVER:"registermediastatereceiver",UNREGISTER_MEDIA_STATE_RECEIVER:"unregistermediastatereceiver"},q={MEDIA_CHROME_ATTRIBUTES:"mediachromeattributes",MEDIA_CONTROLLER:"mediacontroller"},dm={MEDIA_AIRPLAY_UNAVAILABLE:"mediaAirplayUnavailable",MEDIA_AUDIO_TRACK_ENABLED:"mediaAudioTrackEnabled",MEDIA_AUDIO_TRACK_LIST:"mediaAudioTrackList",MEDIA_AUDIO_TRACK_UNAVAILABLE:"mediaAudioTrackUnavailable",MEDIA_BUFFERED:"mediaBuffered",MEDIA_CAST_UNAVAILABLE:"mediaCastUnavailable",MEDIA_CHAPTERS_CUES:"mediaChaptersCues",MEDIA_CURRENT_TIME:"mediaCurrentTime",MEDIA_DURATION:"mediaDuration",MEDIA_ENDED:"mediaEnded",MEDIA_ERROR:"mediaError",MEDIA_ERROR_CODE:"mediaErrorCode",MEDIA_ERROR_MESSAGE:"mediaErrorMessage",MEDIA_FULLSCREEN_UNAVAILABLE:"mediaFullscreenUnavailable",MEDIA_HAS_PLAYED:"mediaHasPlayed",MEDIA_HEIGHT:"mediaHeight",MEDIA_IS_AIRPLAYING:"mediaIsAirplaying",MEDIA_IS_CASTING:"mediaIsCasting",MEDIA_IS_FULLSCREEN:"mediaIsFullscreen",MEDIA_IS_PIP:"mediaIsPip",MEDIA_LOADING:"mediaLoading",MEDIA_MUTED:"mediaMuted",MEDIA_PAUSED:"mediaPaused",MEDIA_PIP_UNAVAILABLE:"mediaPipUnavailable",MEDIA_PLAYBACK_RATE:"mediaPlaybackRate",MEDIA_PREVIEW_CHAPTER:"mediaPreviewChapter",MEDIA_PREVIEW_COORDS:"mediaPreviewCoords",MEDIA_PREVIEW_IMAGE:"mediaPreviewImage",MEDIA_PREVIEW_TIME:"mediaPreviewTime",MEDIA_RENDITION_LIST:"mediaRenditionList",MEDIA_RENDITION_SELECTED:"mediaRenditionSelected",MEDIA_RENDITION_UNAVAILABLE:"mediaRenditionUnavailable",MEDIA_SEEKABLE:"mediaSeekable",MEDIA_STREAM_TYPE:"mediaStreamType",MEDIA_SUBTITLES_LIST:"mediaSubtitlesList",MEDIA_SUBTITLES_SHOWING:"mediaSubtitlesShowing",MEDIA_TARGET_LIVE_WINDOW:"mediaTargetLiveWindow",MEDIA_TIME_IS_LIVE:"mediaTimeIsLive",MEDIA_VOLUME:"mediaVolume",MEDIA_VOLUME_LEVEL:"mediaVolumeLevel",MEDIA_VOLUME_UNAVAILABLE:"mediaVolumeUnavailable",MEDIA_WIDTH:"mediaWidth"},um=Object.entries(dm),u=um.reduce((e,[t,i])=>(e[t]=i.toLowerCase(),e),{}),fb={USER_INACTIVE_CHANGE:"userinactivechange",BREAKPOINTS_CHANGE:"breakpointchange",BREAKPOINTS_COMPUTED:"breakpointscomputed"},Jt=um.reduce((e,[t,i])=>(e[t]=i.toLowerCase(),e),{...fb});Object.entries(Jt).reduce((e,[t,i])=>{const a=u[t];return a&&(e[i]=a),e},{userinactivechange:"userinactive"});const Eb=Object.entries(u).reduce((e,[t,i])=>{const a=Jt[t];return a&&(e[i]=a),e},{userinactive:"userinactivechange"}),Ut={SUBTITLES:"subtitles",CAPTIONS:"captions",CHAPTERS:"chapters",METADATA:"metadata"},Ra={DISABLED:"disabled",SHOWING:"showing"},rc={MOUSE:"mouse",TOUCH:"touch"},ze={UNAVAILABLE:"unavailable",UNSUPPORTED:"unsupported"},Gt={LIVE:"live",ON_DEMAND:"on-demand",UNKNOWN:"unknown"},_b={FULLSCREEN:"fullscreen"};function bb(e){return e?.map(yb).join(" ")}function gb(e){return e?.split(/\s+/).map(Tb)}function yb(e){if(e){const{id:t,width:i,height:a}=e;return[t,i,a].filter(r=>r!=null).join(":")}}function Tb(e){if(e){const[t,i,a]=e.split(":");return{id:t,width:+i,height:+a}}}function Ab(e){return e?.map(Sb).join(" ")}function kb(e){return e?.split(/\s+/).map(wb)}function Sb(e){if(e){const{id:t,kind:i,language:a,label:r}=e;return[t,i,a,r].filter(n=>n!=null).join(":")}}function wb(e){if(e){const[t,i,a,r]=e.split(":");return{id:t,kind:i,language:a,label:r}}}function Ib(e){return e.replace(/[-_]([a-z])/g,(t,i)=>i.toUpperCase())}function gd(e){return typeof e=="number"&&!Number.isNaN(e)&&Number.isFinite(e)}function cm(e){return typeof e!="string"?!1:!isNaN(e)&&!isNaN(parseFloat(e))}const hm=e=>new Promise(t=>setTimeout(t,e)),nc=[{singular:"hour",plural:"hours"},{singular:"minute",plural:"minutes"},{singular:"second",plural:"seconds"}],Rb=(e,t)=>{const i=e===1?nc[t].singular:nc[t].plural;return`${e} ${i}`},Lr=e=>{if(!gd(e))return"";const t=Math.abs(e),i=t!==e,a=new Date(0,0,0,0,0,t,0);return`${[a.getHours(),a.getMinutes(),a.getSeconds()].map((o,l)=>o&&Rb(o,l)).filter(o=>o).join(", ")}${i?" remaining":""}`};function bi(e,t){let i=!1;e<0&&(i=!0,e=0-e),e=e<0?0:e;let a=Math.floor(e%60),r=Math.floor(e/60%60),n=Math.floor(e/3600);const s=Math.floor(t/60%60),o=Math.floor(t/3600);return(isNaN(e)||e===1/0)&&(n=r=a="0"),n=n>0||o>0?n+":":"",r=((n||s>=10)&&r<10?"0"+r:r)+":",a=a<10?"0"+a:a,(i?"-":"")+n+r+a}const Cb={"Start airplay":"Start airplay","Stop airplay":"Stop airplay",Audio:"Audio",Captions:"Captions","Enable captions":"Enable captions","Disable captions":"Disable captions","Start casting":"Start casting","Stop casting":"Stop casting","Enter fullscreen mode":"Enter fullscreen mode","Exit fullscreen mode":"Exit fullscreen mode",Mute:"Mute",Unmute:"Unmute","Enter picture in picture mode":"Enter picture in picture mode","Exit picture in picture mode":"Exit picture in picture mode",Play:"Play",Pause:"Pause","Playback rate":"Playback rate","Playback rate {playbackRate}":"Playback rate {playbackRate}",Quality:"Quality","Seek backward":"Seek backward","Seek forward":"Seek forward",Settings:"Settings",Auto:"Auto","audio player":"audio player","video player":"video player",volume:"volume",seek:"seek","closed captions":"closed captions","current playback rate":"current playback rate","playback time":"playback time","media loading":"media loading",settings:"settings","audio tracks":"audio tracks",quality:"quality",play:"play",pause:"pause",mute:"mute",unmute:"unmute",live:"live",Off:"Off","start airplay":"start airplay","stop airplay":"stop airplay","start casting":"start casting","stop casting":"stop casting","enter fullscreen mode":"enter fullscreen mode","exit fullscreen mode":"exit fullscreen mode","enter picture in picture mode":"enter picture in picture mode","exit picture in picture mode":"exit picture in picture mode","seek to live":"seek to live","playing live":"playing live","seek back {seekOffset} seconds":"seek back {seekOffset} seconds","seek forward {seekOffset} seconds":"seek forward {seekOffset} seconds","Network Error":"Network Error","Decode Error":"Decode Error","Source Not Supported":"Source Not Supported","Encryption Error":"Encryption Error","A network error caused the media download to fail.":"A network error caused the media download to fail.","A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.":"A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.","An unsupported error occurred. The server or network failed, or your browser does not support this format.":"An unsupported error occurred. The server or network failed, or your browser does not support this format.","The media is encrypted and there are no keys to decrypt it.":"The media is encrypted and there are no keys to decrypt it."};var sc;const Do={en:Cb};let tl=((sc=globalThis.navigator)==null?void 0:sc.language)||"en";const Db=e=>{tl=e},Lb=e=>{var t,i,a;const[r]=tl.split("-");return((t=Do[tl])==null?void 0:t[e])||((i=Do[r])==null?void 0:i[e])||((a=Do.en)==null?void 0:a[e])||e},C=(e,t={})=>Lb(e).replace(/\{(\w+)\}/g,(i,a)=>a in t?String(t[a]):`{${a}}`);let mm=class{addEventListener(){}removeEventListener(){}dispatchEvent(){return!0}};class pm extends mm{}let oc=class extends pm{constructor(){super(...arguments),this.role=null}};class Mb{observe(){}unobserve(){}disconnect(){}}const vm={createElement:function(){return new Fr.HTMLElement},createElementNS:function(){return new Fr.HTMLElement},addEventListener(){},removeEventListener(){},dispatchEvent(e){return!1}},Fr={ResizeObserver:Mb,document:vm,Node:pm,Element:oc,HTMLElement:class extends oc{constructor(){super(...arguments),this.innerHTML=""}get content(){return new Fr.DocumentFragment}},DocumentFragment:class extends mm{},customElements:{get:function(){},define:function(){},whenDefined:function(){}},localStorage:{getItem(e){return null},setItem(e,t){},removeItem(e){}},CustomEvent:function(){},getComputedStyle:function(){},navigator:{languages:[],get userAgent(){return""}},matchMedia(e){return{matches:!1,media:e}},DOMParser:class{parseFromString(t,i){return{body:{textContent:t}}}}},fm=typeof window>"u"||typeof window.customElements>"u",Em=Object.keys(Fr).every(e=>e in globalThis),E=fm&&!Em?Fr:globalThis,ye=fm&&!Em?vm:globalThis.document,lc=new WeakMap,yd=e=>{let t=lc.get(e);return t||lc.set(e,t=new Set),t},_m=new E.ResizeObserver(e=>{for(const t of e)for(const i of yd(t.target))i(t)});function Pa(e,t){yd(e).add(t),_m.observe(e)}function $a(e,t){const i=yd(e);i.delete(t),i.size||_m.unobserve(e)}function Qe(e){const t={};for(const i of e)t[i.name]=i.value;return t}function at(e){var t;return(t=il(e))!=null?t:Fa(e,"media-controller")}function il(e){var t;const{MEDIA_CONTROLLER:i}=q,a=e.getAttribute(i);if(a)return(t=ro(e))==null?void 0:t.getElementById(a)}const bm=(e,t,i=".value")=>{const a=e.querySelector(i);a&&(a.textContent=t)},xb=(e,t)=>{const i=`slot[name="${t}"]`,a=e.shadowRoot.querySelector(i);return a?a.children:[]},gm=(e,t)=>xb(e,t)[0],ti=(e,t)=>!e||!t?!1:e?.contains(t)?!0:ti(e,t.getRootNode().host),Fa=(e,t)=>{if(!e)return null;const i=e.closest(t);return i||Fa(e.getRootNode().host,t)};function Td(e=document){var t;const i=e?.activeElement;return i?(t=Td(i.shadowRoot))!=null?t:i:null}function ro(e){var t;const i=(t=e?.getRootNode)==null?void 0:t.call(e);return i instanceof ShadowRoot||i instanceof Document?i:null}function ym(e,{depth:t=3,checkOpacity:i=!0,checkVisibilityCSS:a=!0}={}){if(e.checkVisibility)return e.checkVisibility({checkOpacity:i,checkVisibilityCSS:a});let r=e;for(;r&&t>0;){const n=getComputedStyle(r);if(i&&n.opacity==="0"||a&&n.visibility==="hidden"||n.display==="none")return!1;r=r.parentElement,t--}return!0}function Ob(e,t,i,a){const r=a.x-i.x,n=a.y-i.y,s=r*r+n*n;if(s===0)return 0;const o=((e-i.x)*r+(t-i.y)*n)/s;return Math.max(0,Math.min(1,o))}function fe(e,t){const i=Nb(e,a=>a===t);return i||Tm(e,t)}function Nb(e,t){var i,a;let r;for(r of(i=e.querySelectorAll("style:not([media])"))!=null?i:[]){let n;try{n=(a=r.sheet)==null?void 0:a.cssRules}catch{continue}for(const s of n??[])if(t(s.selectorText))return s}}function Tm(e,t){var i,a;const r=(i=e.querySelectorAll("style:not([media])"))!=null?i:[],n=r?.[r.length-1];return n?.sheet?(n?.sheet.insertRule(`${t}{}`,n.sheet.cssRules.length),(a=n.sheet.cssRules)==null?void 0:a[n.sheet.cssRules.length-1]):(console.warn("Media Chrome: No style sheet found on style tag of",e),{style:{setProperty:()=>{},removeProperty:()=>"",getPropertyValue:()=>""}})}function ie(e,t,i=Number.NaN){const a=e.getAttribute(t);return a!=null?+a:i}function de(e,t,i){const a=+i;if(i==null||Number.isNaN(a)){e.hasAttribute(t)&&e.removeAttribute(t);return}ie(e,t,void 0)!==a&&e.setAttribute(t,`${a}`)}function F(e,t){return e.hasAttribute(t)}function K(e,t,i){if(i==null){e.hasAttribute(t)&&e.removeAttribute(t);return}F(e,t)!=i&&e.toggleAttribute(t,i)}function ae(e,t,i=null){var a;return(a=e.getAttribute(t))!=null?a:i}function re(e,t,i){if(i==null){e.hasAttribute(t)&&e.removeAttribute(t);return}const a=`${i}`;ae(e,t,void 0)!==a&&e.setAttribute(t,a)}var Am=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},ai=(e,t,i)=>(Am(e,t,"read from private field"),i?i.call(e):t.get(e)),Pb=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},ln=(e,t,i,a)=>(Am(e,t,"write to private field"),t.set(e,i),i),Pe;function $b(e){return`
    <style>
      :host {
        display: var(--media-control-display, var(--media-gesture-receiver-display, inline-block));
        box-sizing: border-box;
      }
    </style>
  `}class no extends E.HTMLElement{constructor(){if(super(),Pb(this,Pe,void 0),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}}static get observedAttributes(){return[q.MEDIA_CONTROLLER,u.MEDIA_PAUSED]}attributeChangedCallback(t,i,a){var r,n,s,o,l;t===q.MEDIA_CONTROLLER&&(i&&((n=(r=ai(this,Pe))==null?void 0:r.unassociateElement)==null||n.call(r,this),ln(this,Pe,null)),a&&this.isConnected&&(ln(this,Pe,(s=this.getRootNode())==null?void 0:s.getElementById(a)),(l=(o=ai(this,Pe))==null?void 0:o.associateElement)==null||l.call(o,this)))}connectedCallback(){var t,i,a,r;this.tabIndex=-1,this.setAttribute("aria-hidden","true"),ln(this,Pe,Ub(this)),this.getAttribute(q.MEDIA_CONTROLLER)&&((i=(t=ai(this,Pe))==null?void 0:t.associateElement)==null||i.call(t,this)),(a=ai(this,Pe))==null||a.addEventListener("pointerdown",this),(r=ai(this,Pe))==null||r.addEventListener("click",this)}disconnectedCallback(){var t,i,a,r;this.getAttribute(q.MEDIA_CONTROLLER)&&((i=(t=ai(this,Pe))==null?void 0:t.unassociateElement)==null||i.call(t,this)),(a=ai(this,Pe))==null||a.removeEventListener("pointerdown",this),(r=ai(this,Pe))==null||r.removeEventListener("click",this),ln(this,Pe,null)}handleEvent(t){var i;const a=(i=t.composedPath())==null?void 0:i[0];if(["video","media-controller"].includes(a?.localName)){if(t.type==="pointerdown")this._pointerType=t.pointerType;else if(t.type==="click"){const{clientX:n,clientY:s}=t,{left:o,top:l,width:d,height:m}=this.getBoundingClientRect(),p=n-o,h=s-l;if(p<0||h<0||p>d||h>m||d===0&&m===0)return;const{pointerType:c=this._pointerType}=t;if(this._pointerType=void 0,c===rc.TOUCH){this.handleTap(t);return}else if(c===rc.MOUSE){this.handleMouseClick(t);return}}}}get mediaPaused(){return F(this,u.MEDIA_PAUSED)}set mediaPaused(t){K(this,u.MEDIA_PAUSED,t)}handleTap(t){}handleMouseClick(t){const i=this.mediaPaused?R.MEDIA_PLAY_REQUEST:R.MEDIA_PAUSE_REQUEST;this.dispatchEvent(new E.CustomEvent(i,{composed:!0,bubbles:!0}))}}Pe=new WeakMap;no.shadowRootOptions={mode:"open"};no.getTemplateHTML=$b;function Ub(e){var t;const i=e.getAttribute(q.MEDIA_CONTROLLER);return i?(t=e.getRootNode())==null?void 0:t.getElementById(i):Fa(e,"media-controller")}E.customElements.get("media-gesture-receiver")||E.customElements.define("media-gesture-receiver",no);var dc=no,Ad=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Ge=(e,t,i)=>(Ad(e,t,"read from private field"),i?i.call(e):t.get(e)),Ve=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},wi=(e,t,i,a)=>(Ad(e,t,"write to private field"),t.set(e,i),i),it=(e,t,i)=>(Ad(e,t,"access private method"),i),Ns,sa,Kr,Aa,Pn,al,km,mr,$n,rl,Sm,nl,wm,Vr,so,oo,kd,Ua,qr;const M={AUDIO:"audio",AUTOHIDE:"autohide",BREAKPOINTS:"breakpoints",GESTURES_DISABLED:"gesturesdisabled",KEYBOARD_CONTROL:"keyboardcontrol",NO_AUTOHIDE:"noautohide",USER_INACTIVE:"userinactive",AUTOHIDE_OVER_CONTROLS:"autohideovercontrols"};function Hb(e){return`
    <style>
      
      :host([${u.MEDIA_IS_FULLSCREEN}]) ::slotted([slot=media]) {
        outline: none;
      }

      :host {
        box-sizing: border-box;
        position: relative;
        display: inline-block;
        line-height: 0;
        background-color: var(--media-background-color, #000);
      }

      :host(:not([${M.AUDIO}])) [part~=layer]:not([part~=media-layer]) {
        position: absolute;
        top: 0;
        left: 0;
        bottom: 0;
        right: 0;
        display: flex;
        flex-flow: column nowrap;
        align-items: start;
        pointer-events: none;
        background: none;
      }

      slot[name=media] {
        display: var(--media-slot-display, contents);
      }

      
      :host([${M.AUDIO}]) slot[name=media] {
        display: var(--media-slot-display, none);
      }

      
      :host([${M.AUDIO}]) [part~=layer][part~=gesture-layer] {
        height: 0;
        display: block;
      }

      
      :host(:not([${M.AUDIO}])[${M.GESTURES_DISABLED}]) ::slotted([slot=gestures-chrome]),
          :host(:not([${M.AUDIO}])[${M.GESTURES_DISABLED}]) media-gesture-receiver[slot=gestures-chrome] {
        display: none;
      }

      
      ::slotted(:not([slot=media]):not([slot=poster]):not(media-loading-indicator):not([role=dialog]):not([hidden])) {
        pointer-events: auto;
      }

      :host(:not([${M.AUDIO}])) *[part~=layer][part~=centered-layer] {
        align-items: center;
        justify-content: center;
      }

      :host(:not([${M.AUDIO}])) ::slotted(media-gesture-receiver[slot=gestures-chrome]),
      :host(:not([${M.AUDIO}])) media-gesture-receiver[slot=gestures-chrome] {
        align-self: stretch;
        flex-grow: 1;
      }

      slot[name=middle-chrome] {
        display: inline;
        flex-grow: 1;
        pointer-events: none;
        background: none;
      }

      
      ::slotted([slot=media]),
      ::slotted([slot=poster]) {
        width: 100%;
        height: 100%;
      }

      
      :host(:not([${M.AUDIO}])) .spacer {
        flex-grow: 1;
      }

      
      :host(:-webkit-full-screen) {
        
        width: 100% !important;
        height: 100% !important;
      }

      
      ::slotted(:not([slot=media]):not([slot=poster]):not([${M.NO_AUTOHIDE}]):not([hidden]):not([role=dialog])) {
        opacity: 1;
        transition: var(--media-control-transition-in, opacity 0.25s);
      }

      
      :host([${M.USER_INACTIVE}]:not([${u.MEDIA_PAUSED}]):not([${u.MEDIA_IS_AIRPLAYING}]):not([${u.MEDIA_IS_CASTING}]):not([${M.AUDIO}])) ::slotted(:not([slot=media]):not([slot=poster]):not([${M.NO_AUTOHIDE}]):not([role=dialog])) {
        opacity: 0;
        transition: var(--media-control-transition-out, opacity 1s);
      }

      :host([${M.USER_INACTIVE}]:not([${M.NO_AUTOHIDE}]):not([${u.MEDIA_PAUSED}]):not([${u.MEDIA_IS_CASTING}]):not([${M.AUDIO}])) ::slotted([slot=media]) {
        cursor: none;
      }

      :host([${M.USER_INACTIVE}][${M.AUTOHIDE_OVER_CONTROLS}]:not([${M.NO_AUTOHIDE}]):not([${u.MEDIA_PAUSED}]):not([${u.MEDIA_IS_CASTING}]):not([${M.AUDIO}])) * {
        --media-cursor: none;
        cursor: none;
      }


      ::slotted(media-control-bar)  {
        align-self: stretch;
      }

      
      :host(:not([${M.AUDIO}])[${u.MEDIA_HAS_PLAYED}]) slot[name=poster] {
        display: none;
      }

      ::slotted([role=dialog]) {
        width: 100%;
        height: 100%;
        align-self: center;
      }

      ::slotted([role=menu]) {
        align-self: end;
      }
    </style>

    <slot name="media" part="layer media-layer"></slot>
    <slot name="poster" part="layer poster-layer"></slot>
    <slot name="gestures-chrome" part="layer gesture-layer">
      <media-gesture-receiver slot="gestures-chrome">
        <template shadowrootmode="${dc.shadowRootOptions.mode}">
          ${dc.getTemplateHTML({})}
        </template>
      </media-gesture-receiver>
    </slot>
    <span part="layer vertical-layer">
      <slot name="top-chrome" part="top chrome"></slot>
      <slot name="middle-chrome" part="middle chrome"></slot>
      <slot name="centered-chrome" part="layer centered-layer center centered chrome"></slot>
      
      <slot part="bottom chrome"></slot>
    </span>
    <slot name="dialog" part="layer dialog-layer"></slot>
  `}const Bb=Object.values(u),Wb="sm:384 md:576 lg:768 xl:960";function Fb(e){Im(e.target,e.contentRect.width)}function Im(e,t){var i;if(!e.isConnected)return;const a=(i=e.getAttribute(M.BREAKPOINTS))!=null?i:Wb,r=Kb(a),n=Vb(r,t);let s=!1;if(Object.keys(r).forEach(o=>{if(n.includes(o)){e.hasAttribute(`breakpoint${o}`)||(e.setAttribute(`breakpoint${o}`,""),s=!0);return}e.hasAttribute(`breakpoint${o}`)&&(e.removeAttribute(`breakpoint${o}`),s=!0)}),s){const o=new CustomEvent(Jt.BREAKPOINTS_CHANGE,{detail:n});e.dispatchEvent(o)}e.breakpointsComputed||(e.breakpointsComputed=!0,e.dispatchEvent(new CustomEvent(Jt.BREAKPOINTS_COMPUTED,{bubbles:!0,composed:!0})))}function Kb(e){const t=e.split(/\s+/);return Object.fromEntries(t.map(i=>i.split(":")))}function Vb(e,t){return Object.keys(e).filter(i=>t>=parseInt(e[i]))}class lo extends E.HTMLElement{constructor(){if(super(),Ve(this,al),Ve(this,rl),Ve(this,nl),Ve(this,Vr),Ve(this,oo),Ve(this,Ua),Ve(this,Ns,0),Ve(this,sa,null),Ve(this,Kr,null),Ve(this,Aa,void 0),this.breakpointsComputed=!1,Ve(this,Pn,new MutationObserver(it(this,al,km).bind(this))),Ve(this,mr,!1),Ve(this,$n,i=>{Ge(this,mr)||(setTimeout(()=>{Fb(i),wi(this,mr,!1)},0),wi(this,mr,!0))}),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const i=Qe(this.attributes),a=this.constructor.getTemplateHTML(i);this.shadowRoot.setHTMLUnsafe?this.shadowRoot.setHTMLUnsafe(a):this.shadowRoot.innerHTML=a}const t=this.querySelector(":scope > slot[slot=media]");t&&t.addEventListener("slotchange",()=>{if(!t.assignedElements({flatten:!0}).length){Ge(this,sa)&&this.mediaUnsetCallback(Ge(this,sa));return}this.handleMediaUpdated(this.media)})}static get observedAttributes(){return[M.AUTOHIDE,M.GESTURES_DISABLED].concat(Bb).filter(t=>![u.MEDIA_RENDITION_LIST,u.MEDIA_AUDIO_TRACK_LIST,u.MEDIA_CHAPTERS_CUES,u.MEDIA_WIDTH,u.MEDIA_HEIGHT,u.MEDIA_ERROR,u.MEDIA_ERROR_MESSAGE].includes(t))}attributeChangedCallback(t,i,a){t.toLowerCase()==M.AUTOHIDE&&(this.autohide=a)}get media(){let t=this.querySelector(":scope > [slot=media]");return t?.nodeName=="SLOT"&&(t=t.assignedElements({flatten:!0})[0]),t}async handleMediaUpdated(t){t&&(wi(this,sa,t),t.localName.includes("-")&&await E.customElements.whenDefined(t.localName),this.mediaSetCallback(t))}connectedCallback(){var t;Ge(this,Pn).observe(this,{childList:!0,subtree:!0}),Pa(this,Ge(this,$n));const i=this.getAttribute(M.AUDIO)!=null,a=C(i?"audio player":"video player");this.setAttribute("role","region"),this.setAttribute("aria-label",a),this.handleMediaUpdated(this.media),this.setAttribute(M.USER_INACTIVE,""),Im(this,this.getBoundingClientRect().width),this.addEventListener("pointerdown",this),this.addEventListener("pointermove",this),this.addEventListener("pointerup",this),this.addEventListener("mouseleave",this),this.addEventListener("keyup",this),(t=E.window)==null||t.addEventListener("mouseup",this)}disconnectedCallback(){var t;Ge(this,Pn).disconnect(),$a(this,Ge(this,$n)),this.media&&this.mediaUnsetCallback(this.media),(t=E.window)==null||t.removeEventListener("mouseup",this)}mediaSetCallback(t){}mediaUnsetCallback(t){wi(this,sa,null)}handleEvent(t){switch(t.type){case"pointerdown":wi(this,Ns,t.timeStamp);break;case"pointermove":it(this,rl,Sm).call(this,t);break;case"pointerup":it(this,nl,wm).call(this,t);break;case"mouseleave":it(this,Vr,so).call(this);break;case"mouseup":this.removeAttribute(M.KEYBOARD_CONTROL);break;case"keyup":it(this,Ua,qr).call(this),this.setAttribute(M.KEYBOARD_CONTROL,"");break}}set autohide(t){const i=Number(t);wi(this,Aa,isNaN(i)?0:i)}get autohide(){return(Ge(this,Aa)===void 0?2:Ge(this,Aa)).toString()}get breakpoints(){return ae(this,M.BREAKPOINTS)}set breakpoints(t){re(this,M.BREAKPOINTS,t)}get audio(){return F(this,M.AUDIO)}set audio(t){K(this,M.AUDIO,t)}get gesturesDisabled(){return F(this,M.GESTURES_DISABLED)}set gesturesDisabled(t){K(this,M.GESTURES_DISABLED,t)}get keyboardControl(){return F(this,M.KEYBOARD_CONTROL)}set keyboardControl(t){K(this,M.KEYBOARD_CONTROL,t)}get noAutohide(){return F(this,M.NO_AUTOHIDE)}set noAutohide(t){K(this,M.NO_AUTOHIDE,t)}get autohideOverControls(){return F(this,M.AUTOHIDE_OVER_CONTROLS)}set autohideOverControls(t){K(this,M.AUTOHIDE_OVER_CONTROLS,t)}get userInteractive(){return F(this,M.USER_INACTIVE)}set userInteractive(t){K(this,M.USER_INACTIVE,t)}}Ns=new WeakMap;sa=new WeakMap;Kr=new WeakMap;Aa=new WeakMap;Pn=new WeakMap;al=new WeakSet;km=function(e){const t=this.media;for(const i of e){if(i.type!=="childList")continue;const a=i.removedNodes;for(const r of a){if(r.slot!="media"||i.target!=this)continue;let n=i.previousSibling&&i.previousSibling.previousElementSibling;if(!n||!t)this.mediaUnsetCallback(r);else{let s=n.slot!=="media";for(;(n=n.previousSibling)!==null;)n.slot=="media"&&(s=!1);s&&this.mediaUnsetCallback(r)}}if(t)for(const r of i.addedNodes)r===t&&this.handleMediaUpdated(t)}};mr=new WeakMap;$n=new WeakMap;rl=new WeakSet;Sm=function(e){if(e.pointerType!=="mouse"&&e.timeStamp-Ge(this,Ns)<250)return;it(this,oo,kd).call(this),clearTimeout(Ge(this,Kr));const t=this.hasAttribute(M.AUTOHIDE_OVER_CONTROLS);([this,this.media].includes(e.target)||t)&&it(this,Ua,qr).call(this)};nl=new WeakSet;wm=function(e){if(e.pointerType==="touch"){const t=!this.hasAttribute(M.USER_INACTIVE);[this,this.media].includes(e.target)&&t?it(this,Vr,so).call(this):it(this,Ua,qr).call(this)}else e.composedPath().some(t=>["media-play-button","media-fullscreen-button"].includes(t?.localName))&&it(this,Ua,qr).call(this)};Vr=new WeakSet;so=function(){if(Ge(this,Aa)<0||this.hasAttribute(M.USER_INACTIVE))return;this.setAttribute(M.USER_INACTIVE,"");const e=new E.CustomEvent(Jt.USER_INACTIVE_CHANGE,{composed:!0,bubbles:!0,detail:!0});this.dispatchEvent(e)};oo=new WeakSet;kd=function(){if(!this.hasAttribute(M.USER_INACTIVE))return;this.removeAttribute(M.USER_INACTIVE);const e=new E.CustomEvent(Jt.USER_INACTIVE_CHANGE,{composed:!0,bubbles:!0,detail:!1});this.dispatchEvent(e)};Ua=new WeakSet;qr=function(){it(this,oo,kd).call(this),clearTimeout(Ge(this,Kr));const e=parseInt(this.autohide);e<0||wi(this,Kr,setTimeout(()=>{it(this,Vr,so).call(this)},e*1e3))};lo.shadowRootOptions={mode:"open"};lo.getTemplateHTML=Hb;E.customElements.get("media-container")||E.customElements.define("media-container",lo);var Rm=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Se=(e,t,i)=>(Rm(e,t,"read from private field"),i?i.call(e):t.get(e)),Za=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},dn=(e,t,i,a)=>(Rm(e,t,"write to private field"),t.set(e,i),i),oa,la,Ps,$i,qt,oi;class Sd{constructor(t,i,{defaultValue:a}={defaultValue:void 0}){Za(this,qt),Za(this,oa,void 0),Za(this,la,void 0),Za(this,Ps,void 0),Za(this,$i,new Set),dn(this,oa,t),dn(this,la,i),dn(this,Ps,new Set(a))}[Symbol.iterator](){return Se(this,qt,oi).values()}get length(){return Se(this,qt,oi).size}get value(){var t;return(t=[...Se(this,qt,oi)].join(" "))!=null?t:""}set value(t){var i;t!==this.value&&(dn(this,$i,new Set),this.add(...(i=t?.split(" "))!=null?i:[]))}toString(){return this.value}item(t){return[...Se(this,qt,oi)][t]}values(){return Se(this,qt,oi).values()}forEach(t,i){Se(this,qt,oi).forEach(t,i)}add(...t){var i,a;t.forEach(r=>Se(this,$i).add(r)),!(this.value===""&&!((i=Se(this,oa))!=null&&i.hasAttribute(`${Se(this,la)}`)))&&((a=Se(this,oa))==null||a.setAttribute(`${Se(this,la)}`,`${this.value}`))}remove(...t){var i;t.forEach(a=>Se(this,$i).delete(a)),(i=Se(this,oa))==null||i.setAttribute(`${Se(this,la)}`,`${this.value}`)}contains(t){return Se(this,qt,oi).has(t)}toggle(t,i){return typeof i<"u"?i?(this.add(t),!0):(this.remove(t),!1):this.contains(t)?(this.remove(t),!1):(this.add(t),!0)}replace(t,i){return this.remove(t),this.add(i),t===i}}oa=new WeakMap;la=new WeakMap;Ps=new WeakMap;$i=new WeakMap;qt=new WeakSet;oi=function(){return Se(this,$i).size?Se(this,$i):Se(this,Ps)};const qb=(e="")=>e.split(/\s+/),Cm=(e="")=>{const[t,i,a]=e.split(":"),r=a?decodeURIComponent(a):void 0;return{kind:t==="cc"?Ut.CAPTIONS:Ut.SUBTITLES,language:i,label:r}},uo=(e="",t={})=>qb(e).map(i=>{const a=Cm(i);return{...t,...a}}),Dm=e=>e?Array.isArray(e)?e.map(t=>typeof t=="string"?Cm(t):t):typeof e=="string"?uo(e):[e]:[],sl=({kind:e,label:t,language:i}={kind:"subtitles"})=>t?`${e==="captions"?"cc":"sb"}:${i}:${encodeURIComponent(t)}`:i,Yr=(e=[])=>Array.prototype.map.call(e,sl).join(" "),Yb=(e,t)=>i=>i[e]===t,Lm=e=>{const t=Object.entries(e).map(([i,a])=>Yb(i,a));return i=>t.every(a=>a(i))},Mr=(e,t=[],i=[])=>{const a=Dm(i).map(Lm),r=n=>a.some(s=>s(n));Array.from(t).filter(r).forEach(n=>{n.mode=e})},co=(e,t=()=>!0)=>{if(!e?.textTracks)return[];const i=typeof t=="function"?t:Lm(t);return Array.from(e.textTracks).filter(i)},Mm=e=>{var t;return!!((t=e.mediaSubtitlesShowing)!=null&&t.length)||e.hasAttribute(u.MEDIA_SUBTITLES_SHOWING)},Gb=e=>{var t;const{media:i,fullscreenElement:a}=e;try{const r=a&&"requestFullscreen"in a?"requestFullscreen":a&&"webkitRequestFullScreen"in a?"webkitRequestFullScreen":void 0;if(r){const n=(t=a[r])==null?void 0:t.call(a);if(n instanceof Promise)return n.catch(()=>{})}else i?.webkitEnterFullscreen?i.webkitEnterFullscreen():i?.requestFullscreen&&i.requestFullscreen()}catch(r){console.error(r)}},uc="exitFullscreen"in ye?"exitFullscreen":"webkitExitFullscreen"in ye?"webkitExitFullscreen":"webkitCancelFullScreen"in ye?"webkitCancelFullScreen":void 0,jb=e=>{var t;const{documentElement:i}=e;if(uc){const a=(t=i?.[uc])==null?void 0:t.call(i);if(a instanceof Promise)return a.catch(()=>{})}},pr="fullscreenElement"in ye?"fullscreenElement":"webkitFullscreenElement"in ye?"webkitFullscreenElement":void 0,Qb=e=>{const{documentElement:t,media:i}=e,a=t?.[pr];return!a&&"webkitDisplayingFullscreen"in i&&"webkitPresentationMode"in i&&i.webkitDisplayingFullscreen&&i.webkitPresentationMode===_b.FULLSCREEN?i:a},Zb=e=>{var t;const{media:i,documentElement:a,fullscreenElement:r=i}=e;if(!i||!a)return!1;const n=Qb(e);if(!n)return!1;if(n===r||n===i)return!0;if(n.localName.includes("-")){let s=n.shadowRoot;if(!(pr in s))return ti(n,r);for(;s?.[pr];){if(s[pr]===r)return!0;s=(t=s[pr])==null?void 0:t.shadowRoot}}return!1},zb="fullscreenEnabled"in ye?"fullscreenEnabled":"webkitFullscreenEnabled"in ye?"webkitFullscreenEnabled":void 0,Xb=e=>{const{documentElement:t,media:i}=e;return!!t?.[zb]||i&&"webkitSupportsFullscreen"in i};let un;const wd=()=>{var e,t;return un||(un=(t=(e=ye)==null?void 0:e.createElement)==null?void 0:t.call(e,"video"),un)},Jb=async(e=wd())=>{if(!e)return!1;const t=e.volume;e.volume=t/2+.1;const i=new AbortController,a=await Promise.race([eg(e,i.signal),tg(e,t)]);return i.abort(),a},eg=(e,t)=>new Promise(i=>{e.addEventListener("volumechange",()=>i(!0),{signal:t})}),tg=async(e,t)=>{for(let i=0;i<10;i++){if(e.volume===t)return!1;await hm(10)}return e.volume!==t},ig=/.*Version\/.*Safari\/.*/.test(E.navigator.userAgent),xm=(e=wd())=>E.matchMedia("(display-mode: standalone)").matches&&ig?!1:typeof e?.requestPictureInPicture=="function",Om=(e=wd())=>Xb({documentElement:ye,media:e}),ag=Om(),rg=xm(),ng=!!E.WebKitPlaybackTargetAvailabilityEvent,sg=!!E.chrome,$s=e=>co(e.media,t=>[Ut.SUBTITLES,Ut.CAPTIONS].includes(t.kind)).sort((t,i)=>t.kind>=i.kind?1:-1),Nm=e=>co(e.media,t=>t.mode===Ra.SHOWING&&[Ut.SUBTITLES,Ut.CAPTIONS].includes(t.kind)),Pm=(e,t)=>{const i=$s(e),a=Nm(e),r=!!a.length;if(i.length){if(t===!1||r&&t!==!0)Mr(Ra.DISABLED,i,a);else if(t===!0||!r&&t!==!1){let n=i[0];const{options:s}=e;if(!s?.noSubtitlesLangPref){const m=globalThis.localStorage.getItem("media-chrome-pref-subtitles-lang"),p=m?[m,...globalThis.navigator.languages]:globalThis.navigator.languages,h=i.filter(c=>p.some(v=>c.language.toLowerCase().startsWith(v.split("-")[0]))).sort((c,v)=>{const g=p.findIndex(y=>c.language.toLowerCase().startsWith(y.split("-")[0])),_=p.findIndex(y=>v.language.toLowerCase().startsWith(y.split("-")[0]));return g-_});h[0]&&(n=h[0])}const{language:o,label:l,kind:d}=n;Mr(Ra.DISABLED,i,a),Mr(Ra.SHOWING,i,[{language:o,label:l,kind:d}])}}},Id=(e,t)=>e===t?!0:e==null||t==null||typeof e!=typeof t?!1:typeof e=="number"&&Number.isNaN(e)&&Number.isNaN(t)?!0:typeof e!="object"?!1:Array.isArray(e)?og(e,t):Object.entries(e).every(([i,a])=>i in t&&Id(a,t[i])),og=(e,t)=>{const i=Array.isArray(e),a=Array.isArray(t);return i!==a?!1:i||a?e.length!==t.length?!1:e.every((r,n)=>Id(r,t[n])):!0},lg=Object.values(Gt);let Us;const dg=Jb().then(e=>(Us=e,Us)),ug=async(...e)=>{await Promise.all(e.filter(t=>t).map(async t=>{if(!("localName"in t&&t instanceof E.HTMLElement))return;const i=t.localName;if(!i.includes("-"))return;const a=E.customElements.get(i);a&&t instanceof a||(await E.customElements.whenDefined(i),E.customElements.upgrade(t))}))},cg=new E.DOMParser,hg=e=>e&&(cg.parseFromString(e,"text/html").body.textContent||e),vr={mediaError:{get(e,t){const{media:i}=e;if(t?.type!=="playing")return i?.error},mediaEvents:["emptied","error","playing"]},mediaErrorCode:{get(e,t){var i;const{media:a}=e;if(t?.type!=="playing")return(i=a?.error)==null?void 0:i.code},mediaEvents:["emptied","error","playing"]},mediaErrorMessage:{get(e,t){var i,a;const{media:r}=e;if(t?.type!=="playing")return(a=(i=r?.error)==null?void 0:i.message)!=null?a:""},mediaEvents:["emptied","error","playing"]},mediaWidth:{get(e){var t;const{media:i}=e;return(t=i?.videoWidth)!=null?t:0},mediaEvents:["resize"]},mediaHeight:{get(e){var t;const{media:i}=e;return(t=i?.videoHeight)!=null?t:0},mediaEvents:["resize"]},mediaPaused:{get(e){var t;const{media:i}=e;return(t=i?.paused)!=null?t:!0},set(e,t){var i;const{media:a}=t;a&&(e?a.pause():(i=a.play())==null||i.catch(()=>{}))},mediaEvents:["play","playing","pause","emptied"]},mediaHasPlayed:{get(e,t){const{media:i}=e;return i?t?t.type==="playing":!i.paused:!1},mediaEvents:["playing","emptied"]},mediaEnded:{get(e){var t;const{media:i}=e;return(t=i?.ended)!=null?t:!1},mediaEvents:["seeked","ended","emptied"]},mediaPlaybackRate:{get(e){var t;const{media:i}=e;return(t=i?.playbackRate)!=null?t:1},set(e,t){const{media:i}=t;i&&Number.isFinite(+e)&&(i.playbackRate=+e)},mediaEvents:["ratechange","loadstart"]},mediaMuted:{get(e){var t;const{media:i}=e;return(t=i?.muted)!=null?t:!1},set(e,t){const{media:i}=t;if(i){try{E.localStorage.setItem("media-chrome-pref-muted",e?"true":"false")}catch(a){console.debug("Error setting muted pref",a)}i.muted=e}},mediaEvents:["volumechange"],stateOwnersUpdateHandlers:[(e,t)=>{const{options:{noMutedPref:i}}=t,{media:a}=t;if(!(!a||a.muted||i))try{const r=E.localStorage.getItem("media-chrome-pref-muted")==="true";vr.mediaMuted.set(r,t),e(r)}catch(r){console.debug("Error getting muted pref",r)}}]},mediaVolume:{get(e){var t;const{media:i}=e;return(t=i?.volume)!=null?t:1},set(e,t){const{media:i}=t;if(i){try{e==null?E.localStorage.removeItem("media-chrome-pref-volume"):E.localStorage.setItem("media-chrome-pref-volume",e.toString())}catch(a){console.debug("Error setting volume pref",a)}Number.isFinite(+e)&&(i.volume=+e)}},mediaEvents:["volumechange"],stateOwnersUpdateHandlers:[(e,t)=>{const{options:{noVolumePref:i}}=t;if(!i)try{const{media:a}=t;if(!a)return;const r=E.localStorage.getItem("media-chrome-pref-volume");if(r==null)return;vr.mediaVolume.set(+r,t),e(+r)}catch(a){console.debug("Error getting volume pref",a)}}]},mediaVolumeLevel:{get(e){const{media:t}=e;return typeof t?.volume>"u"?"high":t.muted||t.volume===0?"off":t.volume<.5?"low":t.volume<.75?"medium":"high"},mediaEvents:["volumechange"]},mediaCurrentTime:{get(e){var t;const{media:i}=e;return(t=i?.currentTime)!=null?t:0},set(e,t){const{media:i}=t;!i||!gd(e)||(i.currentTime=e)},mediaEvents:["timeupdate","loadedmetadata"]},mediaDuration:{get(e){const{media:t,options:{defaultDuration:i}={}}=e;return i&&(!t||!t.duration||Number.isNaN(t.duration)||!Number.isFinite(t.duration))?i:Number.isFinite(t?.duration)?t.duration:Number.NaN},mediaEvents:["durationchange","loadedmetadata","emptied"]},mediaLoading:{get(e){const{media:t}=e;return t?.readyState<3},mediaEvents:["waiting","playing","emptied"]},mediaSeekable:{get(e){var t;const{media:i}=e;if(!((t=i?.seekable)!=null&&t.length))return;const a=i.seekable.start(0),r=i.seekable.end(i.seekable.length-1);if(!(!a&&!r))return[Number(a.toFixed(3)),Number(r.toFixed(3))]},mediaEvents:["loadedmetadata","emptied","progress","seekablechange"]},mediaBuffered:{get(e){var t;const{media:i}=e,a=(t=i?.buffered)!=null?t:[];return Array.from(a).map((r,n)=>[Number(a.start(n).toFixed(3)),Number(a.end(n).toFixed(3))])},mediaEvents:["progress","emptied"]},mediaStreamType:{get(e){const{media:t,options:{defaultStreamType:i}={}}=e,a=[Gt.LIVE,Gt.ON_DEMAND].includes(i)?i:void 0;if(!t)return a;const{streamType:r}=t;if(lg.includes(r))return r===Gt.UNKNOWN?a:r;const n=t.duration;return n===1/0?Gt.LIVE:Number.isFinite(n)?Gt.ON_DEMAND:a},mediaEvents:["emptied","durationchange","loadedmetadata","streamtypechange"]},mediaTargetLiveWindow:{get(e){const{media:t}=e;if(!t)return Number.NaN;const{targetLiveWindow:i}=t,a=vr.mediaStreamType.get(e);return(i==null||Number.isNaN(i))&&a===Gt.LIVE?0:i},mediaEvents:["emptied","durationchange","loadedmetadata","streamtypechange","targetlivewindowchange"]},mediaTimeIsLive:{get(e){const{media:t,options:{liveEdgeOffset:i=10}={}}=e;if(!t)return!1;if(typeof t.liveEdgeStart=="number")return Number.isNaN(t.liveEdgeStart)?!1:t.currentTime>=t.liveEdgeStart;if(!(vr.mediaStreamType.get(e)===Gt.LIVE))return!1;const r=t.seekable;if(!r)return!0;if(!r.length)return!1;const n=r.end(r.length-1)-i;return t.currentTime>=n},mediaEvents:["playing","timeupdate","progress","waiting","emptied"]},mediaSubtitlesList:{get(e){return $s(e).map(({kind:t,label:i,language:a})=>({kind:t,label:i,language:a}))},mediaEvents:["loadstart"],textTracksEvents:["addtrack","removetrack"]},mediaSubtitlesShowing:{get(e){return Nm(e).map(({kind:t,label:i,language:a})=>({kind:t,label:i,language:a}))},mediaEvents:["loadstart"],textTracksEvents:["addtrack","removetrack","change"],stateOwnersUpdateHandlers:[(e,t)=>{var i,a;const{media:r,options:n}=t;if(!r)return;const s=o=>{var l;!n.defaultSubtitles||o&&![Ut.CAPTIONS,Ut.SUBTITLES].includes((l=o?.track)==null?void 0:l.kind)||Pm(t,!0)};return r.addEventListener("loadstart",s),(i=r.textTracks)==null||i.addEventListener("addtrack",s),(a=r.textTracks)==null||a.addEventListener("removetrack",s),()=>{var o,l;r.removeEventListener("loadstart",s),(o=r.textTracks)==null||o.removeEventListener("addtrack",s),(l=r.textTracks)==null||l.removeEventListener("removetrack",s)}}]},mediaChaptersCues:{get(e){var t;const{media:i}=e;if(!i)return[];const[a]=co(i,{kind:Ut.CHAPTERS});return Array.from((t=a?.cues)!=null?t:[]).map(({text:r,startTime:n,endTime:s})=>({text:hg(r),startTime:n,endTime:s}))},mediaEvents:["loadstart","loadedmetadata"],textTracksEvents:["addtrack","removetrack","change"],stateOwnersUpdateHandlers:[(e,t)=>{var i;const{media:a}=t;if(!a)return;const r=a.querySelector('track[kind="chapters"][default][src]'),n=(i=a.shadowRoot)==null?void 0:i.querySelector(':is(video,audio) > track[kind="chapters"][default][src]');return r?.addEventListener("load",e),n?.addEventListener("load",e),()=>{r?.removeEventListener("load",e),n?.removeEventListener("load",e)}}]},mediaIsPip:{get(e){var t,i;const{media:a,documentElement:r}=e;if(!a||!r||!r.pictureInPictureElement)return!1;if(r.pictureInPictureElement===a)return!0;if(r.pictureInPictureElement instanceof HTMLMediaElement)return(t=a.localName)!=null&&t.includes("-")?ti(a,r.pictureInPictureElement):!1;if(r.pictureInPictureElement.localName.includes("-")){let n=r.pictureInPictureElement.shadowRoot;for(;n?.pictureInPictureElement;){if(n.pictureInPictureElement===a)return!0;n=(i=n.pictureInPictureElement)==null?void 0:i.shadowRoot}}return!1},set(e,t){const{media:i}=t;if(i)if(e){if(!ye.pictureInPictureEnabled){console.warn("MediaChrome: Picture-in-picture is not enabled");return}if(!i.requestPictureInPicture){console.warn("MediaChrome: The current media does not support picture-in-picture");return}const a=()=>{console.warn("MediaChrome: The media is not ready for picture-in-picture. It must have a readyState > 0.")};i.requestPictureInPicture().catch(r=>{if(r.code===11){if(!i.src){console.warn("MediaChrome: The media is not ready for picture-in-picture. It must have a src set.");return}if(i.readyState===0&&i.preload==="none"){const n=()=>{i.removeEventListener("loadedmetadata",s),i.preload="none"},s=()=>{i.requestPictureInPicture().catch(a),n()};i.addEventListener("loadedmetadata",s),i.preload="metadata",setTimeout(()=>{i.readyState===0&&a(),n()},1e3)}else throw r}else throw r})}else ye.pictureInPictureElement&&ye.exitPictureInPicture()},mediaEvents:["enterpictureinpicture","leavepictureinpicture"]},mediaRenditionList:{get(e){var t;const{media:i}=e;return[...(t=i?.videoRenditions)!=null?t:[]].map(a=>({...a}))},mediaEvents:["emptied","loadstart"],videoRenditionsEvents:["addrendition","removerendition"]},mediaRenditionSelected:{get(e){var t,i,a;const{media:r}=e;return(a=(i=r?.videoRenditions)==null?void 0:i[(t=r.videoRenditions)==null?void 0:t.selectedIndex])==null?void 0:a.id},set(e,t){const{media:i}=t;if(!i?.videoRenditions){console.warn("MediaController: Rendition selection not supported by this media.");return}const a=e,r=Array.prototype.findIndex.call(i.videoRenditions,n=>n.id==a);i.videoRenditions.selectedIndex!=r&&(i.videoRenditions.selectedIndex=r)},mediaEvents:["emptied"],videoRenditionsEvents:["addrendition","removerendition","change"]},mediaAudioTrackList:{get(e){var t;const{media:i}=e;return[...(t=i?.audioTracks)!=null?t:[]]},mediaEvents:["emptied","loadstart"],audioTracksEvents:["addtrack","removetrack"]},mediaAudioTrackEnabled:{get(e){var t,i;const{media:a}=e;return(i=[...(t=a?.audioTracks)!=null?t:[]].find(r=>r.enabled))==null?void 0:i.id},set(e,t){const{media:i}=t;if(!i?.audioTracks){console.warn("MediaChrome: Audio track selection not supported by this media.");return}const a=e;for(const r of i.audioTracks)r.enabled=a==r.id},mediaEvents:["emptied"],audioTracksEvents:["addtrack","removetrack","change"]},mediaIsFullscreen:{get(e){return Zb(e)},set(e,t){e?Gb(t):jb(t)},rootEvents:["fullscreenchange","webkitfullscreenchange"],mediaEvents:["webkitbeginfullscreen","webkitendfullscreen","webkitpresentationmodechanged"]},mediaIsCasting:{get(e){var t;const{media:i}=e;return!i?.remote||((t=i.remote)==null?void 0:t.state)==="disconnected"?!1:!!i.remote.state},set(e,t){var i,a;const{media:r}=t;if(r&&!(e&&((i=r.remote)==null?void 0:i.state)!=="disconnected")&&!(!e&&((a=r.remote)==null?void 0:a.state)!=="connected")){if(typeof r.remote.prompt!="function"){console.warn("MediaChrome: Casting is not supported in this environment");return}r.remote.prompt().catch(()=>{})}},remoteEvents:["connect","connecting","disconnect"]},mediaIsAirplaying:{get(){return!1},set(e,t){const{media:i}=t;if(i){if(!(i.webkitShowPlaybackTargetPicker&&E.WebKitPlaybackTargetAvailabilityEvent)){console.error("MediaChrome: received a request to select AirPlay but AirPlay is not supported in this environment");return}i.webkitShowPlaybackTargetPicker()}},mediaEvents:["webkitcurrentplaybacktargetiswirelesschanged"]},mediaFullscreenUnavailable:{get(e){const{media:t}=e;if(!ag||!Om(t))return ze.UNSUPPORTED}},mediaPipUnavailable:{get(e){const{media:t}=e;if(!rg||!xm(t))return ze.UNSUPPORTED}},mediaVolumeUnavailable:{get(e){const{media:t}=e;if(Us===!1||t?.volume==null)return ze.UNSUPPORTED},stateOwnersUpdateHandlers:[e=>{Us==null&&dg.then(t=>e(t?void 0:ze.UNSUPPORTED))}]},mediaCastUnavailable:{get(e,{availability:t="not-available"}={}){var i;const{media:a}=e;if(!sg||!((i=a?.remote)!=null&&i.state))return ze.UNSUPPORTED;if(!(t==null||t==="available"))return ze.UNAVAILABLE},stateOwnersUpdateHandlers:[(e,t)=>{var i;const{media:a}=t;return a?(a.disableRemotePlayback||a.hasAttribute("disableremoteplayback")||(i=a?.remote)==null||i.watchAvailability(n=>{e({availability:n?"available":"not-available"})}).catch(n=>{n.name==="NotSupportedError"?e({availability:null}):e({availability:"not-available"})}),()=>{var n;(n=a?.remote)==null||n.cancelWatchAvailability().catch(()=>{})}):void 0}]},mediaAirplayUnavailable:{get(e,t){if(!ng)return ze.UNSUPPORTED;if(t?.availability==="not-available")return ze.UNAVAILABLE},mediaEvents:["webkitplaybacktargetavailabilitychanged"],stateOwnersUpdateHandlers:[(e,t)=>{var i;const{media:a}=t;return a?(a.disableRemotePlayback||a.hasAttribute("disableremoteplayback")||(i=a?.remote)==null||i.watchAvailability(n=>{e({availability:n?"available":"not-available"})}).catch(n=>{n.name==="NotSupportedError"?e({availability:null}):e({availability:"not-available"})}),()=>{var n;(n=a?.remote)==null||n.cancelWatchAvailability().catch(()=>{})}):void 0}]},mediaRenditionUnavailable:{get(e){var t;const{media:i}=e;if(!i?.videoRenditions)return ze.UNSUPPORTED;if(!((t=i.videoRenditions)!=null&&t.length))return ze.UNAVAILABLE},mediaEvents:["emptied","loadstart"],videoRenditionsEvents:["addrendition","removerendition"]},mediaAudioTrackUnavailable:{get(e){var t,i;const{media:a}=e;if(!a?.audioTracks)return ze.UNSUPPORTED;if(((i=(t=a.audioTracks)==null?void 0:t.length)!=null?i:0)<=1)return ze.UNAVAILABLE},mediaEvents:["emptied","loadstart"],audioTracksEvents:["addtrack","removetrack"]}},mg={[R.MEDIA_PREVIEW_REQUEST](e,t,{detail:i}){var a,r,n;const{media:s}=t,o=i??void 0;let l,d;if(s&&o!=null){const[c]=co(s,{kind:Ut.METADATA,label:"thumbnails"}),v=Array.prototype.find.call((a=c?.cues)!=null?a:[],(g,_,y)=>_===0?g.endTime>o:_===y.length-1?g.startTime<=o:g.startTime<=o&&g.endTime>o);if(v){const g=/'^(?:[a-z]+:)?\/\//i.test(v.text)||(r=s?.querySelector('track[label="thumbnails"]'))==null?void 0:r.src,_=new URL(v.text,g);d=new URLSearchParams(_.hash).get("#xywh").split(",").map(T=>+T),l=_.href}}const m=e.mediaDuration.get(t);let h=(n=e.mediaChaptersCues.get(t).find((c,v,g)=>v===g.length-1&&m===c.endTime?c.startTime<=o&&c.endTime>=o:c.startTime<=o&&c.endTime>o))==null?void 0:n.text;return i!=null&&h==null&&(h=""),{mediaPreviewTime:o,mediaPreviewImage:l,mediaPreviewCoords:d,mediaPreviewChapter:h}},[R.MEDIA_PAUSE_REQUEST](e,t){e["mediaPaused"].set(!0,t)},[R.MEDIA_PLAY_REQUEST](e,t){var i,a,r,n;const s="mediaPaused",l=e.mediaStreamType.get(t)===Gt.LIVE,d=!((i=t.options)!=null&&i.noAutoSeekToLive),m=e.mediaTargetLiveWindow.get(t)>0;if(l&&d&&!m){const p=(a=e.mediaSeekable.get(t))==null?void 0:a[1];if(p){const h=(n=(r=t.options)==null?void 0:r.seekToLiveOffset)!=null?n:0,c=p-h;e.mediaCurrentTime.set(c,t)}}e[s].set(!1,t)},[R.MEDIA_PLAYBACK_RATE_REQUEST](e,t,{detail:i}){const a="mediaPlaybackRate",r=i;e[a].set(r,t)},[R.MEDIA_MUTE_REQUEST](e,t){e["mediaMuted"].set(!0,t)},[R.MEDIA_UNMUTE_REQUEST](e,t){const i="mediaMuted";e.mediaVolume.get(t)||e.mediaVolume.set(.25,t),e[i].set(!1,t)},[R.MEDIA_VOLUME_REQUEST](e,t,{detail:i}){const a="mediaVolume",r=i;r&&e.mediaMuted.get(t)&&e.mediaMuted.set(!1,t),e[a].set(r,t)},[R.MEDIA_SEEK_REQUEST](e,t,{detail:i}){const a="mediaCurrentTime",r=i;e[a].set(r,t)},[R.MEDIA_SEEK_TO_LIVE_REQUEST](e,t){var i,a,r;const n="mediaCurrentTime",s=(i=e.mediaSeekable.get(t))==null?void 0:i[1];if(Number.isNaN(Number(s)))return;const o=(r=(a=t.options)==null?void 0:a.seekToLiveOffset)!=null?r:0,l=s-o;e[n].set(l,t)},[R.MEDIA_SHOW_SUBTITLES_REQUEST](e,t,{detail:i}){var a;const{options:r}=t,n=$s(t),s=Dm(i),o=(a=s[0])==null?void 0:a.language;o&&!r.noSubtitlesLangPref&&E.localStorage.setItem("media-chrome-pref-subtitles-lang",o),Mr(Ra.SHOWING,n,s)},[R.MEDIA_DISABLE_SUBTITLES_REQUEST](e,t,{detail:i}){const a=$s(t),r=i??[];Mr(Ra.DISABLED,a,r)},[R.MEDIA_TOGGLE_SUBTITLES_REQUEST](e,t,{detail:i}){Pm(t,i)},[R.MEDIA_RENDITION_REQUEST](e,t,{detail:i}){const a="mediaRenditionSelected",r=i;e[a].set(r,t)},[R.MEDIA_AUDIO_TRACK_REQUEST](e,t,{detail:i}){const a="mediaAudioTrackEnabled",r=i;e[a].set(r,t)},[R.MEDIA_ENTER_PIP_REQUEST](e,t){const i="mediaIsPip";e.mediaIsFullscreen.get(t)&&e.mediaIsFullscreen.set(!1,t),e[i].set(!0,t)},[R.MEDIA_EXIT_PIP_REQUEST](e,t){e["mediaIsPip"].set(!1,t)},[R.MEDIA_ENTER_FULLSCREEN_REQUEST](e,t){const i="mediaIsFullscreen";e.mediaIsPip.get(t)&&e.mediaIsPip.set(!1,t),e[i].set(!0,t)},[R.MEDIA_EXIT_FULLSCREEN_REQUEST](e,t){e["mediaIsFullscreen"].set(!1,t)},[R.MEDIA_ENTER_CAST_REQUEST](e,t){const i="mediaIsCasting";e.mediaIsFullscreen.get(t)&&e.mediaIsFullscreen.set(!1,t),e[i].set(!0,t)},[R.MEDIA_EXIT_CAST_REQUEST](e,t){e["mediaIsCasting"].set(!1,t)},[R.MEDIA_AIRPLAY_REQUEST](e,t){e["mediaIsAirplaying"].set(!0,t)}},pg=({media:e,fullscreenElement:t,documentElement:i,stateMediator:a=vr,requestMap:r=mg,options:n={},monitorStateOwnersOnlyWithSubscriptions:s=!0})=>{const o=[],l={options:{...n}};let d=Object.freeze({mediaPreviewTime:void 0,mediaPreviewImage:void 0,mediaPreviewCoords:void 0,mediaPreviewChapter:void 0});const m=g=>{g!=null&&(Id(g,d)||(d=Object.freeze({...d,...g}),o.forEach(_=>_(d))))},p=()=>{const g=Object.entries(a).reduce((_,[y,{get:T}])=>(_[y]=T(l),_),{});m(g)},h={};let c;const v=async(g,_)=>{var y,T,f,S,D,O,H,Y,Q,W,P,De,He,Be,ce,xe;const ft=!!c;if(c={...l,...c??{},...g},ft)return;await ug(...Object.values(g));const Oe=o.length>0&&_===0&&s,rt=l.media!==c.media,Et=((y=l.media)==null?void 0:y.textTracks)!==((T=c.media)==null?void 0:T.textTracks),Ne=((f=l.media)==null?void 0:f.videoRenditions)!==((S=c.media)==null?void 0:S.videoRenditions),We=((D=l.media)==null?void 0:D.audioTracks)!==((O=c.media)==null?void 0:O.audioTracks),Ze=((H=l.media)==null?void 0:H.remote)!==((Y=c.media)==null?void 0:Y.remote),zi=l.documentElement!==c.documentElement,nn=!!l.media&&(rt||Oe),Tu=!!((Q=l.media)!=null&&Q.textTracks)&&(Et||Oe),Au=!!((W=l.media)!=null&&W.videoRenditions)&&(Ne||Oe),ku=!!((P=l.media)!=null&&P.audioTracks)&&(We||Oe),Su=!!((De=l.media)!=null&&De.remote)&&(Ze||Oe),wu=!!l.documentElement&&(zi||Oe),Iu=nn||Tu||Au||ku||Su||wu,Xi=o.length===0&&_===1&&s,Ru=!!c.media&&(rt||Xi),Cu=!!((He=c.media)!=null&&He.textTracks)&&(Et||Xi),Du=!!((Be=c.media)!=null&&Be.videoRenditions)&&(Ne||Xi),Lu=!!((ce=c.media)!=null&&ce.audioTracks)&&(We||Xi),Mu=!!((xe=c.media)!=null&&xe.remote)&&(Ze||Xi),xu=!!c.documentElement&&(zi||Xi),Ou=Ru||Cu||Du||Lu||Mu||xu;if(!(Iu||Ou)){Object.entries(c).forEach(([Z,Ga])=>{l[Z]=Ga}),p(),c=void 0;return}Object.entries(a).forEach(([Z,{get:Ga,mediaEvents:mv=[],textTracksEvents:pv=[],videoRenditionsEvents:vv=[],audioTracksEvents:fv=[],remoteEvents:Ev=[],rootEvents:_v=[],stateOwnersUpdateHandlers:bv=[]}])=>{h[Z]||(h[Z]={});const Fe=ue=>{const Ke=Ga(l,ue);m({[Z]:Ke})};let Te;Te=h[Z].mediaEvents,mv.forEach(ue=>{Te&&nn&&(l.media.removeEventListener(ue,Te),h[Z].mediaEvents=void 0),Ru&&(c.media.addEventListener(ue,Fe),h[Z].mediaEvents=Fe)}),Te=h[Z].textTracksEvents,pv.forEach(ue=>{var Ke,nt;Te&&Tu&&((Ke=l.media.textTracks)==null||Ke.removeEventListener(ue,Te),h[Z].textTracksEvents=void 0),Cu&&((nt=c.media.textTracks)==null||nt.addEventListener(ue,Fe),h[Z].textTracksEvents=Fe)}),Te=h[Z].videoRenditionsEvents,vv.forEach(ue=>{var Ke,nt;Te&&Au&&((Ke=l.media.videoRenditions)==null||Ke.removeEventListener(ue,Te),h[Z].videoRenditionsEvents=void 0),Du&&((nt=c.media.videoRenditions)==null||nt.addEventListener(ue,Fe),h[Z].videoRenditionsEvents=Fe)}),Te=h[Z].audioTracksEvents,fv.forEach(ue=>{var Ke,nt;Te&&ku&&((Ke=l.media.audioTracks)==null||Ke.removeEventListener(ue,Te),h[Z].audioTracksEvents=void 0),Lu&&((nt=c.media.audioTracks)==null||nt.addEventListener(ue,Fe),h[Z].audioTracksEvents=Fe)}),Te=h[Z].remoteEvents,Ev.forEach(ue=>{var Ke,nt;Te&&Su&&((Ke=l.media.remote)==null||Ke.removeEventListener(ue,Te),h[Z].remoteEvents=void 0),Mu&&((nt=c.media.remote)==null||nt.addEventListener(ue,Fe),h[Z].remoteEvents=Fe)}),Te=h[Z].rootEvents,_v.forEach(ue=>{Te&&wu&&(l.documentElement.removeEventListener(ue,Te),h[Z].rootEvents=void 0),xu&&(c.documentElement.addEventListener(ue,Fe),h[Z].rootEvents=Fe)});const Nu=h[Z].stateOwnersUpdateHandlers;bv.forEach(ue=>{Nu&&Iu&&Nu(),Ou&&(h[Z].stateOwnersUpdateHandlers=ue(Fe,c))})}),Object.entries(c).forEach(([Z,Ga])=>{l[Z]=Ga}),p(),c=void 0};return v({media:e,fullscreenElement:t,documentElement:i,options:n}),{dispatch(g){const{type:_,detail:y}=g;if(r[_]&&d.mediaErrorCode==null){m(r[_](a,l,g));return}_==="mediaelementchangerequest"?v({media:y}):_==="fullscreenelementchangerequest"?v({fullscreenElement:y}):_==="documentelementchangerequest"?v({documentElement:y}):_==="optionschangerequest"&&Object.entries(y??{}).forEach(([T,f])=>{l.options[T]=f})},getState(){return d},subscribe(g){return v({},o.length+1),o.push(g),g(d),()=>{const _=o.indexOf(g);_>=0&&(v({},o.length-1),o.splice(_,1))}}}};var Rd=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},U=(e,t,i)=>(Rd(e,t,"read from private field"),i?i.call(e):t.get(e)),Ft=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},ri=(e,t,i,a)=>(Rd(e,t,"write to private field"),t.set(e,i),i),vi=(e,t,i)=>(Rd(e,t,"access private method"),i),Ui,fr,X,Er,Tt,Un,Hn,ol,Ha,Gr,Bn,ll;const $m=["ArrowLeft","ArrowRight","Enter"," ","f","m","k","c"],cc=10,I={DEFAULT_SUBTITLES:"defaultsubtitles",DEFAULT_STREAM_TYPE:"defaultstreamtype",DEFAULT_DURATION:"defaultduration",FULLSCREEN_ELEMENT:"fullscreenelement",HOTKEYS:"hotkeys",KEYS_USED:"keysused",LIVE_EDGE_OFFSET:"liveedgeoffset",SEEK_TO_LIVE_OFFSET:"seektoliveoffset",NO_AUTO_SEEK_TO_LIVE:"noautoseektolive",NO_HOTKEYS:"nohotkeys",NO_VOLUME_PREF:"novolumepref",NO_SUBTITLES_LANG_PREF:"nosubtitleslangpref",NO_DEFAULT_STORE:"nodefaultstore",KEYBOARD_FORWARD_SEEK_OFFSET:"keyboardforwardseekoffset",KEYBOARD_BACKWARD_SEEK_OFFSET:"keyboardbackwardseekoffset",LANG:"lang"};class Um extends lo{constructor(){super(),Ft(this,Hn),Ft(this,Ha),Ft(this,Bn),this.mediaStateReceivers=[],this.associatedElementSubscriptions=new Map,Ft(this,Ui,new Sd(this,I.HOTKEYS)),Ft(this,fr,void 0),Ft(this,X,void 0),Ft(this,Er,void 0),Ft(this,Tt,void 0),Ft(this,Un,i=>{var a;(a=U(this,X))==null||a.dispatch(i)}),this.associateElement(this);let t={};ri(this,Er,i=>{Object.entries(i).forEach(([a,r])=>{if(a in t&&t[a]===r)return;this.propagateMediaState(a,r);const n=a.toLowerCase(),s=new E.CustomEvent(Eb[n],{composed:!0,detail:r});this.dispatchEvent(s)}),t=i}),this.enableHotkeys()}static get observedAttributes(){return super.observedAttributes.concat(I.NO_HOTKEYS,I.HOTKEYS,I.DEFAULT_STREAM_TYPE,I.DEFAULT_SUBTITLES,I.DEFAULT_DURATION,I.LANG)}get mediaStore(){return U(this,X)}set mediaStore(t){var i,a;if(U(this,X)&&((i=U(this,Tt))==null||i.call(this),ri(this,Tt,void 0)),ri(this,X,t),!U(this,X)&&!this.hasAttribute(I.NO_DEFAULT_STORE)){vi(this,Hn,ol).call(this);return}ri(this,Tt,(a=U(this,X))==null?void 0:a.subscribe(U(this,Er)))}get fullscreenElement(){var t;return(t=U(this,fr))!=null?t:this}set fullscreenElement(t){var i;this.hasAttribute(I.FULLSCREEN_ELEMENT)&&this.removeAttribute(I.FULLSCREEN_ELEMENT),ri(this,fr,t),(i=U(this,X))==null||i.dispatch({type:"fullscreenelementchangerequest",detail:this.fullscreenElement})}get defaultSubtitles(){return F(this,I.DEFAULT_SUBTITLES)}set defaultSubtitles(t){K(this,I.DEFAULT_SUBTITLES,t)}get defaultStreamType(){return ae(this,I.DEFAULT_STREAM_TYPE)}set defaultStreamType(t){re(this,I.DEFAULT_STREAM_TYPE,t)}get defaultDuration(){return ie(this,I.DEFAULT_DURATION)}set defaultDuration(t){de(this,I.DEFAULT_DURATION,t)}get noHotkeys(){return F(this,I.NO_HOTKEYS)}set noHotkeys(t){K(this,I.NO_HOTKEYS,t)}get keysUsed(){return ae(this,I.KEYS_USED)}set keysUsed(t){re(this,I.KEYS_USED,t)}get liveEdgeOffset(){return ie(this,I.LIVE_EDGE_OFFSET)}set liveEdgeOffset(t){de(this,I.LIVE_EDGE_OFFSET,t)}get noAutoSeekToLive(){return F(this,I.NO_AUTO_SEEK_TO_LIVE)}set noAutoSeekToLive(t){K(this,I.NO_AUTO_SEEK_TO_LIVE,t)}get noVolumePref(){return F(this,I.NO_VOLUME_PREF)}set noVolumePref(t){K(this,I.NO_VOLUME_PREF,t)}get noSubtitlesLangPref(){return F(this,I.NO_SUBTITLES_LANG_PREF)}set noSubtitlesLangPref(t){K(this,I.NO_SUBTITLES_LANG_PREF,t)}get noDefaultStore(){return F(this,I.NO_DEFAULT_STORE)}set noDefaultStore(t){K(this,I.NO_DEFAULT_STORE,t)}attributeChangedCallback(t,i,a){var r,n,s,o,l,d,m,p;if(super.attributeChangedCallback(t,i,a),t===I.NO_HOTKEYS)a!==i&&a===""?(this.hasAttribute(I.HOTKEYS)&&console.warn("Media Chrome: Both `hotkeys` and `nohotkeys` have been set. All hotkeys will be disabled."),this.disableHotkeys()):a!==i&&a===null&&this.enableHotkeys();else if(t===I.HOTKEYS)U(this,Ui).value=a;else if(t===I.DEFAULT_SUBTITLES&&a!==i)(r=U(this,X))==null||r.dispatch({type:"optionschangerequest",detail:{defaultSubtitles:this.hasAttribute(I.DEFAULT_SUBTITLES)}});else if(t===I.DEFAULT_STREAM_TYPE)(s=U(this,X))==null||s.dispatch({type:"optionschangerequest",detail:{defaultStreamType:(n=this.getAttribute(I.DEFAULT_STREAM_TYPE))!=null?n:void 0}});else if(t===I.LIVE_EDGE_OFFSET)(o=U(this,X))==null||o.dispatch({type:"optionschangerequest",detail:{liveEdgeOffset:this.hasAttribute(I.LIVE_EDGE_OFFSET)?+this.getAttribute(I.LIVE_EDGE_OFFSET):void 0,seekToLiveOffset:this.hasAttribute(I.SEEK_TO_LIVE_OFFSET)?void 0:+this.getAttribute(I.LIVE_EDGE_OFFSET)}});else if(t===I.SEEK_TO_LIVE_OFFSET)(l=U(this,X))==null||l.dispatch({type:"optionschangerequest",detail:{seekToLiveOffset:this.hasAttribute(I.SEEK_TO_LIVE_OFFSET)?+this.getAttribute(I.SEEK_TO_LIVE_OFFSET):void 0}});else if(t===I.NO_AUTO_SEEK_TO_LIVE)(d=U(this,X))==null||d.dispatch({type:"optionschangerequest",detail:{noAutoSeekToLive:this.hasAttribute(I.NO_AUTO_SEEK_TO_LIVE)}});else if(t===I.FULLSCREEN_ELEMENT){const h=a?(m=this.getRootNode())==null?void 0:m.getElementById(a):void 0;ri(this,fr,h),(p=U(this,X))==null||p.dispatch({type:"fullscreenelementchangerequest",detail:this.fullscreenElement})}else t===I.LANG&&a!==i&&Db(a)}connectedCallback(){var t,i;!U(this,X)&&!this.hasAttribute(I.NO_DEFAULT_STORE)&&vi(this,Hn,ol).call(this),(t=U(this,X))==null||t.dispatch({type:"documentelementchangerequest",detail:ye}),super.connectedCallback(),U(this,X)&&!U(this,Tt)&&ri(this,Tt,(i=U(this,X))==null?void 0:i.subscribe(U(this,Er))),this.enableHotkeys()}disconnectedCallback(){var t,i,a,r;(t=super.disconnectedCallback)==null||t.call(this),U(this,X)&&((i=U(this,X))==null||i.dispatch({type:"documentelementchangerequest",detail:void 0}),(a=U(this,X))==null||a.dispatch({type:R.MEDIA_TOGGLE_SUBTITLES_REQUEST,detail:!1})),U(this,Tt)&&((r=U(this,Tt))==null||r.call(this),ri(this,Tt,void 0))}mediaSetCallback(t){var i;super.mediaSetCallback(t),(i=U(this,X))==null||i.dispatch({type:"mediaelementchangerequest",detail:t}),t.hasAttribute("tabindex")||(t.tabIndex=-1)}mediaUnsetCallback(t){var i;super.mediaUnsetCallback(t),(i=U(this,X))==null||i.dispatch({type:"mediaelementchangerequest",detail:void 0})}propagateMediaState(t,i){pc(this.mediaStateReceivers,t,i)}associateElement(t){if(!t)return;const{associatedElementSubscriptions:i}=this;if(i.has(t))return;const a=this.registerMediaStateReceiver.bind(this),r=this.unregisterMediaStateReceiver.bind(this),n=gg(t,a,r);Object.values(R).forEach(s=>{t.addEventListener(s,U(this,Un))}),i.set(t,n)}unassociateElement(t){if(!t)return;const{associatedElementSubscriptions:i}=this;if(!i.has(t))return;i.get(t)(),i.delete(t),Object.values(R).forEach(r=>{t.removeEventListener(r,U(this,Un))})}registerMediaStateReceiver(t){if(!t)return;const i=this.mediaStateReceivers;i.indexOf(t)>-1||(i.push(t),U(this,X)&&Object.entries(U(this,X).getState()).forEach(([r,n])=>{pc([t],r,n)}))}unregisterMediaStateReceiver(t){const i=this.mediaStateReceivers,a=i.indexOf(t);a<0||i.splice(a,1)}enableHotkeys(){this.addEventListener("keydown",vi(this,Bn,ll))}disableHotkeys(){this.removeEventListener("keydown",vi(this,Bn,ll)),this.removeEventListener("keyup",vi(this,Ha,Gr))}get hotkeys(){return ae(this,I.HOTKEYS)}set hotkeys(t){re(this,I.HOTKEYS,t)}keyboardShortcutHandler(t){var i,a,r,n,s;const o=t.target;if(((r=(a=(i=o.getAttribute(I.KEYS_USED))==null?void 0:i.split(" "))!=null?a:o?.keysUsed)!=null?r:[]).map(h=>h==="Space"?" ":h).filter(Boolean).includes(t.key))return;let d,m,p;if(!U(this,Ui).contains(`no${t.key.toLowerCase()}`)&&!(t.key===" "&&U(this,Ui).contains("nospace")))switch(t.key){case" ":case"k":d=U(this,X).getState().mediaPaused?R.MEDIA_PLAY_REQUEST:R.MEDIA_PAUSE_REQUEST,this.dispatchEvent(new E.CustomEvent(d,{composed:!0,bubbles:!0}));break;case"m":d=this.mediaStore.getState().mediaVolumeLevel==="off"?R.MEDIA_UNMUTE_REQUEST:R.MEDIA_MUTE_REQUEST,this.dispatchEvent(new E.CustomEvent(d,{composed:!0,bubbles:!0}));break;case"f":d=this.mediaStore.getState().mediaIsFullscreen?R.MEDIA_EXIT_FULLSCREEN_REQUEST:R.MEDIA_ENTER_FULLSCREEN_REQUEST,this.dispatchEvent(new E.CustomEvent(d,{composed:!0,bubbles:!0}));break;case"c":this.dispatchEvent(new E.CustomEvent(R.MEDIA_TOGGLE_SUBTITLES_REQUEST,{composed:!0,bubbles:!0}));break;case"ArrowLeft":{const h=this.hasAttribute(I.KEYBOARD_BACKWARD_SEEK_OFFSET)?+this.getAttribute(I.KEYBOARD_BACKWARD_SEEK_OFFSET):cc;m=Math.max(((n=this.mediaStore.getState().mediaCurrentTime)!=null?n:0)-h,0),p=new E.CustomEvent(R.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:m}),this.dispatchEvent(p);break}case"ArrowRight":{const h=this.hasAttribute(I.KEYBOARD_FORWARD_SEEK_OFFSET)?+this.getAttribute(I.KEYBOARD_FORWARD_SEEK_OFFSET):cc;m=Math.max(((s=this.mediaStore.getState().mediaCurrentTime)!=null?s:0)+h,0),p=new E.CustomEvent(R.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:m}),this.dispatchEvent(p);break}}}}Ui=new WeakMap;fr=new WeakMap;X=new WeakMap;Er=new WeakMap;Tt=new WeakMap;Un=new WeakMap;Hn=new WeakSet;ol=function(){var e;this.mediaStore=pg({media:this.media,fullscreenElement:this.fullscreenElement,options:{defaultSubtitles:this.hasAttribute(I.DEFAULT_SUBTITLES),defaultDuration:this.hasAttribute(I.DEFAULT_DURATION)?+this.getAttribute(I.DEFAULT_DURATION):void 0,defaultStreamType:(e=this.getAttribute(I.DEFAULT_STREAM_TYPE))!=null?e:void 0,liveEdgeOffset:this.hasAttribute(I.LIVE_EDGE_OFFSET)?+this.getAttribute(I.LIVE_EDGE_OFFSET):void 0,seekToLiveOffset:this.hasAttribute(I.SEEK_TO_LIVE_OFFSET)?+this.getAttribute(I.SEEK_TO_LIVE_OFFSET):this.hasAttribute(I.LIVE_EDGE_OFFSET)?+this.getAttribute(I.LIVE_EDGE_OFFSET):void 0,noAutoSeekToLive:this.hasAttribute(I.NO_AUTO_SEEK_TO_LIVE),noVolumePref:this.hasAttribute(I.NO_VOLUME_PREF),noSubtitlesLangPref:this.hasAttribute(I.NO_SUBTITLES_LANG_PREF)}})};Ha=new WeakSet;Gr=function(e){const{key:t}=e;if(!$m.includes(t)){this.removeEventListener("keyup",vi(this,Ha,Gr));return}this.keyboardShortcutHandler(e)};Bn=new WeakSet;ll=function(e){const{metaKey:t,altKey:i,key:a}=e;if(t||i||!$m.includes(a)){this.removeEventListener("keyup",vi(this,Ha,Gr));return}[" ","ArrowLeft","ArrowRight"].includes(a)&&!(U(this,Ui).contains(`no${a.toLowerCase()}`)||a===" "&&U(this,Ui).contains("nospace"))&&e.preventDefault(),this.addEventListener("keyup",vi(this,Ha,Gr),{once:!0})};const vg=Object.values(u),fg=Object.values(dm),Hm=e=>{var t,i,a,r;let{observedAttributes:n}=e.constructor;!n&&((t=e.nodeName)!=null&&t.includes("-"))&&(E.customElements.upgrade(e),{observedAttributes:n}=e.constructor);const s=(r=(a=(i=e?.getAttribute)==null?void 0:i.call(e,q.MEDIA_CHROME_ATTRIBUTES))==null?void 0:a.split)==null?void 0:r.call(a,/\s+/);return Array.isArray(n||s)?(n||s).filter(o=>vg.includes(o)):[]},Eg=e=>{var t,i;return(t=e.nodeName)!=null&&t.includes("-")&&E.customElements.get((i=e.nodeName)==null?void 0:i.toLowerCase())&&!(e instanceof E.customElements.get(e.nodeName.toLowerCase()))&&E.customElements.upgrade(e),fg.some(a=>a in e)},dl=e=>Eg(e)||!!Hm(e).length,hc=e=>{var t;return(t=e?.join)==null?void 0:t.call(e,":")},mc={[u.MEDIA_SUBTITLES_LIST]:Yr,[u.MEDIA_SUBTITLES_SHOWING]:Yr,[u.MEDIA_SEEKABLE]:hc,[u.MEDIA_BUFFERED]:e=>e?.map(hc).join(" "),[u.MEDIA_PREVIEW_COORDS]:e=>e?.join(" "),[u.MEDIA_RENDITION_LIST]:bb,[u.MEDIA_AUDIO_TRACK_LIST]:Ab},_g=async(e,t,i)=>{var a,r;if(e.isConnected||await hm(0),typeof i=="boolean"||i==null)return K(e,t,i);if(typeof i=="number")return de(e,t,i);if(typeof i=="string")return re(e,t,i);if(Array.isArray(i)&&!i.length)return e.removeAttribute(t);const n=(r=(a=mc[t])==null?void 0:a.call(mc,i))!=null?r:i;return e.setAttribute(t,n)},bg=e=>{var t;return!!((t=e.closest)!=null&&t.call(e,'*[slot="media"]'))},Ii=(e,t)=>{if(bg(e))return;const i=(r,n)=>{var s,o;dl(r)&&n(r);const{children:l=[]}=r??{},d=(o=(s=r?.shadowRoot)==null?void 0:s.children)!=null?o:[];[...l,...d].forEach(p=>Ii(p,n))},a=e?.nodeName.toLowerCase();if(a.includes("-")&&!dl(e)){E.customElements.whenDefined(a).then(()=>{i(e,t)});return}i(e,t)},pc=(e,t,i)=>{e.forEach(a=>{if(t in a){a[t]=i;return}const r=Hm(a),n=t.toLowerCase();r.includes(n)&&_g(a,n,i)})},gg=(e,t,i)=>{Ii(e,t);const a=m=>{var p;const h=(p=m?.composedPath()[0])!=null?p:m.target;t(h)},r=m=>{var p;const h=(p=m?.composedPath()[0])!=null?p:m.target;i(h)};e.addEventListener(R.REGISTER_MEDIA_STATE_RECEIVER,a),e.addEventListener(R.UNREGISTER_MEDIA_STATE_RECEIVER,r);const n=m=>{m.forEach(p=>{const{addedNodes:h=[],removedNodes:c=[],type:v,target:g,attributeName:_}=p;v==="childList"?(Array.prototype.forEach.call(h,y=>Ii(y,t)),Array.prototype.forEach.call(c,y=>Ii(y,i))):v==="attributes"&&_===q.MEDIA_CHROME_ATTRIBUTES&&(dl(g)?t(g):i(g))})};let s=[];const o=m=>{const p=m.target;p.name!=="media"&&(s.forEach(h=>Ii(h,i)),s=[...p.assignedElements({flatten:!0})],s.forEach(h=>Ii(h,t)))};e.addEventListener("slotchange",o);const l=new MutationObserver(n);return l.observe(e,{childList:!0,attributes:!0,subtree:!0}),()=>{Ii(e,i),e.removeEventListener("slotchange",o),l.disconnect(),e.removeEventListener(R.REGISTER_MEDIA_STATE_RECEIVER,a),e.removeEventListener(R.UNREGISTER_MEDIA_STATE_RECEIVER,r)}};E.customElements.get("media-controller")||E.customElements.define("media-controller",Um);var yg=Um;const Ji={PLACEMENT:"placement",BOUNDS:"bounds"};function Tg(e){return`
    <style>
      :host {
        --_tooltip-background-color: var(--media-tooltip-background-color, var(--media-secondary-color, rgba(20, 20, 30, .7)));
        --_tooltip-background: var(--media-tooltip-background, var(--_tooltip-background-color));
        --_tooltip-arrow-half-width: calc(var(--media-tooltip-arrow-width, 12px) / 2);
        --_tooltip-arrow-height: var(--media-tooltip-arrow-height, 5px);
        --_tooltip-arrow-background: var(--media-tooltip-arrow-color, var(--_tooltip-background-color));
        position: relative;
        pointer-events: none;
        display: var(--media-tooltip-display, inline-flex);
        justify-content: center;
        align-items: center;
        box-sizing: border-box;
        z-index: var(--media-tooltip-z-index, 1);
        background: var(--_tooltip-background);
        color: var(--media-text-color, var(--media-primary-color, rgb(238 238 238)));
        font: var(--media-font,
          var(--media-font-weight, 400)
          var(--media-font-size, 13px) /
          var(--media-text-content-height, var(--media-control-height, 18px))
          var(--media-font-family, helvetica neue, segoe ui, roboto, arial, sans-serif));
        padding: var(--media-tooltip-padding, .35em .7em);
        border: var(--media-tooltip-border, none);
        border-radius: var(--media-tooltip-border-radius, 5px);
        filter: var(--media-tooltip-filter, drop-shadow(0 0 4px rgba(0, 0, 0, .2)));
        white-space: var(--media-tooltip-white-space, nowrap);
      }

      :host([hidden]) {
        display: none;
      }

      img, svg {
        display: inline-block;
      }

      #arrow {
        position: absolute;
        width: 0px;
        height: 0px;
        border-style: solid;
        display: var(--media-tooltip-arrow-display, block);
      }

      :host(:not([placement])),
      :host([placement="top"]) {
        position: absolute;
        bottom: calc(100% + var(--media-tooltip-distance, 12px));
        left: 50%;
        transform: translate(calc(-50% - var(--media-tooltip-offset-x, 0px)), 0);
      }
      :host(:not([placement])) #arrow,
      :host([placement="top"]) #arrow {
        top: 100%;
        left: 50%;
        border-width: var(--_tooltip-arrow-height) var(--_tooltip-arrow-half-width) 0 var(--_tooltip-arrow-half-width);
        border-color: var(--_tooltip-arrow-background) transparent transparent transparent;
        transform: translate(calc(-50% + var(--media-tooltip-offset-x, 0px)), 0);
      }

      :host([placement="right"]) {
        position: absolute;
        left: calc(100% + var(--media-tooltip-distance, 12px));
        top: 50%;
        transform: translate(0, -50%);
      }
      :host([placement="right"]) #arrow {
        top: 50%;
        right: 100%;
        border-width: var(--_tooltip-arrow-half-width) var(--_tooltip-arrow-height) var(--_tooltip-arrow-half-width) 0;
        border-color: transparent var(--_tooltip-arrow-background) transparent transparent;
        transform: translate(0, -50%);
      }

      :host([placement="bottom"]) {
        position: absolute;
        top: calc(100% + var(--media-tooltip-distance, 12px));
        left: 50%;
        transform: translate(calc(-50% - var(--media-tooltip-offset-x, 0px)), 0);
      }
      :host([placement="bottom"]) #arrow {
        bottom: 100%;
        left: 50%;
        border-width: 0 var(--_tooltip-arrow-half-width) var(--_tooltip-arrow-height) var(--_tooltip-arrow-half-width);
        border-color: transparent transparent var(--_tooltip-arrow-background) transparent;
        transform: translate(calc(-50% + var(--media-tooltip-offset-x, 0px)), 0);
      }

      :host([placement="left"]) {
        position: absolute;
        right: calc(100% + var(--media-tooltip-distance, 12px));
        top: 50%;
        transform: translate(0, -50%);
      }
      :host([placement="left"]) #arrow {
        top: 50%;
        left: 100%;
        border-width: var(--_tooltip-arrow-half-width) 0 var(--_tooltip-arrow-half-width) var(--_tooltip-arrow-height);
        border-color: transparent transparent transparent var(--_tooltip-arrow-background);
        transform: translate(0, -50%);
      }
      
      :host([placement="none"]) #arrow {
        display: none;
      }
    </style>
    <slot></slot>
    <div id="arrow"></div>
  `}class ho extends E.HTMLElement{constructor(){if(super(),this.updateXOffset=()=>{var t;if(!ym(this,{checkOpacity:!1,checkVisibilityCSS:!1}))return;const i=this.placement;if(i==="left"||i==="right"){this.style.removeProperty("--media-tooltip-offset-x");return}const a=getComputedStyle(this),r=(t=Fa(this,"#"+this.bounds))!=null?t:at(this);if(!r)return;const{x:n,width:s}=r.getBoundingClientRect(),{x:o,width:l}=this.getBoundingClientRect(),d=o+l,m=n+s,p=a.getPropertyValue("--media-tooltip-offset-x"),h=p?parseFloat(p.replace("px","")):0,c=a.getPropertyValue("--media-tooltip-container-margin"),v=c?parseFloat(c.replace("px","")):0,g=o-n+h-v,_=d-m+h+v;if(g<0){this.style.setProperty("--media-tooltip-offset-x",`${g}px`);return}if(_>0){this.style.setProperty("--media-tooltip-offset-x",`${_}px`);return}this.style.removeProperty("--media-tooltip-offset-x")},!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}if(this.arrowEl=this.shadowRoot.querySelector("#arrow"),Object.prototype.hasOwnProperty.call(this,"placement")){const t=this.placement;delete this.placement,this.placement=t}}static get observedAttributes(){return[Ji.PLACEMENT,Ji.BOUNDS]}get placement(){return ae(this,Ji.PLACEMENT)}set placement(t){re(this,Ji.PLACEMENT,t)}get bounds(){return ae(this,Ji.BOUNDS)}set bounds(t){re(this,Ji.BOUNDS,t)}}ho.shadowRootOptions={mode:"open"};ho.getTemplateHTML=Tg;E.customElements.get("media-tooltip")||E.customElements.define("media-tooltip",ho);var vc=ho,Cd=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},me=(e,t,i)=>(Cd(e,t,"read from private field"),i?i.call(e):t.get(e)),ea=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},cn=(e,t,i,a)=>(Cd(e,t,"write to private field"),t.set(e,i),i),Ag=(e,t,i)=>(Cd(e,t,"access private method"),i),At,ka,fi,da,Wn,ul,Bm;const ni={TOOLTIP_PLACEMENT:"tooltipplacement",DISABLED:"disabled",NO_TOOLTIP:"notooltip"};function kg(e,t={}){return`
    <style>
      :host {
        position: relative;
        font: var(--media-font,
          var(--media-font-weight, bold)
          var(--media-font-size, 14px) /
          var(--media-text-content-height, var(--media-control-height, 24px))
          var(--media-font-family, helvetica neue, segoe ui, roboto, arial, sans-serif));
        color: var(--media-text-color, var(--media-primary-color, rgb(238 238 238)));
        background: var(--media-control-background, var(--media-secondary-color, rgb(20 20 30 / .7)));
        padding: var(--media-button-padding, var(--media-control-padding, 10px));
        justify-content: var(--media-button-justify-content, center);
        display: inline-flex;
        align-items: center;
        vertical-align: middle;
        box-sizing: border-box;
        transition: background .15s linear;
        pointer-events: auto;
        cursor: var(--media-cursor, pointer);
        -webkit-tap-highlight-color: transparent;
      }

      
      :host(:focus-visible) {
        box-shadow: inset 0 0 0 2px rgb(27 127 204 / .9);
        outline: 0;
      }
      
      :host(:where(:focus)) {
        box-shadow: none;
        outline: 0;
      }

      :host(:hover) {
        background: var(--media-control-hover-background, rgba(50 50 70 / .7));
      }

      svg, img, ::slotted(svg), ::slotted(img) {
        width: var(--media-button-icon-width);
        height: var(--media-button-icon-height, var(--media-control-height, 24px));
        transform: var(--media-button-icon-transform);
        transition: var(--media-button-icon-transition);
        fill: var(--media-icon-color, var(--media-primary-color, rgb(238 238 238)));
        vertical-align: middle;
        max-width: 100%;
        max-height: 100%;
        min-width: 100%;
      }

      media-tooltip {
        
        max-width: 0;
        overflow-x: clip;
        opacity: 0;
        transition: opacity .3s, max-width 0s 9s;
      }

      :host(:hover) media-tooltip,
      :host(:focus-visible) media-tooltip {
        max-width: 100vw;
        opacity: 1;
        transition: opacity .3s;
      }

      :host([notooltip]) slot[name="tooltip"] {
        display: none;
      }
    </style>

    ${this.getSlotTemplateHTML(e,t)}

    <slot name="tooltip">
      <media-tooltip part="tooltip" aria-hidden="true">
        <template shadowrootmode="${vc.shadowRootOptions.mode}">
          ${vc.getTemplateHTML({})}
        </template>
        <slot name="tooltip-content">
          ${this.getTooltipContentHTML(e)}
        </slot>
      </media-tooltip>
    </slot>
  `}function Sg(e,t){return`
    <slot></slot>
  `}function wg(){return""}class Ce extends E.HTMLElement{constructor(){if(super(),ea(this,ul),ea(this,At,void 0),this.preventClick=!1,this.tooltipEl=null,ea(this,ka,t=>{this.preventClick||this.handleClick(t),setTimeout(me(this,fi),0)}),ea(this,fi,()=>{var t,i;(i=(t=this.tooltipEl)==null?void 0:t.updateXOffset)==null||i.call(t)}),ea(this,da,t=>{const{key:i}=t;if(!this.keysUsed.includes(i)){this.removeEventListener("keyup",me(this,da));return}this.preventClick||this.handleClick(t)}),ea(this,Wn,t=>{const{metaKey:i,altKey:a,key:r}=t;if(i||a||!this.keysUsed.includes(r)){this.removeEventListener("keyup",me(this,da));return}this.addEventListener("keyup",me(this,da),{once:!0})}),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes),i=this.constructor.getTemplateHTML(t);this.shadowRoot.setHTMLUnsafe?this.shadowRoot.setHTMLUnsafe(i):this.shadowRoot.innerHTML=i}this.tooltipEl=this.shadowRoot.querySelector("media-tooltip")}static get observedAttributes(){return["disabled",ni.TOOLTIP_PLACEMENT,q.MEDIA_CONTROLLER]}enable(){this.addEventListener("click",me(this,ka)),this.addEventListener("keydown",me(this,Wn)),this.tabIndex=0}disable(){this.removeEventListener("click",me(this,ka)),this.removeEventListener("keydown",me(this,Wn)),this.removeEventListener("keyup",me(this,da)),this.tabIndex=-1}attributeChangedCallback(t,i,a){var r,n,s,o,l;t===q.MEDIA_CONTROLLER?(i&&((n=(r=me(this,At))==null?void 0:r.unassociateElement)==null||n.call(r,this),cn(this,At,null)),a&&this.isConnected&&(cn(this,At,(s=this.getRootNode())==null?void 0:s.getElementById(a)),(l=(o=me(this,At))==null?void 0:o.associateElement)==null||l.call(o,this))):t==="disabled"&&a!==i?a==null?this.enable():this.disable():t===ni.TOOLTIP_PLACEMENT&&this.tooltipEl&&a!==i&&(this.tooltipEl.placement=a),me(this,fi).call(this)}connectedCallback(){var t,i,a;const{style:r}=fe(this.shadowRoot,":host");r.setProperty("display",`var(--media-control-display, var(--${this.localName}-display, inline-flex))`),this.hasAttribute("disabled")?this.disable():this.enable(),this.setAttribute("role","button");const n=this.getAttribute(q.MEDIA_CONTROLLER);n&&(cn(this,At,(t=this.getRootNode())==null?void 0:t.getElementById(n)),(a=(i=me(this,At))==null?void 0:i.associateElement)==null||a.call(i,this)),E.customElements.whenDefined("media-tooltip").then(()=>Ag(this,ul,Bm).call(this))}disconnectedCallback(){var t,i;this.disable(),(i=(t=me(this,At))==null?void 0:t.unassociateElement)==null||i.call(t,this),cn(this,At,null),this.removeEventListener("mouseenter",me(this,fi)),this.removeEventListener("focus",me(this,fi)),this.removeEventListener("click",me(this,ka))}get keysUsed(){return["Enter"," "]}get tooltipPlacement(){return ae(this,ni.TOOLTIP_PLACEMENT)}set tooltipPlacement(t){re(this,ni.TOOLTIP_PLACEMENT,t)}get mediaController(){return ae(this,q.MEDIA_CONTROLLER)}set mediaController(t){re(this,q.MEDIA_CONTROLLER,t)}get disabled(){return F(this,ni.DISABLED)}set disabled(t){K(this,ni.DISABLED,t)}get noTooltip(){return F(this,ni.NO_TOOLTIP)}set noTooltip(t){K(this,ni.NO_TOOLTIP,t)}handleClick(t){}}At=new WeakMap;ka=new WeakMap;fi=new WeakMap;da=new WeakMap;Wn=new WeakMap;ul=new WeakSet;Bm=function(){this.addEventListener("mouseenter",me(this,fi)),this.addEventListener("focus",me(this,fi)),this.addEventListener("click",me(this,ka));const e=this.tooltipPlacement;e&&this.tooltipEl&&(this.tooltipEl.placement=e)};Ce.shadowRootOptions={mode:"open"};Ce.getTemplateHTML=kg;Ce.getSlotTemplateHTML=Sg;Ce.getTooltipContentHTML=wg;E.customElements.get("media-chrome-button")||E.customElements.define("media-chrome-button",Ce);const fc=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M22.13 3H3.87a.87.87 0 0 0-.87.87v13.26a.87.87 0 0 0 .87.87h3.4L9 16H5V5h16v11h-4l1.72 2h3.4a.87.87 0 0 0 .87-.87V3.87a.87.87 0 0 0-.86-.87Zm-8.75 11.44a.5.5 0 0 0-.76 0l-4.91 5.73a.5.5 0 0 0 .38.83h9.82a.501.501 0 0 0 .38-.83l-4.91-5.73Z"/>
</svg>
`;function Ig(e){return`
    <style>
      :host([${u.MEDIA_IS_AIRPLAYING}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      
      :host(:not([${u.MEDIA_IS_AIRPLAYING}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${u.MEDIA_IS_AIRPLAYING}]) slot[name=tooltip-enter],
      :host(:not([${u.MEDIA_IS_AIRPLAYING}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${fc}</slot>
      <slot name="exit">${fc}</slot>
    </slot>
  `}function Rg(){return`
    <slot name="tooltip-enter">${C("start airplay")}</slot>
    <slot name="tooltip-exit">${C("stop airplay")}</slot>
  `}const Ec=e=>{const t=e.mediaIsAirplaying?C("stop airplay"):C("start airplay");e.setAttribute("aria-label",t)};class Dd extends Ce{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_IS_AIRPLAYING,u.MEDIA_AIRPLAY_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),Ec(this)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_IS_AIRPLAYING&&Ec(this)}get mediaIsAirplaying(){return F(this,u.MEDIA_IS_AIRPLAYING)}set mediaIsAirplaying(t){K(this,u.MEDIA_IS_AIRPLAYING,t)}get mediaAirplayUnavailable(){return ae(this,u.MEDIA_AIRPLAY_UNAVAILABLE)}set mediaAirplayUnavailable(t){re(this,u.MEDIA_AIRPLAY_UNAVAILABLE,t)}handleClick(){const t=new E.CustomEvent(R.MEDIA_AIRPLAY_REQUEST,{composed:!0,bubbles:!0});this.dispatchEvent(t)}}Dd.getSlotTemplateHTML=Ig;Dd.getTooltipContentHTML=Rg;E.customElements.get("media-airplay-button")||E.customElements.define("media-airplay-button",Dd);const Cg=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M22.83 5.68a2.58 2.58 0 0 0-2.3-2.5c-3.62-.24-11.44-.24-15.06 0a2.58 2.58 0 0 0-2.3 2.5c-.23 4.21-.23 8.43 0 12.64a2.58 2.58 0 0 0 2.3 2.5c3.62.24 11.44.24 15.06 0a2.58 2.58 0 0 0 2.3-2.5c.23-4.21.23-8.43 0-12.64Zm-11.39 9.45a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.92 3.92 0 0 1 .92-2.77 3.18 3.18 0 0 1 2.43-1 2.94 2.94 0 0 1 2.13.78c.364.359.62.813.74 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.17 1.61 1.61 0 0 0-1.29.58 2.79 2.79 0 0 0-.5 1.89 3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.48 1.48 0 0 0 1-.37 2.1 2.1 0 0 0 .59-1.14l1.4.44a3.23 3.23 0 0 1-1.07 1.69Zm7.22 0a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.88 3.88 0 0 1 .93-2.77 3.14 3.14 0 0 1 2.42-1 3 3 0 0 1 2.16.82 2.8 2.8 0 0 1 .73 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.21 1.61 1.61 0 0 0-1.29.58A2.79 2.79 0 0 0 15 12a3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.44 1.44 0 0 0 1-.37 2.1 2.1 0 0 0 .6-1.15l1.4.44a3.17 3.17 0 0 1-1.1 1.7Z"/>
</svg>`,Dg=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M17.73 14.09a1.4 1.4 0 0 1-1 .37 1.579 1.579 0 0 1-1.27-.58A3 3 0 0 1 15 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34A2.89 2.89 0 0 0 19 9.07a3 3 0 0 0-2.14-.78 3.14 3.14 0 0 0-2.42 1 3.91 3.91 0 0 0-.93 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.17 3.17 0 0 0 1.07-1.74l-1.4-.45c-.083.43-.3.822-.62 1.12Zm-7.22 0a1.43 1.43 0 0 1-1 .37 1.58 1.58 0 0 1-1.27-.58A3 3 0 0 1 7.76 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34a2.81 2.81 0 0 0-.74-1.32 2.94 2.94 0 0 0-2.13-.78 3.18 3.18 0 0 0-2.43 1 4 4 0 0 0-.92 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.23 3.23 0 0 0 1.07-1.74l-1.4-.45a2.06 2.06 0 0 1-.6 1.07Zm12.32-8.41a2.59 2.59 0 0 0-2.3-2.51C18.72 3.05 15.86 3 13 3c-2.86 0-5.72.05-7.53.17a2.59 2.59 0 0 0-2.3 2.51c-.23 4.207-.23 8.423 0 12.63a2.57 2.57 0 0 0 2.3 2.5c1.81.13 4.67.19 7.53.19 2.86 0 5.72-.06 7.53-.19a2.57 2.57 0 0 0 2.3-2.5c.23-4.207.23-8.423 0-12.63Zm-1.49 12.53a1.11 1.11 0 0 1-.91 1.11c-1.67.11-4.45.18-7.43.18-2.98 0-5.76-.07-7.43-.18a1.11 1.11 0 0 1-.91-1.11c-.21-4.14-.21-8.29 0-12.43a1.11 1.11 0 0 1 .91-1.11C7.24 4.56 10 4.49 13 4.49s5.76.07 7.43.18a1.11 1.11 0 0 1 .91 1.11c.21 4.14.21 8.29 0 12.43Z"/>
</svg>`;function Lg(e){return`
    <style>
      :host([aria-checked="true"]) slot[name=off] {
        display: none !important;
      }

      
      :host(:not([aria-checked="true"])) slot[name=on] {
        display: none !important;
      }

      :host([aria-checked="true"]) slot[name=tooltip-enable],
      :host(:not([aria-checked="true"])) slot[name=tooltip-disable] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="on">${Cg}</slot>
      <slot name="off">${Dg}</slot>
    </slot>
  `}function Mg(){return`
    <slot name="tooltip-enable">${C("Enable captions")}</slot>
    <slot name="tooltip-disable">${C("Disable captions")}</slot>
  `}const _c=e=>{e.setAttribute("aria-checked",Mm(e).toString())};class Ld extends Ce{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_SUBTITLES_LIST,u.MEDIA_SUBTITLES_SHOWING]}connectedCallback(){super.connectedCallback(),this.setAttribute("role","switch"),this.setAttribute("aria-label",C("closed captions")),_c(this)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_SUBTITLES_SHOWING&&_c(this)}get mediaSubtitlesList(){return bc(this,u.MEDIA_SUBTITLES_LIST)}set mediaSubtitlesList(t){gc(this,u.MEDIA_SUBTITLES_LIST,t)}get mediaSubtitlesShowing(){return bc(this,u.MEDIA_SUBTITLES_SHOWING)}set mediaSubtitlesShowing(t){gc(this,u.MEDIA_SUBTITLES_SHOWING,t)}handleClick(){this.dispatchEvent(new E.CustomEvent(R.MEDIA_TOGGLE_SUBTITLES_REQUEST,{composed:!0,bubbles:!0}))}}Ld.getSlotTemplateHTML=Lg;Ld.getTooltipContentHTML=Mg;const bc=(e,t)=>{const i=e.getAttribute(t);return i?uo(i):[]},gc=(e,t,i)=>{if(!i?.length){e.removeAttribute(t);return}const a=Yr(i);e.getAttribute(t)!==a&&e.setAttribute(t,a)};E.customElements.get("media-captions-button")||E.customElements.define("media-captions-button",Ld);const xg='<svg aria-hidden="true" viewBox="0 0 24 24"><g><path class="cast_caf_icon_arch0" d="M1,18 L1,21 L4,21 C4,19.3 2.66,18 1,18 L1,18 Z"/><path class="cast_caf_icon_arch1" d="M1,14 L1,16 C3.76,16 6,18.2 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z"/><path class="cast_caf_icon_arch2" d="M1,10 L1,12 C5.97,12 10,16.0 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z"/><path class="cast_caf_icon_box" d="M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"/></g></svg>',Og='<svg aria-hidden="true" viewBox="0 0 24 24"><g><path class="cast_caf_icon_arch0" d="M1,18 L1,21 L4,21 C4,19.3 2.66,18 1,18 L1,18 Z"/><path class="cast_caf_icon_arch1" d="M1,14 L1,16 C3.76,16 6,18.2 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z"/><path class="cast_caf_icon_arch2" d="M1,10 L1,12 C5.97,12 10,16.0 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z"/><path class="cast_caf_icon_box" d="M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"/><path class="cast_caf_icon_boxfill" d="M5,7 L5,8.63 C8,8.6 13.37,14 13.37,17 L19,17 L19,7 Z"/></g></svg>';function Ng(e){return`
    <style>
      :host([${u.MEDIA_IS_CASTING}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      
      :host(:not([${u.MEDIA_IS_CASTING}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${u.MEDIA_IS_CASTING}]) slot[name=tooltip-enter],
      :host(:not([${u.MEDIA_IS_CASTING}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${xg}</slot>
      <slot name="exit">${Og}</slot>
    </slot>
  `}function Pg(){return`
    <slot name="tooltip-enter">${C("Start casting")}</slot>
    <slot name="tooltip-exit">${C("Stop casting")}</slot>
  `}const yc=e=>{const t=e.mediaIsCasting?C("stop casting"):C("start casting");e.setAttribute("aria-label",t)};class Md extends Ce{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_IS_CASTING,u.MEDIA_CAST_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),yc(this)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_IS_CASTING&&yc(this)}get mediaIsCasting(){return F(this,u.MEDIA_IS_CASTING)}set mediaIsCasting(t){K(this,u.MEDIA_IS_CASTING,t)}get mediaCastUnavailable(){return ae(this,u.MEDIA_CAST_UNAVAILABLE)}set mediaCastUnavailable(t){re(this,u.MEDIA_CAST_UNAVAILABLE,t)}handleClick(){const t=this.mediaIsCasting?R.MEDIA_EXIT_CAST_REQUEST:R.MEDIA_ENTER_CAST_REQUEST;this.dispatchEvent(new E.CustomEvent(t,{composed:!0,bubbles:!0}))}}Md.getSlotTemplateHTML=Ng;Md.getTooltipContentHTML=Pg;E.customElements.get("media-cast-button")||E.customElements.define("media-cast-button",Md);var xd=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Ki=(e,t,i)=>(xd(e,t,"read from private field"),t.get(e)),Kt=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Od=(e,t,i,a)=>(xd(e,t,"write to private field"),t.set(e,i),i),Ai=(e,t,i)=>(xd(e,t,"access private method"),i),Hs,jr,ji,Fn,cl,hl,Wm,ml,Fm,pl,Km,vl,Vm,fl,qm;function $g(e){return`
    <style>
      :host {
        font: var(--media-font,
          var(--media-font-weight, normal)
          var(--media-font-size, 14px) /
          var(--media-text-content-height, var(--media-control-height, 24px))
          var(--media-font-family, helvetica neue, segoe ui, roboto, arial, sans-serif));
        color: var(--media-text-color, var(--media-primary-color, rgb(238 238 238)));
        display: var(--media-dialog-display, inline-flex);
        justify-content: center;
        align-items: center;
        
        transition-behavior: allow-discrete;
        visibility: hidden;
        opacity: 0;
        transform: translateY(2px) scale(.99);
        pointer-events: none;
      }

      :host([open]) {
        transition: display .2s, visibility 0s, opacity .2s ease-out, transform .15s ease-out;
        visibility: visible;
        opacity: 1;
        transform: translateY(0) scale(1);
        pointer-events: auto;
      }

      #content {
        display: flex;
        position: relative;
        box-sizing: border-box;
        width: min(320px, 100%);
        word-wrap: break-word;
        max-height: 100%;
        overflow: auto;
        text-align: center;
        line-height: 1.4;
      }
    </style>
    ${this.getSlotTemplateHTML(e)}
  `}function Ug(e){return`
    <slot id="content"></slot>
  `}const za={OPEN:"open",ANCHOR:"anchor"};class rn extends E.HTMLElement{constructor(){super(),Kt(this,Fn),Kt(this,hl),Kt(this,ml),Kt(this,pl),Kt(this,vl),Kt(this,fl),Kt(this,Hs,!1),Kt(this,jr,null),Kt(this,ji,null),this.addEventListener("invoke",this),this.addEventListener("focusout",this),this.addEventListener("keydown",this)}static get observedAttributes(){return[za.OPEN,za.ANCHOR]}get open(){return F(this,za.OPEN)}set open(t){K(this,za.OPEN,t)}handleEvent(t){switch(t.type){case"invoke":Ai(this,pl,Km).call(this,t);break;case"focusout":Ai(this,vl,Vm).call(this,t);break;case"keydown":Ai(this,fl,qm).call(this,t);break}}connectedCallback(){Ai(this,Fn,cl).call(this),this.role||(this.role="dialog")}attributeChangedCallback(t,i,a){Ai(this,Fn,cl).call(this),t===za.OPEN&&a!==i&&(this.open?Ai(this,hl,Wm).call(this):Ai(this,ml,Fm).call(this))}focus(){Od(this,jr,Td());const t=!this.dispatchEvent(new Event("focus",{composed:!0,cancelable:!0})),i=!this.dispatchEvent(new Event("focusin",{composed:!0,bubbles:!0,cancelable:!0}));if(t||i)return;const a=this.querySelector('[autofocus], [tabindex]:not([tabindex="-1"]), [role="menu"]');a?.focus()}get keysUsed(){return["Escape","Tab"]}}Hs=new WeakMap;jr=new WeakMap;ji=new WeakMap;Fn=new WeakSet;cl=function(){if(!Ki(this,Hs)&&(Od(this,Hs,!0),!this.shadowRoot)){this.attachShadow(this.constructor.shadowRootOptions);const e=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e),queueMicrotask(()=>{const{style:t}=fe(this.shadowRoot,":host");t.setProperty("transition","display .15s, visibility .15s, opacity .15s ease-in, transform .15s ease-in")})}};hl=new WeakSet;Wm=function(){var e;(e=Ki(this,ji))==null||e.setAttribute("aria-expanded","true"),this.dispatchEvent(new Event("open",{composed:!0,bubbles:!0})),this.addEventListener("transitionend",()=>this.focus(),{once:!0})};ml=new WeakSet;Fm=function(){var e;(e=Ki(this,ji))==null||e.setAttribute("aria-expanded","false"),this.dispatchEvent(new Event("close",{composed:!0,bubbles:!0}))};pl=new WeakSet;Km=function(e){Od(this,ji,e.relatedTarget),ti(this,e.relatedTarget)||(this.open=!this.open)};vl=new WeakSet;Vm=function(e){var t;ti(this,e.relatedTarget)||((t=Ki(this,jr))==null||t.focus(),Ki(this,ji)&&Ki(this,ji)!==e.relatedTarget&&this.open&&(this.open=!1))};fl=new WeakSet;qm=function(e){var t,i,a,r,n;const{key:s,ctrlKey:o,altKey:l,metaKey:d}=e;o||l||d||this.keysUsed.includes(s)&&(e.preventDefault(),e.stopPropagation(),s==="Tab"?(e.shiftKey?(i=(t=this.previousElementSibling)==null?void 0:t.focus)==null||i.call(t):(r=(a=this.nextElementSibling)==null?void 0:a.focus)==null||r.call(a),this.blur()):s==="Escape"&&((n=Ki(this,jr))==null||n.focus(),this.open=!1))};rn.shadowRootOptions={mode:"open"};rn.getTemplateHTML=$g;rn.getSlotTemplateHTML=Ug;E.customElements.get("media-chrome-dialog")||E.customElements.define("media-chrome-dialog",rn);var Nd=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},se=(e,t,i)=>(Nd(e,t,"read from private field"),i?i.call(e):t.get(e)),Ie=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},li=(e,t,i,a)=>(Nd(e,t,"write to private field"),t.set(e,i),i),ut=(e,t,i)=>(Nd(e,t,"access private method"),i),kt,mo,Kn,Vn,ct,Bs,qn,Yn,Gn,Pd,Ym,jn,El,Qn,_l,Ws,$d,bl,Gm,gl,jm,yl,Qm,Tl,Zm;function Hg(e){return`
    <style>
      :host {
        --_focus-box-shadow: var(--media-focus-box-shadow, inset 0 0 0 2px rgb(27 127 204 / .9));
        --_media-range-padding: var(--media-range-padding, var(--media-control-padding, 10px));

        box-shadow: var(--_focus-visible-box-shadow, none);
        background: var(--media-control-background, var(--media-secondary-color, rgb(20 20 30 / .7)));
        height: calc(var(--media-control-height, 24px) + 2 * var(--_media-range-padding));
        display: inline-flex;
        align-items: center;
        
        vertical-align: middle;
        box-sizing: border-box;
        position: relative;
        width: 100px;
        transition: background .15s linear;
        cursor: var(--media-cursor, pointer);
        pointer-events: auto;
        touch-action: none; 
      }

      
      input[type=range]:focus {
        outline: 0;
      }
      input[type=range]:focus::-webkit-slider-runnable-track {
        outline: 0;
      }

      :host(:hover) {
        background: var(--media-control-hover-background, rgb(50 50 70 / .7));
      }

      #leftgap {
        padding-left: var(--media-range-padding-left, var(--_media-range-padding));
      }

      #rightgap {
        padding-right: var(--media-range-padding-right, var(--_media-range-padding));
      }

      #startpoint,
      #endpoint {
        position: absolute;
      }

      #endpoint {
        right: 0;
      }

      #container {
        
        width: var(--media-range-track-width, 100%);
        transform: translate(var(--media-range-track-translate-x, 0px), var(--media-range-track-translate-y, 0px));
        position: relative;
        height: 100%;
        display: flex;
        align-items: center;
        min-width: 40px;
      }

      #range {
        
        display: var(--media-time-range-hover-display, block);
        bottom: var(--media-time-range-hover-bottom, -7px);
        height: var(--media-time-range-hover-height, max(100% + 7px, 25px));
        width: 100%;
        position: absolute;
        cursor: var(--media-cursor, pointer);

        -webkit-appearance: none; 
        -webkit-tap-highlight-color: transparent;
        background: transparent; 
        margin: 0;
        z-index: 1;
      }

      @media (hover: hover) {
        #range {
          bottom: var(--media-time-range-hover-bottom, -5px);
          height: var(--media-time-range-hover-height, max(100% + 5px, 20px));
        }
      }

      
      
      #range::-webkit-slider-thumb {
        -webkit-appearance: none;
        background: transparent;
        width: .1px;
        height: .1px;
      }

      
      #range::-moz-range-thumb {
        background: transparent;
        border: transparent;
        width: .1px;
        height: .1px;
      }

      #appearance {
        height: var(--media-range-track-height, 4px);
        display: flex;
        flex-direction: column;
        justify-content: center;
        width: 100%;
        position: absolute;
        
        will-change: transform;
      }

      #track {
        background: var(--media-range-track-background, rgb(255 255 255 / .2));
        border-radius: var(--media-range-track-border-radius, 1px);
        border: var(--media-range-track-border, none);
        outline: var(--media-range-track-outline);
        outline-offset: var(--media-range-track-outline-offset);
        backdrop-filter: var(--media-range-track-backdrop-filter);
        -webkit-backdrop-filter: var(--media-range-track-backdrop-filter);
        box-shadow: var(--media-range-track-box-shadow, none);
        position: absolute;
        width: 100%;
        height: 100%;
        overflow: hidden;
      }

      #progress,
      #pointer {
        position: absolute;
        height: 100%;
        will-change: width;
      }

      #progress {
        background: var(--media-range-bar-color, var(--media-primary-color, rgb(238 238 238)));
        transition: var(--media-range-track-transition);
      }

      #pointer {
        background: var(--media-range-track-pointer-background);
        border-right: var(--media-range-track-pointer-border-right);
        transition: visibility .25s, opacity .25s;
        visibility: hidden;
        opacity: 0;
      }

      @media (hover: hover) {
        :host(:hover) #pointer {
          transition: visibility .5s, opacity .5s;
          visibility: visible;
          opacity: 1;
        }
      }

      #thumb,
      ::slotted([slot=thumb]) {
        width: var(--media-range-thumb-width, 10px);
        height: var(--media-range-thumb-height, 10px);
        transition: var(--media-range-thumb-transition);
        transform: var(--media-range-thumb-transform, none);
        opacity: var(--media-range-thumb-opacity, 1);
        translate: -50%;
        position: absolute;
        left: 0;
        cursor: var(--media-cursor, pointer);
      }

      #thumb {
        border-radius: var(--media-range-thumb-border-radius, 10px);
        background: var(--media-range-thumb-background, var(--media-primary-color, rgb(238 238 238)));
        box-shadow: var(--media-range-thumb-box-shadow, 1px 1px 1px transparent);
        border: var(--media-range-thumb-border, none);
      }

      :host([disabled]) #thumb {
        background-color: #777;
      }

      .segments #appearance {
        height: var(--media-range-segment-hover-height, 7px);
      }

      #track {
        clip-path: url(#segments-clipping);
      }

      #segments {
        --segments-gap: var(--media-range-segments-gap, 2px);
        position: absolute;
        width: 100%;
        height: 100%;
      }

      #segments-clipping {
        transform: translateX(calc(var(--segments-gap) / 2));
      }

      #segments-clipping:empty {
        display: none;
      }

      #segments-clipping rect {
        height: var(--media-range-track-height, 4px);
        y: calc((var(--media-range-segment-hover-height, 7px) - var(--media-range-track-height, 4px)) / 2);
        transition: var(--media-range-segment-transition, transform .1s ease-in-out);
        transform: var(--media-range-segment-transform, scaleY(1));
        transform-origin: center;
      }
    </style>
    <div id="leftgap"></div>
    <div id="container">
      <div id="startpoint"></div>
      <div id="endpoint"></div>
      <div id="appearance">
        <div id="track" part="track">
          <div id="pointer"></div>
          <div id="progress" part="progress"></div>
        </div>
        <slot name="thumb">
          <div id="thumb" part="thumb"></div>
        </slot>
        <svg id="segments"><clipPath id="segments-clipping"></clipPath></svg>
      </div>
      <input id="range" type="range" min="0" max="1" step="any" value="0">
    </div>
    <div id="rightgap"></div>
  `}class Ka extends E.HTMLElement{constructor(){if(super(),Ie(this,Pd),Ie(this,jn),Ie(this,Qn),Ie(this,Ws),Ie(this,bl),Ie(this,gl),Ie(this,yl),Ie(this,Tl),Ie(this,kt,void 0),Ie(this,mo,void 0),Ie(this,Kn,void 0),Ie(this,Vn,void 0),Ie(this,ct,{}),Ie(this,Bs,[]),Ie(this,qn,()=>{if(this.range.matches(":focus-visible")){const{style:t}=fe(this.shadowRoot,":host");t.setProperty("--_focus-visible-box-shadow","var(--_focus-box-shadow)")}}),Ie(this,Yn,()=>{const{style:t}=fe(this.shadowRoot,":host");t.removeProperty("--_focus-visible-box-shadow")}),Ie(this,Gn,()=>{const t=this.shadowRoot.querySelector("#segments-clipping");t&&t.parentNode.append(t)}),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes),i=this.constructor.getTemplateHTML(t);this.shadowRoot.setHTMLUnsafe?this.shadowRoot.setHTMLUnsafe(i):this.shadowRoot.innerHTML=i}this.container=this.shadowRoot.querySelector("#container"),li(this,Kn,this.shadowRoot.querySelector("#startpoint")),li(this,Vn,this.shadowRoot.querySelector("#endpoint")),this.range=this.shadowRoot.querySelector("#range"),this.appearance=this.shadowRoot.querySelector("#appearance")}static get observedAttributes(){return["disabled","aria-disabled",q.MEDIA_CONTROLLER]}attributeChangedCallback(t,i,a){var r,n,s,o,l;t===q.MEDIA_CONTROLLER?(i&&((n=(r=se(this,kt))==null?void 0:r.unassociateElement)==null||n.call(r,this),li(this,kt,null)),a&&this.isConnected&&(li(this,kt,(s=this.getRootNode())==null?void 0:s.getElementById(a)),(l=(o=se(this,kt))==null?void 0:o.associateElement)==null||l.call(o,this))):(t==="disabled"||t==="aria-disabled"&&i!==a)&&(a==null?(this.range.removeAttribute(t),ut(this,jn,El).call(this)):(this.range.setAttribute(t,a),ut(this,Qn,_l).call(this)))}connectedCallback(){var t,i,a;const{style:r}=fe(this.shadowRoot,":host");r.setProperty("display",`var(--media-control-display, var(--${this.localName}-display, inline-flex))`),se(this,ct).pointer=fe(this.shadowRoot,"#pointer"),se(this,ct).progress=fe(this.shadowRoot,"#progress"),se(this,ct).thumb=fe(this.shadowRoot,'#thumb, ::slotted([slot="thumb"])'),se(this,ct).activeSegment=fe(this.shadowRoot,"#segments-clipping rect:nth-child(0)");const n=this.getAttribute(q.MEDIA_CONTROLLER);n&&(li(this,kt,(t=this.getRootNode())==null?void 0:t.getElementById(n)),(a=(i=se(this,kt))==null?void 0:i.associateElement)==null||a.call(i,this)),this.updateBar(),this.shadowRoot.addEventListener("focusin",se(this,qn)),this.shadowRoot.addEventListener("focusout",se(this,Yn)),ut(this,jn,El).call(this),Pa(this.container,se(this,Gn))}disconnectedCallback(){var t,i;ut(this,Qn,_l).call(this),(i=(t=se(this,kt))==null?void 0:t.unassociateElement)==null||i.call(t,this),li(this,kt,null),this.shadowRoot.removeEventListener("focusin",se(this,qn)),this.shadowRoot.removeEventListener("focusout",se(this,Yn)),$a(this.container,se(this,Gn))}updatePointerBar(t){var i;(i=se(this,ct).pointer)==null||i.style.setProperty("width",`${this.getPointerRatio(t)*100}%`)}updateBar(){var t,i;const a=this.range.valueAsNumber*100;(t=se(this,ct).progress)==null||t.style.setProperty("width",`${a}%`),(i=se(this,ct).thumb)==null||i.style.setProperty("left",`${a}%`)}updateSegments(t){const i=this.shadowRoot.querySelector("#segments-clipping");if(i.textContent="",this.container.classList.toggle("segments",!!t?.length),!t?.length)return;const a=[...new Set([+this.range.min,...t.flatMap(n=>[n.start,n.end]),+this.range.max])];li(this,Bs,[...a]);const r=a.pop();for(const[n,s]of a.entries()){const[o,l]=[n===0,n===a.length-1],d=o?"calc(var(--segments-gap) / -1)":`${s*100}%`,p=`calc(${((l?r:a[n+1])-s)*100}%${o||l?"":" - var(--segments-gap)"})`,h=ye.createElementNS("http://www.w3.org/2000/svg","rect"),c=fe(this.shadowRoot,`#segments-clipping rect:nth-child(${n+1})`);c.style.setProperty("x",d),c.style.setProperty("width",p),i.append(h)}}getPointerRatio(t){return Ob(t.clientX,t.clientY,se(this,Kn).getBoundingClientRect(),se(this,Vn).getBoundingClientRect())}get dragging(){return this.hasAttribute("dragging")}handleEvent(t){switch(t.type){case"pointermove":ut(this,Tl,Zm).call(this,t);break;case"input":this.updateBar();break;case"pointerenter":ut(this,bl,Gm).call(this,t);break;case"pointerdown":ut(this,Ws,$d).call(this,t);break;case"pointerup":ut(this,gl,jm).call(this);break;case"pointerleave":ut(this,yl,Qm).call(this);break}}get keysUsed(){return["ArrowUp","ArrowRight","ArrowDown","ArrowLeft"]}}kt=new WeakMap;mo=new WeakMap;Kn=new WeakMap;Vn=new WeakMap;ct=new WeakMap;Bs=new WeakMap;qn=new WeakMap;Yn=new WeakMap;Gn=new WeakMap;Pd=new WeakSet;Ym=function(e){const t=se(this,ct).activeSegment;if(!t)return;const i=this.getPointerRatio(e),r=`#segments-clipping rect:nth-child(${se(this,Bs).findIndex((n,s,o)=>{const l=o[s+1];return l!=null&&i>=n&&i<=l})+1})`;(t.selectorText!=r||!t.style.transform)&&(t.selectorText=r,t.style.setProperty("transform","var(--media-range-segment-hover-transform, scaleY(2))"))};jn=new WeakSet;El=function(){this.hasAttribute("disabled")||(this.addEventListener("input",this),this.addEventListener("pointerdown",this),this.addEventListener("pointerenter",this))};Qn=new WeakSet;_l=function(){var e,t;this.removeEventListener("input",this),this.removeEventListener("pointerdown",this),this.removeEventListener("pointerenter",this),(e=E.window)==null||e.removeEventListener("pointerup",this),(t=E.window)==null||t.removeEventListener("pointermove",this)};Ws=new WeakSet;$d=function(e){var t;li(this,mo,e.composedPath().includes(this.range)),(t=E.window)==null||t.addEventListener("pointerup",this)};bl=new WeakSet;Gm=function(e){var t;e.pointerType!=="mouse"&&ut(this,Ws,$d).call(this,e),this.addEventListener("pointerleave",this),(t=E.window)==null||t.addEventListener("pointermove",this)};gl=new WeakSet;jm=function(){var e;(e=E.window)==null||e.removeEventListener("pointerup",this),this.toggleAttribute("dragging",!1),this.range.disabled=this.hasAttribute("disabled")};yl=new WeakSet;Qm=function(){var e,t;this.removeEventListener("pointerleave",this),(e=E.window)==null||e.removeEventListener("pointermove",this),this.toggleAttribute("dragging",!1),this.range.disabled=this.hasAttribute("disabled"),(t=se(this,ct).activeSegment)==null||t.style.removeProperty("transform")};Tl=new WeakSet;Zm=function(e){this.toggleAttribute("dragging",e.buttons===1||e.pointerType!=="mouse"),this.updatePointerBar(e),ut(this,Pd,Ym).call(this,e),this.dragging&&(e.pointerType!=="mouse"||!se(this,mo))&&(this.range.disabled=!0,this.range.valueAsNumber=this.getPointerRatio(e),this.range.dispatchEvent(new Event("input",{bubbles:!0,composed:!0})))};Ka.shadowRootOptions={mode:"open"};Ka.getTemplateHTML=Hg;E.customElements.get("media-chrome-range")||E.customElements.define("media-chrome-range",Ka);var zm=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},hn=(e,t,i)=>(zm(e,t,"read from private field"),i?i.call(e):t.get(e)),Bg=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},mn=(e,t,i,a)=>(zm(e,t,"write to private field"),t.set(e,i),i),St;function Wg(e){return`
    <style>
      :host {
        
        box-sizing: border-box;
        display: var(--media-control-display, var(--media-control-bar-display, inline-flex));
        color: var(--media-text-color, var(--media-primary-color, rgb(238 238 238)));
        --media-loading-indicator-icon-height: 44px;
      }

      ::slotted(media-time-range),
      ::slotted(media-volume-range) {
        min-height: 100%;
      }

      ::slotted(media-time-range),
      ::slotted(media-clip-selector) {
        flex-grow: 1;
      }

      ::slotted([role="menu"]) {
        position: absolute;
      }
    </style>

    <slot></slot>
  `}class Ud extends E.HTMLElement{constructor(){if(super(),Bg(this,St,void 0),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}}static get observedAttributes(){return[q.MEDIA_CONTROLLER]}attributeChangedCallback(t,i,a){var r,n,s,o,l;t===q.MEDIA_CONTROLLER&&(i&&((n=(r=hn(this,St))==null?void 0:r.unassociateElement)==null||n.call(r,this),mn(this,St,null)),a&&this.isConnected&&(mn(this,St,(s=this.getRootNode())==null?void 0:s.getElementById(a)),(l=(o=hn(this,St))==null?void 0:o.associateElement)==null||l.call(o,this)))}connectedCallback(){var t,i,a;const r=this.getAttribute(q.MEDIA_CONTROLLER);r&&(mn(this,St,(t=this.getRootNode())==null?void 0:t.getElementById(r)),(a=(i=hn(this,St))==null?void 0:i.associateElement)==null||a.call(i,this))}disconnectedCallback(){var t,i;(i=(t=hn(this,St))==null?void 0:t.unassociateElement)==null||i.call(t,this),mn(this,St,null)}}St=new WeakMap;Ud.shadowRootOptions={mode:"open"};Ud.getTemplateHTML=Wg;E.customElements.get("media-control-bar")||E.customElements.define("media-control-bar",Ud);var Xm=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},pn=(e,t,i)=>(Xm(e,t,"read from private field"),i?i.call(e):t.get(e)),Fg=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},vn=(e,t,i,a)=>(Xm(e,t,"write to private field"),t.set(e,i),i),wt;function Kg(e,t={}){return`
    <style>
      :host {
        font: var(--media-font,
          var(--media-font-weight, normal)
          var(--media-font-size, 14px) /
          var(--media-text-content-height, var(--media-control-height, 24px))
          var(--media-font-family, helvetica neue, segoe ui, roboto, arial, sans-serif));
        color: var(--media-text-color, var(--media-primary-color, rgb(238 238 238)));
        background: var(--media-text-background, var(--media-control-background, var(--media-secondary-color, rgb(20 20 30 / .7))));
        padding: var(--media-control-padding, 10px);
        display: inline-flex;
        justify-content: center;
        align-items: center;
        vertical-align: middle;
        box-sizing: border-box;
        text-align: center;
        pointer-events: auto;
      }

      
      :host(:focus-visible) {
        box-shadow: inset 0 0 0 2px rgb(27 127 204 / .9);
        outline: 0;
      }

      
      :host(:where(:focus)) {
        box-shadow: none;
        outline: 0;
      }
    </style>

    ${this.getSlotTemplateHTML(e,t)}
  `}function Vg(e,t){return`
    <slot></slot>
  `}class yi extends E.HTMLElement{constructor(){if(super(),Fg(this,wt,void 0),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}}static get observedAttributes(){return[q.MEDIA_CONTROLLER]}attributeChangedCallback(t,i,a){var r,n,s,o,l;t===q.MEDIA_CONTROLLER&&(i&&((n=(r=pn(this,wt))==null?void 0:r.unassociateElement)==null||n.call(r,this),vn(this,wt,null)),a&&this.isConnected&&(vn(this,wt,(s=this.getRootNode())==null?void 0:s.getElementById(a)),(l=(o=pn(this,wt))==null?void 0:o.associateElement)==null||l.call(o,this)))}connectedCallback(){var t,i,a;const{style:r}=fe(this.shadowRoot,":host");r.setProperty("display",`var(--media-control-display, var(--${this.localName}-display, inline-flex))`);const n=this.getAttribute(q.MEDIA_CONTROLLER);n&&(vn(this,wt,(t=this.getRootNode())==null?void 0:t.getElementById(n)),(a=(i=pn(this,wt))==null?void 0:i.associateElement)==null||a.call(i,this))}disconnectedCallback(){var t,i;(i=(t=pn(this,wt))==null?void 0:t.unassociateElement)==null||i.call(t,this),vn(this,wt,null)}}wt=new WeakMap;yi.shadowRootOptions={mode:"open"};yi.getTemplateHTML=Kg;yi.getSlotTemplateHTML=Vg;E.customElements.get("media-text-display")||E.customElements.define("media-text-display",yi);var Jm=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Tc=(e,t,i)=>(Jm(e,t,"read from private field"),i?i.call(e):t.get(e)),qg=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Yg=(e,t,i,a)=>(Jm(e,t,"write to private field"),t.set(e,i),i),_r;function Gg(e,t){return`
    <slot>${bi(t.mediaDuration)}</slot>
  `}class ep extends yi{constructor(){var t;super(),qg(this,_r,void 0),Yg(this,_r,this.shadowRoot.querySelector("slot")),Tc(this,_r).textContent=bi((t=this.mediaDuration)!=null?t:0)}static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_DURATION]}attributeChangedCallback(t,i,a){t===u.MEDIA_DURATION&&(Tc(this,_r).textContent=bi(+a)),super.attributeChangedCallback(t,i,a)}get mediaDuration(){return ie(this,u.MEDIA_DURATION)}set mediaDuration(t){de(this,u.MEDIA_DURATION,t)}}_r=new WeakMap;ep.getSlotTemplateHTML=Gg;E.customElements.get("media-duration-display")||E.customElements.define("media-duration-display",ep);const jg={2:C("Network Error"),3:C("Decode Error"),4:C("Source Not Supported"),5:C("Encryption Error")},Qg={2:C("A network error caused the media download to fail."),3:C("A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format."),4:C("An unsupported error occurred. The server or network failed, or your browser does not support this format."),5:C("The media is encrypted and there are no keys to decrypt it.")},tp=e=>{var t,i;return e.code===1?null:{title:(t=jg[e.code])!=null?t:`Error ${e.code}`,message:(i=Qg[e.code])!=null?i:e.message}};var ip=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Zg=(e,t,i)=>(ip(e,t,"read from private field"),i?i.call(e):t.get(e)),zg=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Xg=(e,t,i,a)=>(ip(e,t,"write to private field"),t.set(e,i),i),Zn;function Jg(e){return`
    <style>
      :host {
        background: rgb(20 20 30 / .8);
      }

      #content {
        display: block;
        padding: 1.2em 1.5em;
      }

      h3,
      p {
        margin-block: 0 .3em;
      }
    </style>
    <slot name="error-${e.mediaerrorcode}" id="content">
      ${ap({code:+e.mediaerrorcode,message:e.mediaerrormessage})}
    </slot>
  `}function e0(e){return e.code&&tp(e)!==null}function ap(e){var t;const{title:i,message:a}=(t=tp(e))!=null?t:{};let r="";return i&&(r+=`<slot name="error-${e.code}-title"><h3>${i}</h3></slot>`),a&&(r+=`<slot name="error-${e.code}-message"><p>${a}</p></slot>`),r}const Ac=[u.MEDIA_ERROR_CODE,u.MEDIA_ERROR_MESSAGE];class po extends rn{constructor(){super(...arguments),zg(this,Zn,null)}static get observedAttributes(){return[...super.observedAttributes,...Ac]}formatErrorMessage(t){return this.constructor.formatErrorMessage(t)}attributeChangedCallback(t,i,a){var r;if(super.attributeChangedCallback(t,i,a),!Ac.includes(t))return;const n=(r=this.mediaError)!=null?r:{code:this.mediaErrorCode,message:this.mediaErrorMessage};this.open=e0(n),this.open&&(this.shadowRoot.querySelector("slot").name=`error-${this.mediaErrorCode}`,this.shadowRoot.querySelector("#content").innerHTML=this.formatErrorMessage(n))}get mediaError(){return Zg(this,Zn)}set mediaError(t){Xg(this,Zn,t)}get mediaErrorCode(){return ie(this,"mediaerrorcode")}set mediaErrorCode(t){de(this,"mediaerrorcode",t)}get mediaErrorMessage(){return ae(this,"mediaerrormessage")}set mediaErrorMessage(t){re(this,"mediaerrormessage",t)}}Zn=new WeakMap;po.getSlotTemplateHTML=Jg;po.formatErrorMessage=ap;E.customElements.get("media-error-dialog")||E.customElements.define("media-error-dialog",po);var rp=po;const t0=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M16 3v2.5h3.5V9H22V3h-6ZM4 9h2.5V5.5H10V3H4v6Zm15.5 9.5H16V21h6v-6h-2.5v3.5ZM6.5 15H4v6h6v-2.5H6.5V15Z"/>
</svg>`,i0=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M18.5 6.5V3H16v6h6V6.5h-3.5ZM16 21h2.5v-3.5H22V15h-6v6ZM4 17.5h3.5V21H10v-6H4v2.5Zm3.5-11H4V9h6V3H7.5v3.5Z"/>
</svg>`;function a0(e){return`
    <style>
      :host([${u.MEDIA_IS_FULLSCREEN}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      
      :host(:not([${u.MEDIA_IS_FULLSCREEN}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${u.MEDIA_IS_FULLSCREEN}]) slot[name=tooltip-enter],
      :host(:not([${u.MEDIA_IS_FULLSCREEN}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${t0}</slot>
      <slot name="exit">${i0}</slot>
    </slot>
  `}function r0(){return`
    <slot name="tooltip-enter">${C("Enter fullscreen mode")}</slot>
    <slot name="tooltip-exit">${C("Exit fullscreen mode")}</slot>
  `}const kc=e=>{const t=e.mediaIsFullscreen?C("exit fullscreen mode"):C("enter fullscreen mode");e.setAttribute("aria-label",t)};class Hd extends Ce{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_IS_FULLSCREEN,u.MEDIA_FULLSCREEN_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),kc(this)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_IS_FULLSCREEN&&kc(this)}get mediaFullscreenUnavailable(){return ae(this,u.MEDIA_FULLSCREEN_UNAVAILABLE)}set mediaFullscreenUnavailable(t){re(this,u.MEDIA_FULLSCREEN_UNAVAILABLE,t)}get mediaIsFullscreen(){return F(this,u.MEDIA_IS_FULLSCREEN)}set mediaIsFullscreen(t){K(this,u.MEDIA_IS_FULLSCREEN,t)}handleClick(){const t=this.mediaIsFullscreen?R.MEDIA_EXIT_FULLSCREEN_REQUEST:R.MEDIA_ENTER_FULLSCREEN_REQUEST;this.dispatchEvent(new E.CustomEvent(t,{composed:!0,bubbles:!0}))}}Hd.getSlotTemplateHTML=a0;Hd.getTooltipContentHTML=r0;E.customElements.get("media-fullscreen-button")||E.customElements.define("media-fullscreen-button",Hd);const{MEDIA_TIME_IS_LIVE:zn,MEDIA_PAUSED:xr}=u,{MEDIA_SEEK_TO_LIVE_REQUEST:n0,MEDIA_PLAY_REQUEST:s0}=R,o0='<svg viewBox="0 0 6 12"><circle cx="3" cy="6" r="2"></circle></svg>';function l0(e){return`
    <style>
      :host { --media-tooltip-display: none; }
      
      slot[name=indicator] > *,
      :host ::slotted([slot=indicator]) {
        
        min-width: auto;
        fill: var(--media-live-button-icon-color, rgb(140, 140, 140));
        color: var(--media-live-button-icon-color, rgb(140, 140, 140));
      }

      :host([${zn}]:not([${xr}])) slot[name=indicator] > *,
      :host([${zn}]:not([${xr}])) ::slotted([slot=indicator]) {
        fill: var(--media-live-button-indicator-color, rgb(255, 0, 0));
        color: var(--media-live-button-indicator-color, rgb(255, 0, 0));
      }

      :host([${zn}]:not([${xr}])) {
        cursor: var(--media-cursor, not-allowed);
      }

      slot[name=text]{
        text-transform: uppercase;
      }

    </style>

    <slot name="indicator">${o0}</slot>
    
    <slot name="spacer">&nbsp;</slot><slot name="text">${C("live")}</slot>
  `}const Sc=e=>{const t=e.mediaPaused||!e.mediaTimeIsLive,i=C(t?"seek to live":"playing live");e.setAttribute("aria-label",i),t?e.removeAttribute("aria-disabled"):e.setAttribute("aria-disabled","true")};class np extends Ce{static get observedAttributes(){return[...super.observedAttributes,zn,xr]}connectedCallback(){super.connectedCallback(),Sc(this)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),Sc(this)}get mediaPaused(){return F(this,u.MEDIA_PAUSED)}set mediaPaused(t){K(this,u.MEDIA_PAUSED,t)}get mediaTimeIsLive(){return F(this,u.MEDIA_TIME_IS_LIVE)}set mediaTimeIsLive(t){K(this,u.MEDIA_TIME_IS_LIVE,t)}handleClick(){!this.mediaPaused&&this.mediaTimeIsLive||(this.dispatchEvent(new E.CustomEvent(n0,{composed:!0,bubbles:!0})),this.hasAttribute(xr)&&this.dispatchEvent(new E.CustomEvent(s0,{composed:!0,bubbles:!0})))}}np.getSlotTemplateHTML=l0;E.customElements.get("media-live-button")||E.customElements.define("media-live-button",np);var sp=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Xa=(e,t,i)=>(sp(e,t,"read from private field"),i?i.call(e):t.get(e)),wc=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Ja=(e,t,i,a)=>(sp(e,t,"write to private field"),t.set(e,i),i),It,Xn;const fn={LOADING_DELAY:"loadingdelay",NO_AUTOHIDE:"noautohide"},op=500,d0=`
<svg aria-hidden="true" viewBox="0 0 100 100">
  <path d="M73,50c0-12.7-10.3-23-23-23S27,37.3,27,50 M30.9,50c0-10.5,8.5-19.1,19.1-19.1S69.1,39.5,69.1,50">
    <animateTransform
       attributeName="transform"
       attributeType="XML"
       type="rotate"
       dur="1s"
       from="0 50 50"
       to="360 50 50"
       repeatCount="indefinite" />
  </path>
</svg>
`;function u0(e){return`
    <style>
      :host {
        display: var(--media-control-display, var(--media-loading-indicator-display, inline-block));
        vertical-align: middle;
        box-sizing: border-box;
        --_loading-indicator-delay: var(--media-loading-indicator-transition-delay, ${op}ms);
      }

      #status {
        color: rgba(0,0,0,0);
        width: 0px;
        height: 0px;
      }

      :host slot[name=icon] > *,
      :host ::slotted([slot=icon]) {
        opacity: var(--media-loading-indicator-opacity, 0);
        transition: opacity 0.15s;
      }

      :host([${u.MEDIA_LOADING}]:not([${u.MEDIA_PAUSED}])) slot[name=icon] > *,
      :host([${u.MEDIA_LOADING}]:not([${u.MEDIA_PAUSED}])) ::slotted([slot=icon]) {
        opacity: var(--media-loading-indicator-opacity, 1);
        transition: opacity 0.15s var(--_loading-indicator-delay);
      }

      :host #status {
        visibility: var(--media-loading-indicator-opacity, hidden);
        transition: visibility 0.15s;
      }

      :host([${u.MEDIA_LOADING}]:not([${u.MEDIA_PAUSED}])) #status {
        visibility: var(--media-loading-indicator-opacity, visible);
        transition: visibility 0.15s var(--_loading-indicator-delay);
      }

      svg, img, ::slotted(svg), ::slotted(img) {
        width: var(--media-loading-indicator-icon-width);
        height: var(--media-loading-indicator-icon-height, 100px);
        fill: var(--media-icon-color, var(--media-primary-color, rgb(238 238 238)));
        vertical-align: middle;
      }
    </style>

    <slot name="icon">${d0}</slot>
    <div id="status" role="status" aria-live="polite">${C("media loading")}</div>
  `}class Bd extends E.HTMLElement{constructor(){if(super(),wc(this,It,void 0),wc(this,Xn,op),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}}static get observedAttributes(){return[q.MEDIA_CONTROLLER,u.MEDIA_PAUSED,u.MEDIA_LOADING,fn.LOADING_DELAY]}attributeChangedCallback(t,i,a){var r,n,s,o,l;t===fn.LOADING_DELAY&&i!==a?this.loadingDelay=Number(a):t===q.MEDIA_CONTROLLER&&(i&&((n=(r=Xa(this,It))==null?void 0:r.unassociateElement)==null||n.call(r,this),Ja(this,It,null)),a&&this.isConnected&&(Ja(this,It,(s=this.getRootNode())==null?void 0:s.getElementById(a)),(l=(o=Xa(this,It))==null?void 0:o.associateElement)==null||l.call(o,this)))}connectedCallback(){var t,i,a;const r=this.getAttribute(q.MEDIA_CONTROLLER);r&&(Ja(this,It,(t=this.getRootNode())==null?void 0:t.getElementById(r)),(a=(i=Xa(this,It))==null?void 0:i.associateElement)==null||a.call(i,this))}disconnectedCallback(){var t,i;(i=(t=Xa(this,It))==null?void 0:t.unassociateElement)==null||i.call(t,this),Ja(this,It,null)}get loadingDelay(){return Xa(this,Xn)}set loadingDelay(t){Ja(this,Xn,t);const{style:i}=fe(this.shadowRoot,":host");i.setProperty("--_loading-indicator-delay",`var(--media-loading-indicator-transition-delay, ${t}ms)`)}get mediaPaused(){return F(this,u.MEDIA_PAUSED)}set mediaPaused(t){K(this,u.MEDIA_PAUSED,t)}get mediaLoading(){return F(this,u.MEDIA_LOADING)}set mediaLoading(t){K(this,u.MEDIA_LOADING,t)}get mediaController(){return ae(this,q.MEDIA_CONTROLLER)}set mediaController(t){re(this,q.MEDIA_CONTROLLER,t)}get noAutohide(){return F(this,fn.NO_AUTOHIDE)}set noAutohide(t){K(this,fn.NO_AUTOHIDE,t)}}It=new WeakMap;Xn=new WeakMap;Bd.shadowRootOptions={mode:"open"};Bd.getTemplateHTML=u0;E.customElements.get("media-loading-indicator")||E.customElements.define("media-loading-indicator",Bd);const c0=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M16.5 12A4.5 4.5 0 0 0 14 8v2.18l2.45 2.45a4.22 4.22 0 0 0 .05-.63Zm2.5 0a6.84 6.84 0 0 1-.54 2.64L20 16.15A8.8 8.8 0 0 0 21 12a9 9 0 0 0-7-8.77v2.06A7 7 0 0 1 19 12ZM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25A6.92 6.92 0 0 1 14 18.7v2.06A9 9 0 0 0 17.69 19l2 2.05L21 19.73l-9-9L4.27 3ZM12 4 9.91 6.09 12 8.18V4Z"/>
</svg>`,Ic=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M3 9v6h4l5 5V4L7 9H3Zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4Z"/>
</svg>`,h0=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M3 9v6h4l5 5V4L7 9H3Zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4ZM14 3.23v2.06a7 7 0 0 1 0 13.42v2.06a9 9 0 0 0 0-17.54Z"/>
</svg>`;function m0(e){return`
    <style>
      :host(:not([${u.MEDIA_VOLUME_LEVEL}])) slot[name=icon] slot:not([name=high]),
      :host([${u.MEDIA_VOLUME_LEVEL}=high]) slot[name=icon] slot:not([name=high]) {
        display: none !important;
      }

      :host([${u.MEDIA_VOLUME_LEVEL}=off]) slot[name=icon] slot:not([name=off]) {
        display: none !important;
      }

      :host([${u.MEDIA_VOLUME_LEVEL}=low]) slot[name=icon] slot:not([name=low]) {
        display: none !important;
      }

      :host([${u.MEDIA_VOLUME_LEVEL}=medium]) slot[name=icon] slot:not([name=medium]) {
        display: none !important;
      }

      :host(:not([${u.MEDIA_VOLUME_LEVEL}=off])) slot[name=tooltip-unmute],
      :host([${u.MEDIA_VOLUME_LEVEL}=off]) slot[name=tooltip-mute] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="off">${c0}</slot>
      <slot name="low">${Ic}</slot>
      <slot name="medium">${Ic}</slot>
      <slot name="high">${h0}</slot>
    </slot>
  `}function p0(){return`
    <slot name="tooltip-mute">${C("Mute")}</slot>
    <slot name="tooltip-unmute">${C("Unmute")}</slot>
  `}const Rc=e=>{const t=e.mediaVolumeLevel==="off",i=C(t?"unmute":"mute");e.setAttribute("aria-label",i)};class Wd extends Ce{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_VOLUME_LEVEL]}connectedCallback(){super.connectedCallback(),Rc(this)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_VOLUME_LEVEL&&Rc(this)}get mediaVolumeLevel(){return ae(this,u.MEDIA_VOLUME_LEVEL)}set mediaVolumeLevel(t){re(this,u.MEDIA_VOLUME_LEVEL,t)}handleClick(){const t=this.mediaVolumeLevel==="off"?R.MEDIA_UNMUTE_REQUEST:R.MEDIA_MUTE_REQUEST;this.dispatchEvent(new E.CustomEvent(t,{composed:!0,bubbles:!0}))}}Wd.getSlotTemplateHTML=m0;Wd.getTooltipContentHTML=p0;E.customElements.get("media-mute-button")||E.customElements.define("media-mute-button",Wd);const Cc=`<svg aria-hidden="true" viewBox="0 0 28 24">
  <path d="M24 3H4a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h20a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1Zm-1 16H5V5h18v14Zm-3-8h-7v5h7v-5Z"/>
</svg>`;function v0(e){return`
    <style>
      :host([${u.MEDIA_IS_PIP}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      :host(:not([${u.MEDIA_IS_PIP}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${u.MEDIA_IS_PIP}]) slot[name=tooltip-enter],
      :host(:not([${u.MEDIA_IS_PIP}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${Cc}</slot>
      <slot name="exit">${Cc}</slot>
    </slot>
  `}function f0(){return`
    <slot name="tooltip-enter">${C("Enter picture in picture mode")}</slot>
    <slot name="tooltip-exit">${C("Exit picture in picture mode")}</slot>
  `}const Dc=e=>{const t=e.mediaIsPip?C("exit picture in picture mode"):C("enter picture in picture mode");e.setAttribute("aria-label",t)};class Fd extends Ce{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_IS_PIP,u.MEDIA_PIP_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),Dc(this)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_IS_PIP&&Dc(this)}get mediaPipUnavailable(){return ae(this,u.MEDIA_PIP_UNAVAILABLE)}set mediaPipUnavailable(t){re(this,u.MEDIA_PIP_UNAVAILABLE,t)}get mediaIsPip(){return F(this,u.MEDIA_IS_PIP)}set mediaIsPip(t){K(this,u.MEDIA_IS_PIP,t)}handleClick(){const t=this.mediaIsPip?R.MEDIA_EXIT_PIP_REQUEST:R.MEDIA_ENTER_PIP_REQUEST;this.dispatchEvent(new E.CustomEvent(t,{composed:!0,bubbles:!0}))}}Fd.getSlotTemplateHTML=v0;Fd.getTooltipContentHTML=f0;E.customElements.get("media-pip-button")||E.customElements.define("media-pip-button",Fd);var E0=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},ta=(e,t,i)=>(E0(e,t,"read from private field"),i?i.call(e):t.get(e)),_0=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},di;const Lo={RATES:"rates"},lp=[1,1.2,1.5,1.7,2],Sa=1;function b0(e){return`
    <style>
      :host {
        min-width: 5ch;
        padding: var(--media-button-padding, var(--media-control-padding, 10px 5px));
      }
    </style>
    <slot name="icon">${e.mediaplaybackrate||Sa}x</slot>
  `}function g0(){return C("Playback rate")}class Kd extends Ce{constructor(){var t;super(),_0(this,di,new Sd(this,Lo.RATES,{defaultValue:lp})),this.container=this.shadowRoot.querySelector('slot[name="icon"]'),this.container.innerHTML=`${(t=this.mediaPlaybackRate)!=null?t:Sa}x`}static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_PLAYBACK_RATE,Lo.RATES]}attributeChangedCallback(t,i,a){if(super.attributeChangedCallback(t,i,a),t===Lo.RATES&&(ta(this,di).value=a),t===u.MEDIA_PLAYBACK_RATE){const r=a?+a:Number.NaN,n=Number.isNaN(r)?Sa:r;this.container.innerHTML=`${n}x`,this.setAttribute("aria-label",C("Playback rate {playbackRate}",{playbackRate:n}))}}get rates(){return ta(this,di)}set rates(t){t?Array.isArray(t)?ta(this,di).value=t.join(" "):typeof t=="string"&&(ta(this,di).value=t):ta(this,di).value=""}get mediaPlaybackRate(){return ie(this,u.MEDIA_PLAYBACK_RATE,Sa)}set mediaPlaybackRate(t){de(this,u.MEDIA_PLAYBACK_RATE,t)}handleClick(){var t,i;const a=Array.from(ta(this,di).values(),s=>+s).sort((s,o)=>s-o),r=(i=(t=a.find(s=>s>this.mediaPlaybackRate))!=null?t:a[0])!=null?i:Sa,n=new E.CustomEvent(R.MEDIA_PLAYBACK_RATE_REQUEST,{composed:!0,bubbles:!0,detail:r});this.dispatchEvent(n)}}di=new WeakMap;Kd.getSlotTemplateHTML=b0;Kd.getTooltipContentHTML=g0;E.customElements.get("media-playback-rate-button")||E.customElements.define("media-playback-rate-button",Kd);const y0=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="m6 21 15-9L6 3v18Z"/>
</svg>`,T0=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M6 20h4V4H6v16Zm8-16v16h4V4h-4Z"/>
</svg>`;function A0(e){return`
    <style>
      :host([${u.MEDIA_PAUSED}]) slot[name=pause],
      :host(:not([${u.MEDIA_PAUSED}])) slot[name=play] {
        display: none !important;
      }

      :host([${u.MEDIA_PAUSED}]) slot[name=tooltip-pause],
      :host(:not([${u.MEDIA_PAUSED}])) slot[name=tooltip-play] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="play">${y0}</slot>
      <slot name="pause">${T0}</slot>
    </slot>
  `}function k0(){return`
    <slot name="tooltip-play">${C("Play")}</slot>
    <slot name="tooltip-pause">${C("Pause")}</slot>
  `}const Lc=e=>{const t=e.mediaPaused?C("play"):C("pause");e.setAttribute("aria-label",t)};class Vd extends Ce{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_PAUSED,u.MEDIA_ENDED]}connectedCallback(){super.connectedCallback(),Lc(this)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_PAUSED&&Lc(this)}get mediaPaused(){return F(this,u.MEDIA_PAUSED)}set mediaPaused(t){K(this,u.MEDIA_PAUSED,t)}handleClick(){const t=this.mediaPaused?R.MEDIA_PLAY_REQUEST:R.MEDIA_PAUSE_REQUEST;this.dispatchEvent(new E.CustomEvent(t,{composed:!0,bubbles:!0}))}}Vd.getSlotTemplateHTML=A0;Vd.getTooltipContentHTML=k0;E.customElements.get("media-play-button")||E.customElements.define("media-play-button",Vd);const _t={PLACEHOLDER_SRC:"placeholdersrc",SRC:"src"};function S0(e){return`
    <style>
      :host {
        pointer-events: none;
        display: var(--media-poster-image-display, inline-block);
        box-sizing: border-box;
      }

      img {
        max-width: 100%;
        max-height: 100%;
        min-width: 100%;
        min-height: 100%;
        background-repeat: no-repeat;
        background-position: var(--media-poster-image-background-position, var(--media-object-position, center));
        background-size: var(--media-poster-image-background-size, var(--media-object-fit, contain));
        object-fit: var(--media-object-fit, contain);
        object-position: var(--media-object-position, center);
      }
    </style>

    <img part="poster img" aria-hidden="true" id="image"/>
  `}const w0=e=>{e.style.removeProperty("background-image")},I0=(e,t)=>{e.style["background-image"]=`url('${t}')`};class qd extends E.HTMLElement{static get observedAttributes(){return[_t.PLACEHOLDER_SRC,_t.SRC]}constructor(){if(super(),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}this.image=this.shadowRoot.querySelector("#image")}attributeChangedCallback(t,i,a){t===_t.SRC&&(a==null?this.image.removeAttribute(_t.SRC):this.image.setAttribute(_t.SRC,a)),t===_t.PLACEHOLDER_SRC&&(a==null?w0(this.image):I0(this.image,a))}get placeholderSrc(){return ae(this,_t.PLACEHOLDER_SRC)}set placeholderSrc(t){re(this,_t.SRC,t)}get src(){return ae(this,_t.SRC)}set src(t){re(this,_t.SRC,t)}}qd.shadowRootOptions={mode:"open"};qd.getTemplateHTML=S0;E.customElements.get("media-poster-image")||E.customElements.define("media-poster-image",qd);var dp=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},R0=(e,t,i)=>(dp(e,t,"read from private field"),i?i.call(e):t.get(e)),C0=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},D0=(e,t,i,a)=>(dp(e,t,"write to private field"),t.set(e,i),i),Jn;class L0 extends yi{constructor(){super(),C0(this,Jn,void 0),D0(this,Jn,this.shadowRoot.querySelector("slot"))}static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_PREVIEW_CHAPTER]}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_PREVIEW_CHAPTER&&a!==i&&a!=null&&(R0(this,Jn).textContent=a,a!==""?this.setAttribute("aria-valuetext",`chapter: ${a}`):this.removeAttribute("aria-valuetext"))}get mediaPreviewChapter(){return ae(this,u.MEDIA_PREVIEW_CHAPTER)}set mediaPreviewChapter(t){re(this,u.MEDIA_PREVIEW_CHAPTER,t)}}Jn=new WeakMap;E.customElements.get("media-preview-chapter-display")||E.customElements.define("media-preview-chapter-display",L0);var up=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},En=(e,t,i)=>(up(e,t,"read from private field"),i?i.call(e):t.get(e)),M0=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},_n=(e,t,i,a)=>(up(e,t,"write to private field"),t.set(e,i),i),Rt;function x0(e){return`
    <style>
      :host {
        box-sizing: border-box;
        display: var(--media-control-display, var(--media-preview-thumbnail-display, inline-block));
        overflow: hidden;
      }

      img {
        display: none;
        position: relative;
      }
    </style>
    <img crossorigin loading="eager" decoding="async">
  `}class vo extends E.HTMLElement{constructor(){if(super(),M0(this,Rt,void 0),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}}static get observedAttributes(){return[q.MEDIA_CONTROLLER,u.MEDIA_PREVIEW_IMAGE,u.MEDIA_PREVIEW_COORDS]}connectedCallback(){var t,i,a;const r=this.getAttribute(q.MEDIA_CONTROLLER);r&&(_n(this,Rt,(t=this.getRootNode())==null?void 0:t.getElementById(r)),(a=(i=En(this,Rt))==null?void 0:i.associateElement)==null||a.call(i,this))}disconnectedCallback(){var t,i;(i=(t=En(this,Rt))==null?void 0:t.unassociateElement)==null||i.call(t,this),_n(this,Rt,null)}attributeChangedCallback(t,i,a){var r,n,s,o,l;[u.MEDIA_PREVIEW_IMAGE,u.MEDIA_PREVIEW_COORDS].includes(t)&&this.update(),t===q.MEDIA_CONTROLLER&&(i&&((n=(r=En(this,Rt))==null?void 0:r.unassociateElement)==null||n.call(r,this),_n(this,Rt,null)),a&&this.isConnected&&(_n(this,Rt,(s=this.getRootNode())==null?void 0:s.getElementById(a)),(l=(o=En(this,Rt))==null?void 0:o.associateElement)==null||l.call(o,this)))}get mediaPreviewImage(){return ae(this,u.MEDIA_PREVIEW_IMAGE)}set mediaPreviewImage(t){re(this,u.MEDIA_PREVIEW_IMAGE,t)}get mediaPreviewCoords(){const t=this.getAttribute(u.MEDIA_PREVIEW_COORDS);if(t)return t.split(/\s+/).map(i=>+i)}set mediaPreviewCoords(t){if(!t){this.removeAttribute(u.MEDIA_PREVIEW_COORDS);return}this.setAttribute(u.MEDIA_PREVIEW_COORDS,t.join(" "))}update(){const t=this.mediaPreviewCoords,i=this.mediaPreviewImage;if(!(t&&i))return;const[a,r,n,s]=t,o=i.split("#")[0],l=getComputedStyle(this),{maxWidth:d,maxHeight:m,minWidth:p,minHeight:h}=l,c=Math.min(parseInt(d)/n,parseInt(m)/s),v=Math.max(parseInt(p)/n,parseInt(h)/s),g=c<1,_=g?c:v>1?v:1,{style:y}=fe(this.shadowRoot,":host"),T=fe(this.shadowRoot,"img").style,f=this.shadowRoot.querySelector("img"),S=g?"min":"max";y.setProperty(`${S}-width`,"initial","important"),y.setProperty(`${S}-height`,"initial","important"),y.width=`${n*_}px`,y.height=`${s*_}px`;const D=()=>{T.width=`${this.imgWidth*_}px`,T.height=`${this.imgHeight*_}px`,T.display="block"};f.src!==o&&(f.onload=()=>{this.imgWidth=f.naturalWidth,this.imgHeight=f.naturalHeight,D()},f.src=o,D()),D(),T.transform=`translate(-${a*_}px, -${r*_}px)`}}Rt=new WeakMap;vo.shadowRootOptions={mode:"open"};vo.getTemplateHTML=x0;E.customElements.get("media-preview-thumbnail")||E.customElements.define("media-preview-thumbnail",vo);var Mc=vo,cp=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},xc=(e,t,i)=>(cp(e,t,"read from private field"),i?i.call(e):t.get(e)),O0=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},N0=(e,t,i,a)=>(cp(e,t,"write to private field"),t.set(e,i),i),br;class P0 extends yi{constructor(){super(),O0(this,br,void 0),N0(this,br,this.shadowRoot.querySelector("slot")),xc(this,br).textContent=bi(0)}static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_PREVIEW_TIME]}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_PREVIEW_TIME&&a!=null&&(xc(this,br).textContent=bi(parseFloat(a)))}get mediaPreviewTime(){return ie(this,u.MEDIA_PREVIEW_TIME)}set mediaPreviewTime(t){de(this,u.MEDIA_PREVIEW_TIME,t)}}br=new WeakMap;E.customElements.get("media-preview-time-display")||E.customElements.define("media-preview-time-display",P0);const ia={SEEK_OFFSET:"seekoffset"},Mo=30,$0=e=>`
  <svg aria-hidden="true" viewBox="0 0 20 24">
    <defs>
      <style>.text{font-size:8px;font-family:Arial-BoldMT, Arial;font-weight:700;}</style>
    </defs>
    <text class="text value" transform="translate(2.18 19.87)">${e}</text>
    <path d="M10 6V3L4.37 7 10 10.94V8a5.54 5.54 0 0 1 1.9 10.48v2.12A7.5 7.5 0 0 0 10 6Z"/>
  </svg>`;function U0(e,t){return`
    <slot name="icon">${$0(t.seekOffset)}</slot>
  `}function H0(){return C("Seek backward")}const B0=0;class Yd extends Ce{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_CURRENT_TIME,ia.SEEK_OFFSET]}connectedCallback(){super.connectedCallback(),this.seekOffset=ie(this,ia.SEEK_OFFSET,Mo)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===ia.SEEK_OFFSET&&(this.seekOffset=ie(this,ia.SEEK_OFFSET,Mo))}get seekOffset(){return ie(this,ia.SEEK_OFFSET,Mo)}set seekOffset(t){de(this,ia.SEEK_OFFSET,t),this.setAttribute("aria-label",C("seek back {seekOffset} seconds",{seekOffset:this.seekOffset})),bm(gm(this,"icon"),this.seekOffset)}get mediaCurrentTime(){return ie(this,u.MEDIA_CURRENT_TIME,B0)}set mediaCurrentTime(t){de(this,u.MEDIA_CURRENT_TIME,t)}handleClick(){const t=Math.max(this.mediaCurrentTime-this.seekOffset,0),i=new E.CustomEvent(R.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:t});this.dispatchEvent(i)}}Yd.getSlotTemplateHTML=U0;Yd.getTooltipContentHTML=H0;E.customElements.get("media-seek-backward-button")||E.customElements.define("media-seek-backward-button",Yd);const aa={SEEK_OFFSET:"seekoffset"},xo=30,W0=e=>`
  <svg aria-hidden="true" viewBox="0 0 20 24">
    <defs>
      <style>.text{font-size:8px;font-family:Arial-BoldMT, Arial;font-weight:700;}</style>
    </defs>
    <text class="text value" transform="translate(8.9 19.87)">${e}</text>
    <path d="M10 6V3l5.61 4L10 10.94V8a5.54 5.54 0 0 0-1.9 10.48v2.12A7.5 7.5 0 0 1 10 6Z"/>
  </svg>`;function F0(e,t){return`
    <slot name="icon">${W0(t.seekOffset)}</slot>
  `}function K0(){return C("Seek forward")}const V0=0;class Gd extends Ce{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_CURRENT_TIME,aa.SEEK_OFFSET]}connectedCallback(){super.connectedCallback(),this.seekOffset=ie(this,aa.SEEK_OFFSET,xo)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===aa.SEEK_OFFSET&&(this.seekOffset=ie(this,aa.SEEK_OFFSET,xo))}get seekOffset(){return ie(this,aa.SEEK_OFFSET,xo)}set seekOffset(t){de(this,aa.SEEK_OFFSET,t),this.setAttribute("aria-label",C("seek forward {seekOffset} seconds",{seekOffset:this.seekOffset})),bm(gm(this,"icon"),this.seekOffset)}get mediaCurrentTime(){return ie(this,u.MEDIA_CURRENT_TIME,V0)}set mediaCurrentTime(t){de(this,u.MEDIA_CURRENT_TIME,t)}handleClick(){const t=this.mediaCurrentTime+this.seekOffset,i=new E.CustomEvent(R.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:t});this.dispatchEvent(i)}}Gd.getSlotTemplateHTML=F0;Gd.getTooltipContentHTML=K0;E.customElements.get("media-seek-forward-button")||E.customElements.define("media-seek-forward-button",Gd);var hp=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Oo=(e,t,i)=>(hp(e,t,"read from private field"),i?i.call(e):t.get(e)),q0=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Y0=(e,t,i,a)=>(hp(e,t,"write to private field"),t.set(e,i),i),ua;const Ri={REMAINING:"remaining",SHOW_DURATION:"showduration",NO_TOGGLE:"notoggle"},Oc=[...Object.values(Ri),u.MEDIA_CURRENT_TIME,u.MEDIA_DURATION,u.MEDIA_SEEKABLE],Nc=["Enter"," "],G0="&nbsp;/&nbsp;",Al=(e,{timesSep:t=G0}={})=>{var i,a;const r=(i=e.mediaCurrentTime)!=null?i:0,[,n]=(a=e.mediaSeekable)!=null?a:[];let s=0;Number.isFinite(e.mediaDuration)?s=e.mediaDuration:Number.isFinite(n)&&(s=n);const o=e.remaining?bi(0-(s-r)):bi(r);return e.showDuration?`${o}${t}${bi(s)}`:o},j0="video not loaded, unknown time.",Q0=e=>{var t;const i=e.mediaCurrentTime,[,a]=(t=e.mediaSeekable)!=null?t:[];let r=null;if(Number.isFinite(e.mediaDuration)?r=e.mediaDuration:Number.isFinite(a)&&(r=a),i==null||r===null){e.setAttribute("aria-valuetext",j0);return}const n=e.remaining?Lr(0-(r-i)):Lr(i);if(!e.showDuration){e.setAttribute("aria-valuetext",n);return}const s=Lr(r),o=`${n} of ${s}`;e.setAttribute("aria-valuetext",o)};function Z0(e,t){return`
    <slot>${Al(t)}</slot>
  `}class mp extends yi{constructor(){super(),q0(this,ua,void 0),Y0(this,ua,this.shadowRoot.querySelector("slot")),Oo(this,ua).innerHTML=`${Al(this)}`}static get observedAttributes(){return[...super.observedAttributes,...Oc,"disabled"]}connectedCallback(){const{style:t}=fe(this.shadowRoot,":host(:hover:not([notoggle]))");t.setProperty("cursor","var(--media-cursor, pointer)"),t.setProperty("background","var(--media-control-hover-background, rgba(50 50 70 / .7))"),this.hasAttribute("disabled")||this.enable(),this.setAttribute("role","progressbar"),this.setAttribute("aria-label",C("playback time"));const i=a=>{const{key:r}=a;if(!Nc.includes(r)){this.removeEventListener("keyup",i);return}this.toggleTimeDisplay()};this.addEventListener("keydown",a=>{const{metaKey:r,altKey:n,key:s}=a;if(r||n||!Nc.includes(s)){this.removeEventListener("keyup",i);return}this.addEventListener("keyup",i)}),this.addEventListener("click",this.toggleTimeDisplay),super.connectedCallback()}toggleTimeDisplay(){this.noToggle||(this.hasAttribute("remaining")?this.removeAttribute("remaining"):this.setAttribute("remaining",""))}disconnectedCallback(){this.disable(),super.disconnectedCallback()}attributeChangedCallback(t,i,a){Oc.includes(t)?this.update():t==="disabled"&&a!==i&&(a==null?this.enable():this.disable()),super.attributeChangedCallback(t,i,a)}enable(){this.tabIndex=0}disable(){this.tabIndex=-1}get remaining(){return F(this,Ri.REMAINING)}set remaining(t){K(this,Ri.REMAINING,t)}get showDuration(){return F(this,Ri.SHOW_DURATION)}set showDuration(t){K(this,Ri.SHOW_DURATION,t)}get noToggle(){return F(this,Ri.NO_TOGGLE)}set noToggle(t){K(this,Ri.NO_TOGGLE,t)}get mediaDuration(){return ie(this,u.MEDIA_DURATION)}set mediaDuration(t){de(this,u.MEDIA_DURATION,t)}get mediaCurrentTime(){return ie(this,u.MEDIA_CURRENT_TIME)}set mediaCurrentTime(t){de(this,u.MEDIA_CURRENT_TIME,t)}get mediaSeekable(){const t=this.getAttribute(u.MEDIA_SEEKABLE);if(t)return t.split(":").map(i=>+i)}set mediaSeekable(t){if(t==null){this.removeAttribute(u.MEDIA_SEEKABLE);return}this.setAttribute(u.MEDIA_SEEKABLE,t.join(":"))}update(){const t=Al(this);Q0(this),t!==Oo(this,ua).innerHTML&&(Oo(this,ua).innerHTML=t)}}ua=new WeakMap;mp.getSlotTemplateHTML=Z0;E.customElements.get("media-time-display")||E.customElements.define("media-time-display",mp);var pp=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},ke=(e,t,i)=>(pp(e,t,"read from private field"),t.get(e)),bt=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},qe=(e,t,i,a)=>(pp(e,t,"write to private field"),t.set(e,i),i),z0=(e,t,i,a)=>({set _(r){qe(e,t,r)},get _(){return ke(e,t)}}),ca,es,ha,gr,ts,is,as,ma,Ci,rs;class X0{constructor(t,i,a){bt(this,ca,void 0),bt(this,es,void 0),bt(this,ha,void 0),bt(this,gr,void 0),bt(this,ts,void 0),bt(this,is,void 0),bt(this,as,void 0),bt(this,ma,void 0),bt(this,Ci,0),bt(this,rs,(r=performance.now())=>{qe(this,Ci,requestAnimationFrame(ke(this,rs))),qe(this,gr,performance.now()-ke(this,ha));const n=1e3/this.fps;if(ke(this,gr)>n){qe(this,ha,r-ke(this,gr)%n);const s=1e3/((r-ke(this,es))/++z0(this,ts)._),o=(r-ke(this,is))/1e3/this.duration;let l=ke(this,as)+o*this.playbackRate;l-ke(this,ca).valueAsNumber>0?qe(this,ma,this.playbackRate/this.duration/s):(qe(this,ma,.995*ke(this,ma)),l=ke(this,ca).valueAsNumber+ke(this,ma)),this.callback(l)}}),qe(this,ca,t),this.callback=i,this.fps=a}start(){ke(this,Ci)===0&&(qe(this,ha,performance.now()),qe(this,es,ke(this,ha)),qe(this,ts,0),ke(this,rs).call(this))}stop(){ke(this,Ci)!==0&&(cancelAnimationFrame(ke(this,Ci)),qe(this,Ci,0))}update({start:t,duration:i,playbackRate:a}){const r=t-ke(this,ca).valueAsNumber,n=Math.abs(i-this.duration);(r>0||r<-.03||n>=.5)&&this.callback(t),qe(this,as,t),qe(this,is,performance.now()),this.duration=i,this.playbackRate=a}}ca=new WeakMap;es=new WeakMap;ha=new WeakMap;gr=new WeakMap;ts=new WeakMap;is=new WeakMap;as=new WeakMap;ma=new WeakMap;Ci=new WeakMap;rs=new WeakMap;var jd=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},ge=(e,t,i)=>(jd(e,t,"read from private field"),i?i.call(e):t.get(e)),Ae=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Ct=(e,t,i,a)=>(jd(e,t,"write to private field"),t.set(e,i),i),Me=(e,t,i)=>(jd(e,t,"access private method"),i),pa,Vi,Fs,Or,Ks,ns,Qr,Zr,va,fa,yr,Qd,vp,kl,Vs,Zd,qs,zd,Ys,Xd,Sl,fp,zr,Gs,wl,Ep;const J0="video not loaded, unknown time.",e1=e=>{const t=e.range,i=Lr(+_p(e)),a=Lr(+e.mediaSeekableEnd),r=i&&a?`${i} of ${a}`:J0;t.setAttribute("aria-valuetext",r)};function t1(e){return`
    ${Ka.getTemplateHTML(e)}
    <style>
      :host {
        --media-box-border-radius: 4px;
        --media-box-padding-left: 10px;
        --media-box-padding-right: 10px;
        --media-preview-border-radius: var(--media-box-border-radius);
        --media-box-arrow-offset: var(--media-box-border-radius);
        --_control-background: var(--media-control-background, var(--media-secondary-color, rgb(20 20 30 / .7)));
        --_preview-background: var(--media-preview-background, var(--_control-background));

        
        contain: layout;
      }

      #buffered {
        background: var(--media-time-range-buffered-color, rgb(255 255 255 / .4));
        position: absolute;
        height: 100%;
        will-change: width;
      }

      #preview-rail,
      #current-rail {
        width: 100%;
        position: absolute;
        left: 0;
        bottom: 100%;
        pointer-events: none;
        will-change: transform;
      }

      [part~="box"] {
        width: min-content;
        
        position: absolute;
        bottom: 100%;
        flex-direction: column;
        align-items: center;
        transform: translateX(-50%);
      }

      [part~="current-box"] {
        display: var(--media-current-box-display, var(--media-box-display, flex));
        margin: var(--media-current-box-margin, var(--media-box-margin, 0 0 5px));
        visibility: hidden;
      }

      [part~="preview-box"] {
        display: var(--media-preview-box-display, var(--media-box-display, flex));
        margin: var(--media-preview-box-margin, var(--media-box-margin, 0 0 5px));
        transition-property: var(--media-preview-transition-property, visibility, opacity);
        transition-duration: var(--media-preview-transition-duration-out, .25s);
        transition-delay: var(--media-preview-transition-delay-out, 0s);
        visibility: hidden;
        opacity: 0;
      }

      :host(:is([${u.MEDIA_PREVIEW_IMAGE}], [${u.MEDIA_PREVIEW_TIME}])[dragging]) [part~="preview-box"] {
        transition-duration: var(--media-preview-transition-duration-in, .5s);
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        visibility: visible;
        opacity: 1;
      }

      @media (hover: hover) {
        :host(:is([${u.MEDIA_PREVIEW_IMAGE}], [${u.MEDIA_PREVIEW_TIME}]):hover) [part~="preview-box"] {
          transition-duration: var(--media-preview-transition-duration-in, .5s);
          transition-delay: var(--media-preview-transition-delay-in, .25s);
          visibility: visible;
          opacity: 1;
        }
      }

      media-preview-thumbnail,
      ::slotted(media-preview-thumbnail) {
        visibility: hidden;
        
        transition: visibility 0s .25s;
        transition-delay: calc(var(--media-preview-transition-delay-out, 0s) + var(--media-preview-transition-duration-out, .25s));
        background: var(--media-preview-thumbnail-background, var(--_preview-background));
        box-shadow: var(--media-preview-thumbnail-box-shadow, 0 0 4px rgb(0 0 0 / .2));
        max-width: var(--media-preview-thumbnail-max-width, 180px);
        max-height: var(--media-preview-thumbnail-max-height, 160px);
        min-width: var(--media-preview-thumbnail-min-width, 120px);
        min-height: var(--media-preview-thumbnail-min-height, 80px);
        border: var(--media-preview-thumbnail-border);
        border-radius: var(--media-preview-thumbnail-border-radius,
          var(--media-preview-border-radius) var(--media-preview-border-radius) 0 0);
      }

      :host([${u.MEDIA_PREVIEW_IMAGE}][dragging]) media-preview-thumbnail,
      :host([${u.MEDIA_PREVIEW_IMAGE}][dragging]) ::slotted(media-preview-thumbnail) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        visibility: visible;
      }

      @media (hover: hover) {
        :host([${u.MEDIA_PREVIEW_IMAGE}]:hover) media-preview-thumbnail,
        :host([${u.MEDIA_PREVIEW_IMAGE}]:hover) ::slotted(media-preview-thumbnail) {
          transition-delay: var(--media-preview-transition-delay-in, .25s);
          visibility: visible;
        }

        :host([${u.MEDIA_PREVIEW_TIME}]:hover) {
          --media-time-range-hover-display: block;
        }
      }

      media-preview-chapter-display,
      ::slotted(media-preview-chapter-display) {
        font-size: var(--media-font-size, 13px);
        line-height: 17px;
        min-width: 0;
        visibility: hidden;
        
        transition: min-width 0s, border-radius 0s, margin 0s, padding 0s, visibility 0s;
        transition-delay: calc(var(--media-preview-transition-delay-out, 0s) + var(--media-preview-transition-duration-out, .25s));
        background: var(--media-preview-chapter-background, var(--_preview-background));
        border-radius: var(--media-preview-chapter-border-radius,
          var(--media-preview-border-radius) var(--media-preview-border-radius)
          var(--media-preview-border-radius) var(--media-preview-border-radius));
        padding: var(--media-preview-chapter-padding, 3.5px 9px);
        margin: var(--media-preview-chapter-margin, 0 0 5px);
        text-shadow: var(--media-preview-chapter-text-shadow, 0 0 4px rgb(0 0 0 / .75));
      }

      :host([${u.MEDIA_PREVIEW_IMAGE}]) media-preview-chapter-display,
      :host([${u.MEDIA_PREVIEW_IMAGE}]) ::slotted(media-preview-chapter-display) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        border-radius: var(--media-preview-chapter-border-radius, 0);
        padding: var(--media-preview-chapter-padding, 3.5px 9px 0);
        margin: var(--media-preview-chapter-margin, 0);
        min-width: 100%;
      }

      media-preview-chapter-display[${u.MEDIA_PREVIEW_CHAPTER}],
      ::slotted(media-preview-chapter-display[${u.MEDIA_PREVIEW_CHAPTER}]) {
        visibility: visible;
      }

      media-preview-chapter-display:not([aria-valuetext]),
      ::slotted(media-preview-chapter-display:not([aria-valuetext])) {
        display: none;
      }

      media-preview-time-display,
      ::slotted(media-preview-time-display),
      media-time-display,
      ::slotted(media-time-display) {
        font-size: var(--media-font-size, 13px);
        line-height: 17px;
        min-width: 0;
        
        transition: min-width 0s, border-radius 0s;
        transition-delay: calc(var(--media-preview-transition-delay-out, 0s) + var(--media-preview-transition-duration-out, .25s));
        background: var(--media-preview-time-background, var(--_preview-background));
        border-radius: var(--media-preview-time-border-radius,
          var(--media-preview-border-radius) var(--media-preview-border-radius)
          var(--media-preview-border-radius) var(--media-preview-border-radius));
        padding: var(--media-preview-time-padding, 3.5px 9px);
        margin: var(--media-preview-time-margin, 0);
        text-shadow: var(--media-preview-time-text-shadow, 0 0 4px rgb(0 0 0 / .75));
        transform: translateX(min(
          max(calc(50% - var(--_box-width) / 2),
          calc(var(--_box-shift, 0))),
          calc(var(--_box-width) / 2 - 50%)
        ));
      }

      :host([${u.MEDIA_PREVIEW_IMAGE}]) media-preview-time-display,
      :host([${u.MEDIA_PREVIEW_IMAGE}]) ::slotted(media-preview-time-display) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        border-radius: var(--media-preview-time-border-radius,
          0 0 var(--media-preview-border-radius) var(--media-preview-border-radius));
        min-width: 100%;
      }

      :host([${u.MEDIA_PREVIEW_TIME}]:hover) {
        --media-time-range-hover-display: block;
      }

      [part~="arrow"],
      ::slotted([part~="arrow"]) {
        display: var(--media-box-arrow-display, inline-block);
        transform: translateX(min(
          max(calc(50% - var(--_box-width) / 2 + var(--media-box-arrow-offset)),
          calc(var(--_box-shift, 0))),
          calc(var(--_box-width) / 2 - 50% - var(--media-box-arrow-offset))
        ));
        
        border-color: transparent;
        border-top-color: var(--media-box-arrow-background, var(--_control-background));
        border-width: var(--media-box-arrow-border-width,
          var(--media-box-arrow-height, 5px) var(--media-box-arrow-width, 6px) 0);
        border-style: solid;
        justify-content: center;
        height: 0;
      }
    </style>
    <div id="preview-rail">
      <slot name="preview" part="box preview-box">
        <media-preview-thumbnail>
          <template shadowrootmode="${Mc.shadowRootOptions.mode}">
            ${Mc.getTemplateHTML({})}
          </template>
        </media-preview-thumbnail>
        <media-preview-chapter-display></media-preview-chapter-display>
        <media-preview-time-display></media-preview-time-display>
        <slot name="preview-arrow"><div part="arrow"></div></slot>
      </slot>
    </div>
    <div id="current-rail">
      <slot name="current" part="box current-box">
        
      </slot>
    </div>
  `}const bn=(e,t=e.mediaCurrentTime)=>{const i=Number.isFinite(e.mediaSeekableStart)?e.mediaSeekableStart:0,a=Number.isFinite(e.mediaDuration)?e.mediaDuration:e.mediaSeekableEnd;if(Number.isNaN(a))return 0;const r=(t-i)/(a-i);return Math.max(0,Math.min(r,1))},_p=(e,t=e.range.valueAsNumber)=>{const i=Number.isFinite(e.mediaSeekableStart)?e.mediaSeekableStart:0,a=Number.isFinite(e.mediaDuration)?e.mediaDuration:e.mediaSeekableEnd;return Number.isNaN(a)?0:t*(a-i)+i};class Jd extends Ka{constructor(){super(),Ae(this,fa),Ae(this,Qd),Ae(this,Vs),Ae(this,qs),Ae(this,Ys),Ae(this,Sl),Ae(this,zr),Ae(this,wl),Ae(this,pa,void 0),Ae(this,Vi,void 0),Ae(this,Fs,void 0),Ae(this,Or,void 0),Ae(this,Ks,void 0),Ae(this,ns,void 0),Ae(this,Qr,void 0),Ae(this,Zr,void 0),Ae(this,va,void 0),Ae(this,kl,a=>{this.dragging||(gd(a)&&(this.range.valueAsNumber=a),this.updateBar())}),this.shadowRoot.querySelector("#track").insertAdjacentHTML("afterbegin",'<div id="buffered" part="buffered"></div>'),Ct(this,Fs,this.shadowRoot.querySelectorAll('[part~="box"]')),Ct(this,Ks,this.shadowRoot.querySelector('[part~="preview-box"]')),Ct(this,ns,this.shadowRoot.querySelector('[part~="current-box"]'));const i=getComputedStyle(this);Ct(this,Qr,parseInt(i.getPropertyValue("--media-box-padding-left"))),Ct(this,Zr,parseInt(i.getPropertyValue("--media-box-padding-right"))),Ct(this,Vi,new X0(this.range,ge(this,kl),60))}static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_PAUSED,u.MEDIA_DURATION,u.MEDIA_SEEKABLE,u.MEDIA_CURRENT_TIME,u.MEDIA_PREVIEW_IMAGE,u.MEDIA_PREVIEW_TIME,u.MEDIA_PREVIEW_CHAPTER,u.MEDIA_BUFFERED,u.MEDIA_PLAYBACK_RATE,u.MEDIA_LOADING,u.MEDIA_ENDED]}connectedCallback(){var t;super.connectedCallback(),this.range.setAttribute("aria-label",C("seek")),Me(this,fa,yr).call(this),Ct(this,pa,this.getRootNode()),(t=ge(this,pa))==null||t.addEventListener("transitionstart",this)}disconnectedCallback(){var t;super.disconnectedCallback(),Me(this,fa,yr).call(this),(t=ge(this,pa))==null||t.removeEventListener("transitionstart",this),Ct(this,pa,null)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),i!=a&&(t===u.MEDIA_CURRENT_TIME||t===u.MEDIA_PAUSED||t===u.MEDIA_ENDED||t===u.MEDIA_LOADING||t===u.MEDIA_DURATION||t===u.MEDIA_SEEKABLE?(ge(this,Vi).update({start:bn(this),duration:this.mediaSeekableEnd-this.mediaSeekableStart,playbackRate:this.mediaPlaybackRate}),Me(this,fa,yr).call(this),e1(this)):t===u.MEDIA_BUFFERED&&this.updateBufferedBar(),(t===u.MEDIA_DURATION||t===u.MEDIA_SEEKABLE)&&(this.mediaChaptersCues=ge(this,va),this.updateBar()))}get mediaChaptersCues(){return ge(this,va)}set mediaChaptersCues(t){var i;Ct(this,va,t),this.updateSegments((i=ge(this,va))==null?void 0:i.map(a=>({start:bn(this,a.startTime),end:bn(this,a.endTime)})))}get mediaPaused(){return F(this,u.MEDIA_PAUSED)}set mediaPaused(t){K(this,u.MEDIA_PAUSED,t)}get mediaLoading(){return F(this,u.MEDIA_LOADING)}set mediaLoading(t){K(this,u.MEDIA_LOADING,t)}get mediaDuration(){return ie(this,u.MEDIA_DURATION)}set mediaDuration(t){de(this,u.MEDIA_DURATION,t)}get mediaCurrentTime(){return ie(this,u.MEDIA_CURRENT_TIME)}set mediaCurrentTime(t){de(this,u.MEDIA_CURRENT_TIME,t)}get mediaPlaybackRate(){return ie(this,u.MEDIA_PLAYBACK_RATE,1)}set mediaPlaybackRate(t){de(this,u.MEDIA_PLAYBACK_RATE,t)}get mediaBuffered(){const t=this.getAttribute(u.MEDIA_BUFFERED);return t?t.split(" ").map(i=>i.split(":").map(a=>+a)):[]}set mediaBuffered(t){if(!t){this.removeAttribute(u.MEDIA_BUFFERED);return}const i=t.map(a=>a.join(":")).join(" ");this.setAttribute(u.MEDIA_BUFFERED,i)}get mediaSeekable(){const t=this.getAttribute(u.MEDIA_SEEKABLE);if(t)return t.split(":").map(i=>+i)}set mediaSeekable(t){if(t==null){this.removeAttribute(u.MEDIA_SEEKABLE);return}this.setAttribute(u.MEDIA_SEEKABLE,t.join(":"))}get mediaSeekableEnd(){var t;const[,i=this.mediaDuration]=(t=this.mediaSeekable)!=null?t:[];return i}get mediaSeekableStart(){var t;const[i=0]=(t=this.mediaSeekable)!=null?t:[];return i}get mediaPreviewImage(){return ae(this,u.MEDIA_PREVIEW_IMAGE)}set mediaPreviewImage(t){re(this,u.MEDIA_PREVIEW_IMAGE,t)}get mediaPreviewTime(){return ie(this,u.MEDIA_PREVIEW_TIME)}set mediaPreviewTime(t){de(this,u.MEDIA_PREVIEW_TIME,t)}get mediaEnded(){return F(this,u.MEDIA_ENDED)}set mediaEnded(t){K(this,u.MEDIA_ENDED,t)}updateBar(){super.updateBar(),this.updateBufferedBar(),this.updateCurrentBox()}updateBufferedBar(){var t;const i=this.mediaBuffered;if(!i.length)return;let a;if(this.mediaEnded)a=1;else{const n=this.mediaCurrentTime,[,s=this.mediaSeekableStart]=(t=i.find(([o,l])=>o<=n&&n<=l))!=null?t:[];a=bn(this,s)}const{style:r}=fe(this.shadowRoot,"#buffered");r.setProperty("width",`${a*100}%`)}updateCurrentBox(){if(!this.shadowRoot.querySelector('slot[name="current"]').assignedElements().length)return;const i=fe(this.shadowRoot,"#current-rail"),a=fe(this.shadowRoot,'[part~="current-box"]'),r=Me(this,Vs,Zd).call(this,ge(this,ns)),n=Me(this,qs,zd).call(this,r,this.range.valueAsNumber),s=Me(this,Ys,Xd).call(this,r,this.range.valueAsNumber);i.style.transform=`translateX(${n})`,i.style.setProperty("--_range-width",`${r.range.width}`),a.style.setProperty("--_box-shift",`${s}`),a.style.setProperty("--_box-width",`${r.box.width}px`),a.style.setProperty("visibility","initial")}handleEvent(t){switch(super.handleEvent(t),t.type){case"input":Me(this,wl,Ep).call(this);break;case"pointermove":Me(this,Sl,fp).call(this,t);break;case"pointerup":case"pointerleave":Me(this,zr,Gs).call(this,null);break;case"transitionstart":ti(t.target,this)&&setTimeout(()=>Me(this,fa,yr).call(this),0);break}}}pa=new WeakMap;Vi=new WeakMap;Fs=new WeakMap;Or=new WeakMap;Ks=new WeakMap;ns=new WeakMap;Qr=new WeakMap;Zr=new WeakMap;va=new WeakMap;fa=new WeakSet;yr=function(){Me(this,Qd,vp).call(this)?ge(this,Vi).start():ge(this,Vi).stop()};Qd=new WeakSet;vp=function(){return this.isConnected&&!this.mediaPaused&&!this.mediaLoading&&!this.mediaEnded&&this.mediaSeekableEnd>0&&ym(this)};kl=new WeakMap;Vs=new WeakSet;Zd=function(e){var t;const a=((t=this.getAttribute("bounds")?Fa(this,`#${this.getAttribute("bounds")}`):this.parentElement)!=null?t:this).getBoundingClientRect(),r=this.range.getBoundingClientRect(),n=e.offsetWidth,s=-(r.left-a.left-n/2),o=a.right-r.left-n/2;return{box:{width:n,min:s,max:o},bounds:a,range:r}};qs=new WeakSet;zd=function(e,t){let i=`${t*100}%`;const{width:a,min:r,max:n}=e.box;if(!a)return i;if(Number.isNaN(r)||(i=`max(${`calc(1 / var(--_range-width) * 100 * ${r}% + var(--media-box-padding-left))`}, ${i})`),!Number.isNaN(n)){const o=`calc(1 / var(--_range-width) * 100 * ${n}% - var(--media-box-padding-right))`;i=`min(${i}, ${o})`}return i};Ys=new WeakSet;Xd=function(e,t){const{width:i,min:a,max:r}=e.box,n=t*e.range.width;if(n<a+ge(this,Qr)){const s=e.range.left-e.bounds.left-ge(this,Qr);return`${n-i/2+s}px`}if(n>r-ge(this,Zr)){const s=e.bounds.right-e.range.right-ge(this,Zr);return`${n+i/2-s-e.range.width}px`}return 0};Sl=new WeakSet;fp=function(e){const t=[...ge(this,Fs)].some(m=>e.composedPath().includes(m));if(!this.dragging&&(t||!e.composedPath().includes(this))){Me(this,zr,Gs).call(this,null);return}const i=this.mediaSeekableEnd;if(!i)return;const a=fe(this.shadowRoot,"#preview-rail"),r=fe(this.shadowRoot,'[part~="preview-box"]'),n=Me(this,Vs,Zd).call(this,ge(this,Ks));let s=(e.clientX-n.range.left)/n.range.width;s=Math.max(0,Math.min(1,s));const o=Me(this,qs,zd).call(this,n,s),l=Me(this,Ys,Xd).call(this,n,s);a.style.transform=`translateX(${o})`,a.style.setProperty("--_range-width",`${n.range.width}`),r.style.setProperty("--_box-shift",`${l}`),r.style.setProperty("--_box-width",`${n.box.width}px`);const d=Math.round(ge(this,Or))-Math.round(s*i);Math.abs(d)<1&&s>.01&&s<.99||(Ct(this,Or,s*i),Me(this,zr,Gs).call(this,ge(this,Or)))};zr=new WeakSet;Gs=function(e){this.dispatchEvent(new E.CustomEvent(R.MEDIA_PREVIEW_REQUEST,{composed:!0,bubbles:!0,detail:e}))};wl=new WeakSet;Ep=function(){ge(this,Vi).stop();const e=_p(this);this.dispatchEvent(new E.CustomEvent(R.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:e}))};Jd.shadowRootOptions={mode:"open"};Jd.getTemplateHTML=t1;E.customElements.get("media-time-range")||E.customElements.define("media-time-range",Jd);const i1=1,a1=e=>e.mediaMuted?0:e.mediaVolume,r1=e=>`${Math.round(e*100)}%`;class n1 extends Ka{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_VOLUME,u.MEDIA_MUTED,u.MEDIA_VOLUME_UNAVAILABLE]}constructor(){super(),this.range.addEventListener("input",()=>{const t=this.range.value,i=new E.CustomEvent(R.MEDIA_VOLUME_REQUEST,{composed:!0,bubbles:!0,detail:t});this.dispatchEvent(i)})}connectedCallback(){super.connectedCallback(),this.range.setAttribute("aria-label",C("volume"))}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),(t===u.MEDIA_VOLUME||t===u.MEDIA_MUTED)&&(this.range.valueAsNumber=a1(this),this.range.setAttribute("aria-valuetext",r1(this.range.valueAsNumber)),this.updateBar())}get mediaVolume(){return ie(this,u.MEDIA_VOLUME,i1)}set mediaVolume(t){de(this,u.MEDIA_VOLUME,t)}get mediaMuted(){return F(this,u.MEDIA_MUTED)}set mediaMuted(t){K(this,u.MEDIA_MUTED,t)}get mediaVolumeUnavailable(){return ae(this,u.MEDIA_VOLUME_UNAVAILABLE)}set mediaVolumeUnavailable(t){re(this,u.MEDIA_VOLUME_UNAVAILABLE,t)}}E.customElements.get("media-volume-range")||E.customElements.define("media-volume-range",n1);var bp=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},$=(e,t,i)=>(bp(e,t,"read from private field"),i?i.call(e):t.get(e)),Mt=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Qt=(e,t,i,a)=>(bp(e,t,"write to private field"),t.set(e,i),i),Ea,ss,Di,Tr,ui,ci,hi,Li,_a,os,dt;const Pc=1,$c=0,s1=1,o1={processCallback(e,t,i){if(i){for(const[a,r]of t)if(a in i){const n=i[a];typeof n=="boolean"&&r instanceof pt&&typeof r.element[r.attributeName]=="boolean"?r.booleanValue=n:typeof n=="function"&&r instanceof pt?r.element[r.attributeName]=n:r.value=n}}}};class fo extends E.DocumentFragment{constructor(t,i,a=o1){var r;super(),Mt(this,Ea,void 0),Mt(this,ss,void 0),this.append(t.content.cloneNode(!0)),Qt(this,Ea,gp(this)),Qt(this,ss,a),(r=a.createCallback)==null||r.call(a,this,$(this,Ea),i),a.processCallback(this,$(this,Ea),i)}update(t){$(this,ss).processCallback(this,$(this,Ea),t)}}Ea=new WeakMap;ss=new WeakMap;const gp=(e,t=[])=>{let i,a;for(const r of e.attributes||[])if(r.value.includes("{{")){const n=new d1;for([i,a]of Hc(r.value))if(!i)n.append(a);else{const s=new pt(e,r.name,r.namespaceURI);n.append(s),t.push([a,s])}r.value=n.toString()}for(const r of e.childNodes)if(r.nodeType===Pc&&!(r instanceof HTMLTemplateElement))gp(r,t);else{const n=r.data;if(r.nodeType===Pc||n.includes("{{")){const s=[];if(n)for([i,a]of Hc(n))if(!i)s.push(new Text(a));else{const o=new Va(e);s.push(o),t.push([a,o])}else if(r instanceof HTMLTemplateElement){const o=new Ap(e,r);s.push(o),t.push([o.expression,o])}r.replaceWith(...s.flatMap(o=>o.replacementNodes||[o]))}}return t},Uc={},Hc=e=>{let t="",i=0,a=Uc[e],r=0,n;if(a)return a;for(a=[];n=e[r];r++)n==="{"&&e[r+1]==="{"&&e[r-1]!=="\\"&&e[r+2]&&++i==1?(t&&a.push([$c,t]),t="",r++):n==="}"&&e[r+1]==="}"&&e[r-1]!=="\\"&&!--i?(a.push([s1,t.trim()]),t="",r++):t+=n||"";return t&&a.push([$c,(i>0?"{{":"")+t]),Uc[e]=a},l1=11;class yp{get value(){return""}set value(t){}toString(){return this.value}}const Tp=new WeakMap;class d1{constructor(){Mt(this,Di,[])}[Symbol.iterator](){return $(this,Di).values()}get length(){return $(this,Di).length}item(t){return $(this,Di)[t]}append(...t){for(const i of t)i instanceof pt&&Tp.set(i,this),$(this,Di).push(i)}toString(){return $(this,Di).join("")}}Di=new WeakMap;class pt extends yp{constructor(t,i,a){super(),Mt(this,Li),Mt(this,Tr,""),Mt(this,ui,void 0),Mt(this,ci,void 0),Mt(this,hi,void 0),Qt(this,ui,t),Qt(this,ci,i),Qt(this,hi,a)}get attributeName(){return $(this,ci)}get attributeNamespace(){return $(this,hi)}get element(){return $(this,ui)}get value(){return $(this,Tr)}set value(t){$(this,Tr)!==t&&(Qt(this,Tr,t),!$(this,Li,_a)||$(this,Li,_a).length===1?t==null?$(this,ui).removeAttributeNS($(this,hi),$(this,ci)):$(this,ui).setAttributeNS($(this,hi),$(this,ci),t):$(this,ui).setAttributeNS($(this,hi),$(this,ci),$(this,Li,_a).toString()))}get booleanValue(){return $(this,ui).hasAttributeNS($(this,hi),$(this,ci))}set booleanValue(t){if(!$(this,Li,_a)||$(this,Li,_a).length===1)this.value=t?"":null;else throw new DOMException("Value is not fully templatized")}}Tr=new WeakMap;ui=new WeakMap;ci=new WeakMap;hi=new WeakMap;Li=new WeakSet;_a=function(){return Tp.get(this)};class Va extends yp{constructor(t,i){super(),Mt(this,os,void 0),Mt(this,dt,void 0),Qt(this,os,t),Qt(this,dt,i?[...i]:[new Text])}get replacementNodes(){return $(this,dt)}get parentNode(){return $(this,os)}get nextSibling(){return $(this,dt)[$(this,dt).length-1].nextSibling}get previousSibling(){return $(this,dt)[0].previousSibling}get value(){return $(this,dt).map(t=>t.textContent).join("")}set value(t){this.replace(t)}replace(...t){const i=t.flat().flatMap(a=>a==null?[new Text]:a.forEach?[...a]:a.nodeType===l1?[...a.childNodes]:a.nodeType?[a]:[new Text(a)]);i.length||i.push(new Text),Qt(this,dt,u1($(this,dt)[0].parentNode,$(this,dt),i,this.nextSibling))}}os=new WeakMap;dt=new WeakMap;class Ap extends Va{constructor(t,i){const a=i.getAttribute("directive")||i.getAttribute("type");let r=i.getAttribute("expression")||i.getAttribute(a)||"";r.startsWith("{{")&&(r=r.trim().slice(2,-2).trim()),super(t),this.expression=r,this.template=i,this.directive=a}}function u1(e,t,i,a=null){let r=0,n,s,o,l=i.length,d=t.length;for(;r<l&&r<d&&t[r]==i[r];)r++;for(;r<l&&r<d&&i[l-1]==t[d-1];)a=i[--d,--l];if(r==d)for(;r<l;)e.insertBefore(i[r++],a);if(r==l)for(;r<d;)e.removeChild(t[r++]);else{for(n=t[r];r<l;)o=i[r++],s=n?n.nextSibling:a,n==o?n=s:r<l&&i[r]==s?(e.replaceChild(o,n),n=s):e.insertBefore(o,n);for(;n!=a;)s=n.nextSibling,e.removeChild(n),n=s}return i}const Bc={string:e=>String(e)};class kp{constructor(t){this.template=t,this.state=void 0}}const Hi=new WeakMap,Bi=new WeakMap,Il={partial:(e,t)=>{t[e.expression]=new kp(e.template)},if:(e,t)=>{var i;if(Sp(e.expression,t))if(Hi.get(e)!==e.template){Hi.set(e,e.template);const a=new fo(e.template,t,eu);e.replace(a),Bi.set(e,a)}else(i=Bi.get(e))==null||i.update(t);else e.replace(""),Hi.delete(e),Bi.delete(e)}},c1=Object.keys(Il),eu={processCallback(e,t,i){var a,r;if(i)for(const[n,s]of t){if(s instanceof Ap){if(!s.directive){const l=c1.find(d=>s.template.hasAttribute(d));l&&(s.directive=l,s.expression=s.template.getAttribute(l))}(a=Il[s.directive])==null||a.call(Il,s,i);continue}let o=Sp(n,i);if(o instanceof kp){Hi.get(s)!==o.template?(Hi.set(s,o.template),o=new fo(o.template,o.state,eu),s.value=o,Bi.set(s,o)):(r=Bi.get(s))==null||r.update(o.state);continue}o?(s instanceof pt&&s.attributeName.startsWith("aria-")&&(o=String(o)),s instanceof pt?typeof o=="boolean"?s.booleanValue=o:typeof o=="function"?s.element[s.attributeName]=o:s.value=o:(s.value=o,Hi.delete(s),Bi.delete(s))):s instanceof pt?s.value=void 0:(s.value=void 0,Hi.delete(s),Bi.delete(s))}}},Wc={"!":e=>!e,"!!":e=>!!e,"==":(e,t)=>e==t,"!=":(e,t)=>e!=t,">":(e,t)=>e>t,">=":(e,t)=>e>=t,"<":(e,t)=>e<t,"<=":(e,t)=>e<=t,"??":(e,t)=>e??t,"|":(e,t)=>{var i;return(i=Bc[t])==null?void 0:i.call(Bc,e)}};function h1(e){return m1(e,{boolean:/true|false/,number:/-?\d+\.?\d*/,string:/(["'])((?:\\.|[^\\])*?)\1/,operator:/[!=><][=!]?|\?\?|\|/,ws:/\s+/,param:/[$a-z_][$\w]*/i}).filter(({type:t})=>t!=="ws")}function Sp(e,t={}){var i,a,r,n,s,o,l;const d=h1(e);if(d.length===0||d.some(({type:m})=>!m))return er(e);if(((i=d[0])==null?void 0:i.token)===">"){const m=t[(a=d[1])==null?void 0:a.token];if(!m)return er(e);const p={...t};m.state=p;const h=d.slice(2);for(let c=0;c<h.length;c+=3){const v=(r=h[c])==null?void 0:r.token,g=(n=h[c+1])==null?void 0:n.token,_=(s=h[c+2])==null?void 0:s.token;v&&g==="="&&(p[v]=tr(_,t))}return m}if(d.length===1)return gn(d[0])?tr(d[0].token,t):er(e);if(d.length===2){const m=(o=d[0])==null?void 0:o.token,p=Wc[m];if(!p||!gn(d[1]))return er(e);const h=tr(d[1].token,t);return p(h)}if(d.length===3){const m=(l=d[1])==null?void 0:l.token,p=Wc[m];if(!p||!gn(d[0])||!gn(d[2]))return er(e);const h=tr(d[0].token,t);if(m==="|")return p(h,d[2].token);const c=tr(d[2].token,t);return p(h,c)}}function er(e){return console.warn(`Warning: invalid expression \`${e}\``),!1}function gn({type:e}){return["number","boolean","string","param"].includes(e)}function tr(e,t){const i=e[0],a=e.slice(-1);return e==="true"||e==="false"?e==="true":i===a&&["'",'"'].includes(i)?e.slice(1,-1):cm(e)?parseFloat(e):t[e]}function m1(e,t){let i,a,r;const n=[];for(;e;){r=null,i=e.length;for(const s in t)a=t[s].exec(e),a&&a.index<i&&(r={token:a[0],type:s,matches:a.slice(1)},i=a.index);i&&n.push({token:e.substr(0,i),type:void 0}),r&&n.push(r),e=e.substr(i+(r?r.token.length:0))}return n}var tu=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Rl=(e,t,i)=>(tu(e,t,"read from private field"),i?i.call(e):t.get(e)),ir=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Wi=(e,t,i,a)=>(tu(e,t,"write to private field"),t.set(e,i),i),No=(e,t,i)=>(tu(e,t,"access private method"),i),Ca,ls,Da,Cl,wp,ds,Dl;const Po={mediatargetlivewindow:"targetlivewindow",mediastreamtype:"streamtype"},Ip=ye.createElement("template");Ip.innerHTML=`
  <style>
    :host {
      display: inline-block;
      line-height: 0;
    }

    media-controller {
      width: 100%;
      height: 100%;
    }

    media-captions-button:not([mediasubtitleslist]),
    media-captions-menu:not([mediasubtitleslist]),
    media-captions-menu-button:not([mediasubtitleslist]),
    media-audio-track-menu[mediaaudiotrackunavailable],
    media-audio-track-menu-button[mediaaudiotrackunavailable],
    media-rendition-menu[mediarenditionunavailable],
    media-rendition-menu-button[mediarenditionunavailable],
    media-volume-range[mediavolumeunavailable],
    media-airplay-button[mediaairplayunavailable],
    media-fullscreen-button[mediafullscreenunavailable],
    media-cast-button[mediacastunavailable],
    media-pip-button[mediapipunavailable] {
      display: none;
    }
  </style>
`;class Eo extends E.HTMLElement{constructor(){super(),ir(this,Cl),ir(this,ds),ir(this,Ca,void 0),ir(this,ls,void 0),ir(this,Da,void 0),this.shadowRoot?this.renderRoot=this.shadowRoot:(this.renderRoot=this.attachShadow({mode:"open"}),this.createRenderer());const t=new MutationObserver(i=>{var a;this.mediaController&&!((a=this.mediaController)!=null&&a.breakpointsComputed)||i.some(r=>{const n=r.target;return n===this?!0:n.localName!=="media-controller"?!1:!!(Po[r.attributeName]||r.attributeName.startsWith("breakpoint"))})&&this.render()});t.observe(this,{attributes:!0}),t.observe(this.renderRoot,{attributes:!0,subtree:!0}),this.addEventListener(Jt.BREAKPOINTS_COMPUTED,this.render),No(this,Cl,wp).call(this,"template")}get mediaController(){return this.renderRoot.querySelector("media-controller")}get template(){var t;return(t=Rl(this,Ca))!=null?t:this.constructor.template}set template(t){Wi(this,Da,null),Wi(this,Ca,t),this.createRenderer()}get props(){var t,i,a;const r=[...Array.from((i=(t=this.mediaController)==null?void 0:t.attributes)!=null?i:[]).filter(({name:s})=>Po[s]||s.startsWith("breakpoint")),...Array.from(this.attributes)],n={};for(const s of r){const o=(a=Po[s.name])!=null?a:Ib(s.name);let{value:l}=s;l!=null?(cm(l)&&(l=parseFloat(l)),n[o]=l===""?!0:l):n[o]=!1}return n}attributeChangedCallback(t,i,a){t==="template"&&i!=a&&No(this,ds,Dl).call(this)}connectedCallback(){No(this,ds,Dl).call(this)}createRenderer(){this.template&&this.template!==Rl(this,ls)&&(Wi(this,ls,this.template),this.renderer=new fo(this.template,this.props,this.constructor.processor),this.renderRoot.textContent="",this.renderRoot.append(Ip.content.cloneNode(!0),this.renderer))}render(){var t;(t=this.renderer)==null||t.update(this.props)}}Ca=new WeakMap;ls=new WeakMap;Da=new WeakMap;Cl=new WeakSet;wp=function(e){if(Object.prototype.hasOwnProperty.call(this,e)){const t=this[e];delete this[e],this[e]=t}};ds=new WeakSet;Dl=function(){var e;const t=this.getAttribute("template");if(!t||t===Rl(this,Da))return;const i=this.getRootNode(),a=(e=i?.getElementById)==null?void 0:e.call(i,t);if(a){Wi(this,Da,t),Wi(this,Ca,a),this.createRenderer();return}p1(t)&&(Wi(this,Da,t),v1(t).then(r=>{const n=ye.createElement("template");n.innerHTML=r,Wi(this,Ca,n),this.createRenderer()}).catch(console.error))};Eo.observedAttributes=["template"];Eo.processor=eu;function p1(e){if(!/^(\/|\.\/|https?:\/\/)/.test(e))return!1;const t=/^https?:\/\//.test(e)?void 0:location.origin;try{new URL(e,t)}catch{return!1}return!0}async function v1(e){const t=await fetch(e);if(t.status!==200)throw new Error(`Failed to load resource: the server responded with a status of ${t.status}`);return t.text()}E.customElements.get("media-theme")||E.customElements.define("media-theme",Eo);function f1({anchor:e,floating:t,placement:i}){const a=E1({anchor:e,floating:t}),{x:r,y:n}=b1(a,i);return{x:r,y:n}}function E1({anchor:e,floating:t}){return{anchor:_1(e,t.offsetParent),floating:{x:0,y:0,width:t.offsetWidth,height:t.offsetHeight}}}function _1(e,t){var i;const a=e.getBoundingClientRect(),r=(i=t?.getBoundingClientRect())!=null?i:{x:0,y:0};return{x:a.x-r.x,y:a.y-r.y,width:a.width,height:a.height}}function b1({anchor:e,floating:t},i){const a=g1(i)==="x"?"y":"x",r=a==="y"?"height":"width",n=Rp(i),s=e.x+e.width/2-t.width/2,o=e.y+e.height/2-t.height/2,l=e[r]/2-t[r]/2;let d;switch(n){case"top":d={x:s,y:e.y-t.height};break;case"bottom":d={x:s,y:e.y+e.height};break;case"right":d={x:e.x+e.width,y:o};break;case"left":d={x:e.x-t.width,y:o};break;default:d={x:e.x,y:e.y}}switch(i.split("-")[1]){case"start":d[a]-=l;break;case"end":d[a]+=l;break}return d}function Rp(e){return e.split("-")[0]}function g1(e){return["top","bottom"].includes(Rp(e))?"y":"x"}class iu extends Event{constructor({action:t="auto",relatedTarget:i,...a}){super("invoke",a),this.action=t,this.relatedTarget=i}}class y1 extends Event{constructor({newState:t,oldState:i,...a}){super("toggle",a),this.newState=t,this.oldState=i}}var au=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},V=(e,t,i)=>(au(e,t,"read from private field"),i?i.call(e):t.get(e)),z=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Dt=(e,t,i,a)=>(au(e,t,"write to private field"),t.set(e,i),i),ee=(e,t,i)=>(au(e,t,"access private method"),i),Lt,qi,gi,us,cs,Yi,Xr,Ll,Cp,js,hs,Ml,xl,Dp,Ol,Lp,Nl,Mp,La,Ma,xa,Jr,Qs,ru,Pl,xp,nu,Op,$l,Np,su,Pp,Ul,$p,Hl,Up,Nr,Zs,Bl,Hp,Pr,zs,ms,Wl;function Ba({type:e,text:t,value:i,checked:a}){const r=ye.createElement("media-chrome-menu-item");r.type=e,r.part.add("menu-item"),r.part.add(e),r.value=i,r.checked=a;const n=ye.createElement("span");return n.textContent=t,r.append(n),r}function Gi(e,t){let i=e.querySelector(`:scope > [slot="${t}"]`);if(i?.nodeName=="SLOT"&&(i=i.assignedElements({flatten:!0})[0]),i)return i=i.cloneNode(!0),i;const a=e.shadowRoot.querySelector(`[name="${t}"] > svg`);return a?a.cloneNode(!0):""}function T1(e){return`
    <style>
      :host {
        font: var(--media-font,
          var(--media-font-weight, normal)
          var(--media-font-size, 14px) /
          var(--media-text-content-height, var(--media-control-height, 24px))
          var(--media-font-family, helvetica neue, segoe ui, roboto, arial, sans-serif));
        color: var(--media-text-color, var(--media-primary-color, rgb(238 238 238)));
        --_menu-bg: rgb(20 20 30 / .8);
        background: var(--media-menu-background, var(--media-control-background, var(--media-secondary-color, var(--_menu-bg))));
        border-radius: var(--media-menu-border-radius);
        border: var(--media-menu-border, none);
        display: var(--media-menu-display, inline-flex);
        transition: var(--media-menu-transition-in,
          visibility 0s,
          opacity .2s ease-out,
          transform .15s ease-out,
          left .2s ease-in-out,
          min-width .2s ease-in-out,
          min-height .2s ease-in-out
        ) !important;
        
        visibility: var(--media-menu-visibility, visible);
        opacity: var(--media-menu-opacity, 1);
        max-height: var(--media-menu-max-height, var(--_menu-max-height, 300px));
        transform: var(--media-menu-transform-in, translateY(0) scale(1));
        flex-direction: column;
        
        min-height: 0;
        position: relative;
        bottom: var(--_menu-bottom);
        box-sizing: border-box;
      } 

      @-moz-document url-prefix() {
        :host{
          --_menu-bg: rgb(20 20 30);
        }
      }

      :host([hidden]) {
        transition: var(--media-menu-transition-out,
          visibility .15s ease-in,
          opacity .15s ease-in,
          transform .15s ease-in
        ) !important;
        visibility: var(--media-menu-hidden-visibility, hidden);
        opacity: var(--media-menu-hidden-opacity, 0);
        max-height: var(--media-menu-hidden-max-height,
          var(--media-menu-max-height, var(--_menu-max-height, 300px)));
        transform: var(--media-menu-transform-out, translateY(2px) scale(.99));
        pointer-events: none;
      }

      :host([slot="submenu"]) {
        background: none;
        width: 100%;
        min-height: 100%;
        position: absolute;
        bottom: 0;
        right: -100%;
      }

      #container {
        display: flex;
        flex-direction: column;
        min-height: 0;
        transition: transform .2s ease-out;
        transform: translate(0, 0);
      }

      #container.has-expanded {
        transition: transform .2s ease-in;
        transform: translate(-100%, 0);
      }

      button {
        background: none;
        color: inherit;
        border: none;
        padding: 0;
        font: inherit;
        outline: inherit;
        display: inline-flex;
        align-items: center;
      }

      slot[name="header"][hidden] {
        display: none;
      }

      slot[name="header"] > *,
      slot[name="header"]::slotted(*) {
        padding: .4em .7em;
        border-bottom: 1px solid rgb(255 255 255 / .25);
        cursor: var(--media-cursor, default);
      }

      slot[name="header"] > button[part~="back"],
      slot[name="header"]::slotted(button[part~="back"]) {
        cursor: var(--media-cursor, pointer);
      }

      svg[part~="back"] {
        height: var(--media-menu-icon-height, var(--media-control-height, 24px));
        fill: var(--media-icon-color, var(--media-primary-color, rgb(238 238 238)));
        display: block;
        margin-right: .5ch;
      }

      slot:not([name]) {
        gap: var(--media-menu-gap);
        flex-direction: var(--media-menu-flex-direction, column);
        overflow: var(--media-menu-overflow, hidden auto);
        display: flex;
        min-height: 0;
      }

      :host([role="menu"]) slot:not([name]) {
        padding-block: .4em;
      }

      slot:not([name])::slotted([role="menu"]) {
        background: none;
      }

      media-chrome-menu-item > span {
        margin-right: .5ch;
        max-width: var(--media-menu-item-max-width);
        text-overflow: ellipsis;
        overflow: hidden;
      }
    </style>
    <style id="layout-row" media="width:0">

      slot[name="header"] > *,
      slot[name="header"]::slotted(*) {
        padding: .4em .5em;
      }

      slot:not([name]) {
        gap: var(--media-menu-gap, .25em);
        flex-direction: var(--media-menu-flex-direction, row);
        padding-inline: .5em;
      }

      media-chrome-menu-item {
        padding: .3em .5em;
      }

      media-chrome-menu-item[aria-checked="true"] {
        background: var(--media-menu-item-checked-background, rgb(255 255 255 / .2));
      }

      
      media-chrome-menu-item::part(checked-indicator) {
        display: var(--media-menu-item-checked-indicator-display, none);
      }
    </style>
    <div id="container">
      <slot name="header" hidden>
        <button part="back button" aria-label="Back to previous menu">
          <slot name="back-icon">
            <svg aria-hidden="true" viewBox="0 0 20 24" part="back indicator">
              <path d="m11.88 17.585.742-.669-4.2-4.665 4.2-4.666-.743-.669-4.803 5.335 4.803 5.334Z"/>
            </svg>
          </slot>
          <slot name="title"></slot>
        </button>
      </slot>
      <slot></slot>
    </div>
    <slot name="checked-indicator" hidden></slot>
  `}const ki={STYLE:"style",HIDDEN:"hidden",DISABLED:"disabled",ANCHOR:"anchor"};class Wt extends E.HTMLElement{constructor(){if(super(),z(this,Ll),z(this,hs),z(this,xl),z(this,Ol),z(this,Nl),z(this,xa),z(this,Qs),z(this,Pl),z(this,nu),z(this,$l),z(this,su),z(this,Ul),z(this,Hl),z(this,Nr),z(this,Bl),z(this,Pr),z(this,ms),z(this,Lt,null),z(this,qi,null),z(this,gi,null),z(this,us,new Set),z(this,cs,void 0),z(this,Yi,!1),z(this,Xr,null),z(this,js,()=>{const t=V(this,us),i=new Set(this.items);for(const a of t)i.has(a)||this.dispatchEvent(new CustomEvent("removemenuitem",{detail:a}));for(const a of i)t.has(a)||this.dispatchEvent(new CustomEvent("addmenuitem",{detail:a}));Dt(this,us,i)}),z(this,La,()=>{ee(this,xa,Jr).call(this),ee(this,Qs,ru).call(this,!1)}),z(this,Ma,()=>{ee(this,xa,Jr).call(this)}),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}this.container=this.shadowRoot.querySelector("#container"),this.defaultSlot=this.shadowRoot.querySelector("slot:not([name])"),this.shadowRoot.addEventListener("slotchange",this),Dt(this,cs,new MutationObserver(V(this,js))),V(this,cs).observe(this.defaultSlot,{childList:!0})}static get observedAttributes(){return[ki.DISABLED,ki.HIDDEN,ki.STYLE,ki.ANCHOR,q.MEDIA_CONTROLLER]}static formatMenuItemText(t,i){return t}enable(){this.addEventListener("click",this),this.addEventListener("focusout",this),this.addEventListener("keydown",this),this.addEventListener("invoke",this),this.addEventListener("toggle",this)}disable(){this.removeEventListener("click",this),this.removeEventListener("focusout",this),this.removeEventListener("keyup",this),this.removeEventListener("invoke",this),this.removeEventListener("toggle",this)}handleEvent(t){switch(t.type){case"slotchange":ee(this,Ll,Cp).call(this,t);break;case"invoke":ee(this,xl,Dp).call(this,t);break;case"click":ee(this,Pl,xp).call(this,t);break;case"toggle":ee(this,$l,Np).call(this,t);break;case"focusout":ee(this,Ul,$p).call(this,t);break;case"keydown":ee(this,Hl,Up).call(this,t);break}}connectedCallback(){var t,i;Dt(this,Xr,Tm(this.shadowRoot,":host")),ee(this,hs,Ml).call(this),this.hasAttribute("disabled")||this.enable(),this.role||(this.role="menu"),Dt(this,Lt,il(this)),(i=(t=V(this,Lt))==null?void 0:t.associateElement)==null||i.call(t,this),this.hidden||(Pa(en(this),V(this,La)),Pa(this,V(this,Ma)))}disconnectedCallback(){var t,i;$a(en(this),V(this,La)),$a(this,V(this,Ma)),this.disable(),(i=(t=V(this,Lt))==null?void 0:t.unassociateElement)==null||i.call(t,this),Dt(this,Lt,null)}attributeChangedCallback(t,i,a){var r,n,s,o;t===ki.HIDDEN&&a!==i?(V(this,Yi)||Dt(this,Yi,!0),this.hidden?ee(this,Nl,Mp).call(this):ee(this,Ol,Lp).call(this),this.dispatchEvent(new y1({oldState:this.hidden?"open":"closed",newState:this.hidden?"closed":"open",bubbles:!0}))):t===q.MEDIA_CONTROLLER?(i&&((n=(r=V(this,Lt))==null?void 0:r.unassociateElement)==null||n.call(r,this),Dt(this,Lt,null)),a&&this.isConnected&&(Dt(this,Lt,il(this)),(o=(s=V(this,Lt))==null?void 0:s.associateElement)==null||o.call(s,this))):t===ki.DISABLED&&a!==i?a==null?this.enable():this.disable():t===ki.STYLE&&a!==i&&ee(this,hs,Ml).call(this)}formatMenuItemText(t,i){return this.constructor.formatMenuItemText(t,i)}get anchor(){return this.getAttribute("anchor")}set anchor(t){this.setAttribute("anchor",`${t}`)}get anchorElement(){var t;return this.anchor?(t=ro(this))==null?void 0:t.querySelector(`#${this.anchor}`):null}get items(){return this.defaultSlot.assignedElements({flatten:!0}).filter(A1)}get radioGroupItems(){return this.items.filter(t=>t.role==="menuitemradio")}get checkedItems(){return this.items.filter(t=>t.checked)}get value(){var t,i;return(i=(t=this.checkedItems[0])==null?void 0:t.value)!=null?i:""}set value(t){const i=this.items.find(a=>a.value===t);i&&ee(this,ms,Wl).call(this,i)}focus(){if(Dt(this,qi,Td()),this.items.length){ee(this,Pr,zs).call(this,this.items[0]),this.items[0].focus();return}const t=this.querySelector('[autofocus], [tabindex]:not([tabindex="-1"]), [role="menu"]');t?.focus()}handleSelect(t){var i;const a=ee(this,Nr,Zs).call(this,t);a&&(ee(this,ms,Wl).call(this,a,a.type==="checkbox"),V(this,gi)&&!this.hidden&&((i=V(this,qi))==null||i.focus(),this.hidden=!0))}get keysUsed(){return["Enter","Escape","Tab"," ","ArrowDown","ArrowUp","Home","End"]}handleMove(t){var i,a;const{key:r}=t,n=this.items,s=(a=(i=ee(this,Nr,Zs).call(this,t))!=null?i:ee(this,Bl,Hp).call(this))!=null?a:n[0],o=n.indexOf(s);let l=Math.max(0,o);r==="ArrowDown"?l++:r==="ArrowUp"?l--:t.key==="Home"?l=0:t.key==="End"&&(l=n.length-1),l<0&&(l=n.length-1),l>n.length-1&&(l=0),ee(this,Pr,zs).call(this,n[l]),n[l].focus()}}Lt=new WeakMap;qi=new WeakMap;gi=new WeakMap;us=new WeakMap;cs=new WeakMap;Yi=new WeakMap;Xr=new WeakMap;Ll=new WeakSet;Cp=function(e){const t=e.target;for(const i of t.assignedNodes({flatten:!0}))i.nodeType===3&&i.textContent.trim()===""&&i.remove();if(["header","title"].includes(t.name)){const i=this.shadowRoot.querySelector('slot[name="header"]');i.hidden=t.assignedNodes().length===0}t.name||V(this,js).call(this)};js=new WeakMap;hs=new WeakSet;Ml=function(){var e;const t=this.shadowRoot.querySelector("#layout-row"),i=(e=getComputedStyle(this).getPropertyValue("--media-menu-layout"))==null?void 0:e.trim();t.setAttribute("media",i==="row"?"":"width:0")};xl=new WeakSet;Dp=function(e){Dt(this,gi,e.relatedTarget),ti(this,e.relatedTarget)||(this.hidden=!this.hidden)};Ol=new WeakSet;Lp=function(){var e;(e=V(this,gi))==null||e.setAttribute("aria-expanded","true"),this.addEventListener("transitionend",()=>this.focus(),{once:!0}),Pa(en(this),V(this,La)),Pa(this,V(this,Ma))};Nl=new WeakSet;Mp=function(){var e;(e=V(this,gi))==null||e.setAttribute("aria-expanded","false"),$a(en(this),V(this,La)),$a(this,V(this,Ma))};La=new WeakMap;Ma=new WeakMap;xa=new WeakSet;Jr=function(e){if(this.hasAttribute("mediacontroller")&&!this.anchor||this.hidden||!this.anchorElement)return;const{x:t,y:i}=f1({anchor:this.anchorElement,floating:this,placement:"top-start"});e??(e=this.offsetWidth);const r=en(this).getBoundingClientRect(),n=r.width-t-e,s=r.height-i-this.offsetHeight,{style:o}=V(this,Xr);o.setProperty("position","absolute"),o.setProperty("right",`${Math.max(0,n)}px`),o.setProperty("--_menu-bottom",`${s}px`);const l=getComputedStyle(this),m=o.getPropertyValue("--_menu-bottom")===l.bottom?s:parseFloat(l.bottom),p=r.height-m-parseFloat(l.marginBottom);this.style.setProperty("--_menu-max-height",`${p}px`)};Qs=new WeakSet;ru=function(e){const t=this.querySelector('[role="menuitem"][aria-haspopup][aria-expanded="true"]'),i=t?.querySelector('[role="menu"]'),{style:a}=V(this,Xr);if(e||a.setProperty("--media-menu-transition-in","none"),i){const r=i.offsetHeight,n=Math.max(i.offsetWidth,t.offsetWidth);this.style.setProperty("min-width",`${n}px`),this.style.setProperty("min-height",`${r}px`),ee(this,xa,Jr).call(this,n)}else this.style.removeProperty("min-width"),this.style.removeProperty("min-height"),ee(this,xa,Jr).call(this);a.removeProperty("--media-menu-transition-in")};Pl=new WeakSet;xp=function(e){var t;if(e.stopPropagation(),e.composedPath().includes(V(this,nu,Op))){(t=V(this,qi))==null||t.focus(),this.hidden=!0;return}const i=ee(this,Nr,Zs).call(this,e);!i||i.hasAttribute("disabled")||(ee(this,Pr,zs).call(this,i),this.handleSelect(e))};nu=new WeakSet;Op=function(){var e;return(e=this.shadowRoot.querySelector('slot[name="header"]').assignedElements({flatten:!0}))==null?void 0:e.find(i=>i.matches('button[part~="back"]'))};$l=new WeakSet;Np=function(e){if(e.target===this)return;ee(this,su,Pp).call(this);const t=Array.from(this.querySelectorAll('[role="menuitem"][aria-haspopup]'));for(const i of t)i.invokeTargetElement!=e.target&&e.newState=="open"&&i.getAttribute("aria-expanded")=="true"&&!i.invokeTargetElement.hidden&&i.invokeTargetElement.dispatchEvent(new iu({relatedTarget:i}));for(const i of t)i.setAttribute("aria-expanded",`${!i.submenuElement.hidden}`);ee(this,Qs,ru).call(this,!0)};su=new WeakSet;Pp=function(){const t=this.querySelector('[role="menuitem"] > [role="menu"]:not([hidden])');this.container.classList.toggle("has-expanded",!!t)};Ul=new WeakSet;$p=function(e){var t;ti(this,e.relatedTarget)||(V(this,Yi)&&((t=V(this,qi))==null||t.focus()),V(this,gi)&&V(this,gi)!==e.relatedTarget&&!this.hidden&&(this.hidden=!0))};Hl=new WeakSet;Up=function(e){var t,i,a,r,n;const{key:s,ctrlKey:o,altKey:l,metaKey:d}=e;if(!(o||l||d)&&this.keysUsed.includes(s))if(e.preventDefault(),e.stopPropagation(),s==="Tab"){if(V(this,Yi)){this.hidden=!0;return}e.shiftKey?(i=(t=this.previousElementSibling)==null?void 0:t.focus)==null||i.call(t):(r=(a=this.nextElementSibling)==null?void 0:a.focus)==null||r.call(a),this.blur()}else s==="Escape"?((n=V(this,qi))==null||n.focus(),V(this,Yi)&&(this.hidden=!0)):s==="Enter"||s===" "?this.handleSelect(e):this.handleMove(e)};Nr=new WeakSet;Zs=function(e){return e.composedPath().find(t=>["menuitemradio","menuitemcheckbox"].includes(t.role))};Bl=new WeakSet;Hp=function(){return this.items.find(e=>e.tabIndex===0)};Pr=new WeakSet;zs=function(e){for(const t of this.items)t.tabIndex=t===e?0:-1};ms=new WeakSet;Wl=function(e,t){const i=[...this.checkedItems];e.type==="radio"&&this.radioGroupItems.forEach(a=>a.checked=!1),t?e.checked=!e.checked:e.checked=!0,this.checkedItems.some((a,r)=>a!=i[r])&&this.dispatchEvent(new Event("change",{bubbles:!0,composed:!0}))};Wt.shadowRootOptions={mode:"open"};Wt.getTemplateHTML=T1;function A1(e){return["menuitem","menuitemradio","menuitemcheckbox"].includes(e?.role)}function en(e){var t;return(t=e.getAttribute("bounds")?Fa(e,`#${e.getAttribute("bounds")}`):at(e)||e.parentElement)!=null?t:e}E.customElements.get("media-chrome-menu")||E.customElements.define("media-chrome-menu",Wt);var ou=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Pt=(e,t,i)=>(ou(e,t,"read from private field"),i?i.call(e):t.get(e)),Vt=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},$o=(e,t,i,a)=>(ou(e,t,"write to private field"),t.set(e,i),i),xt=(e,t,i)=>(ou(e,t,"access private method"),i),ps,$r,Fl,Bp,lu,Wp,du,Fp,$t,Wa,tn,Kl,Kp,vs,Vl;function k1(e){return`
    <style>
      :host {
        transition: var(--media-menu-item-transition,
          background .15s linear,
          opacity .2s ease-in-out
        );
        outline: var(--media-menu-item-outline, 0);
        outline-offset: var(--media-menu-item-outline-offset, -1px);
        cursor: var(--media-cursor, pointer);
        display: flex;
        align-items: center;
        align-self: stretch;
        justify-self: stretch;
        white-space: nowrap;
        white-space-collapse: collapse;
        text-wrap: nowrap;
        padding: .4em .8em .4em 1em;
      }

      :host(:focus-visible) {
        box-shadow: var(--media-menu-item-focus-shadow, inset 0 0 0 2px rgb(27 127 204 / .9));
        outline: var(--media-menu-item-hover-outline, 0);
        outline-offset: var(--media-menu-item-hover-outline-offset,  var(--media-menu-item-outline-offset, -1px));
      }

      :host(:hover) {
        cursor: var(--media-cursor, pointer);
        background: var(--media-menu-item-hover-background, rgb(92 92 102 / .5));
        outline: var(--media-menu-item-hover-outline);
        outline-offset: var(--media-menu-item-hover-outline-offset,  var(--media-menu-item-outline-offset, -1px));
      }

      :host([aria-checked="true"]) {
        background: var(--media-menu-item-checked-background);
      }

      :host([hidden]) {
        display: none;
      }

      :host([disabled]) {
        pointer-events: none;
        color: rgba(255, 255, 255, .3);
      }

      slot:not([name]) {
        width: 100%;
      }

      slot:not([name="submenu"]) {
        display: inline-flex;
        align-items: center;
        transition: inherit;
        opacity: var(--media-menu-item-opacity, 1);
      }

      slot[name="description"] {
        justify-content: end;
      }

      slot[name="description"] > span {
        display: inline-block;
        margin-inline: 1em .2em;
        max-width: var(--media-menu-item-description-max-width, 100px);
        text-overflow: ellipsis;
        overflow: hidden;
        font-size: .8em;
        font-weight: 400;
        text-align: right;
        position: relative;
        top: .04em;
      }

      slot[name="checked-indicator"] {
        display: none;
      }

      :host(:is([role="menuitemradio"],[role="menuitemcheckbox"])) slot[name="checked-indicator"] {
        display: var(--media-menu-item-checked-indicator-display, inline-block);
      }

      
      svg, img, ::slotted(svg), ::slotted(img) {
        height: var(--media-menu-item-icon-height, var(--media-control-height, 24px));
        fill: var(--media-icon-color, var(--media-primary-color, rgb(238 238 238)));
        display: block;
      }

      
      [part~="indicator"],
      ::slotted([part~="indicator"]) {
        fill: var(--media-menu-item-indicator-fill,
          var(--media-icon-color, var(--media-primary-color, rgb(238 238 238))));
        height: var(--media-menu-item-indicator-height, 1.25em);
        margin-right: .5ch;
      }

      [part~="checked-indicator"] {
        visibility: hidden;
      }

      :host([aria-checked="true"]) [part~="checked-indicator"] {
        visibility: visible;
      }
    </style>
    <slot name="checked-indicator">
      <svg aria-hidden="true" viewBox="0 1 24 24" part="checked-indicator indicator">
        <path d="m10 15.17 9.193-9.191 1.414 1.414-10.606 10.606-6.364-6.364 1.414-1.414 4.95 4.95Z"/>
      </svg>
    </slot>
    <slot name="prefix"></slot>
    <slot></slot>
    <slot name="description"></slot>
    <slot name="suffix">
      ${this.getSuffixSlotInnerHTML(e)}
    </slot>
    <slot name="submenu"></slot>
  `}function S1(e){return""}const Xe={TYPE:"type",VALUE:"value",CHECKED:"checked",DISABLED:"disabled"};class qa extends E.HTMLElement{constructor(){if(super(),Vt(this,Fl),Vt(this,lu),Vt(this,du),Vt(this,Wa),Vt(this,Kl),Vt(this,vs),Vt(this,ps,!1),Vt(this,$r,void 0),Vt(this,$t,()=>{var t,i;this.setAttribute("submenusize",`${this.submenuElement.items.length}`);const a=this.shadowRoot.querySelector('slot[name="description"]'),r=(t=this.submenuElement.checkedItems)==null?void 0:t[0],n=(i=r?.dataset.description)!=null?i:r?.text,s=ye.createElement("span");s.textContent=n??"",a.replaceChildren(s)}),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);const t=Qe(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}this.shadowRoot.addEventListener("slotchange",this)}static get observedAttributes(){return[Xe.TYPE,Xe.DISABLED,Xe.CHECKED,Xe.VALUE]}enable(){this.hasAttribute("tabindex")||this.setAttribute("tabindex","-1"),ar(this)&&!this.hasAttribute("aria-checked")&&this.setAttribute("aria-checked","false"),this.addEventListener("click",this),this.addEventListener("keydown",this)}disable(){this.removeAttribute("tabindex"),this.removeEventListener("click",this),this.removeEventListener("keydown",this),this.removeEventListener("keyup",this)}handleEvent(t){switch(t.type){case"slotchange":xt(this,Fl,Bp).call(this,t);break;case"click":this.handleClick(t);break;case"keydown":xt(this,Kl,Kp).call(this,t);break;case"keyup":xt(this,Wa,tn).call(this,t);break}}attributeChangedCallback(t,i,a){t===Xe.CHECKED&&ar(this)&&!Pt(this,ps)?this.setAttribute("aria-checked",a!=null?"true":"false"):t===Xe.TYPE&&a!==i?this.role="menuitem"+a:t===Xe.DISABLED&&a!==i&&(a==null?this.enable():this.disable())}connectedCallback(){this.hasAttribute(Xe.DISABLED)||this.enable(),this.role="menuitem"+this.type,$o(this,$r,ql(this,this.parentNode)),xt(this,vs,Vl).call(this)}disconnectedCallback(){this.disable(),xt(this,vs,Vl).call(this),$o(this,$r,null)}get invokeTarget(){return this.getAttribute("invoketarget")}set invokeTarget(t){this.setAttribute("invoketarget",`${t}`)}get invokeTargetElement(){var t;return this.invokeTarget?(t=ro(this))==null?void 0:t.querySelector(`#${this.invokeTarget}`):this.submenuElement}get submenuElement(){return this.shadowRoot.querySelector('slot[name="submenu"]').assignedElements({flatten:!0})[0]}get type(){var t;return(t=this.getAttribute(Xe.TYPE))!=null?t:""}set type(t){this.setAttribute(Xe.TYPE,`${t}`)}get value(){var t;return(t=this.getAttribute(Xe.VALUE))!=null?t:this.text}set value(t){this.setAttribute(Xe.VALUE,t)}get text(){var t;return((t=this.textContent)!=null?t:"").trim()}get checked(){if(ar(this))return this.getAttribute("aria-checked")==="true"}set checked(t){ar(this)&&($o(this,ps,!0),this.setAttribute("aria-checked",t?"true":"false"),t?this.part.add("checked"):this.part.remove("checked"))}handleClick(t){ar(this)||this.invokeTargetElement&&ti(this,t.target)&&this.invokeTargetElement.dispatchEvent(new iu({relatedTarget:this}))}get keysUsed(){return["Enter"," "]}}ps=new WeakMap;$r=new WeakMap;Fl=new WeakSet;Bp=function(e){const t=e.target;if(!t?.name)for(const a of t.assignedNodes({flatten:!0}))a instanceof Text&&a.textContent.trim()===""&&a.remove();t.name==="submenu"&&(this.submenuElement?xt(this,lu,Wp).call(this):xt(this,du,Fp).call(this))};lu=new WeakSet;Wp=async function(){this.setAttribute("aria-haspopup","menu"),this.setAttribute("aria-expanded",`${!this.submenuElement.hidden}`),this.submenuElement.addEventListener("change",Pt(this,$t)),this.submenuElement.addEventListener("addmenuitem",Pt(this,$t)),this.submenuElement.addEventListener("removemenuitem",Pt(this,$t)),Pt(this,$t).call(this)};du=new WeakSet;Fp=function(){this.removeAttribute("aria-haspopup"),this.removeAttribute("aria-expanded"),this.submenuElement.removeEventListener("change",Pt(this,$t)),this.submenuElement.removeEventListener("addmenuitem",Pt(this,$t)),this.submenuElement.removeEventListener("removemenuitem",Pt(this,$t)),Pt(this,$t).call(this)};$t=new WeakMap;Wa=new WeakSet;tn=function(e){const{key:t}=e;if(!this.keysUsed.includes(t)){this.removeEventListener("keyup",xt(this,Wa,tn));return}this.handleClick(e)};Kl=new WeakSet;Kp=function(e){const{metaKey:t,altKey:i,key:a}=e;if(t||i||!this.keysUsed.includes(a)){this.removeEventListener("keyup",xt(this,Wa,tn));return}this.addEventListener("keyup",xt(this,Wa,tn),{once:!0})};vs=new WeakSet;Vl=function(){var e;const t=(e=Pt(this,$r))==null?void 0:e.radioGroupItems;if(!t)return;let i=t.filter(a=>a.getAttribute("aria-checked")==="true").pop();i||(i=t[0]);for(const a of t)a.setAttribute("aria-checked","false");i?.setAttribute("aria-checked","true")};qa.shadowRootOptions={mode:"open"};qa.getTemplateHTML=k1;qa.getSuffixSlotInnerHTML=S1;function ar(e){return e.type==="radio"||e.type==="checkbox"}function ql(e,t){if(!e)return null;const{host:i}=e.getRootNode();return!t&&i?ql(e,i):t?.items?t:ql(t,t?.parentNode)}E.customElements.get("media-chrome-menu-item")||E.customElements.define("media-chrome-menu-item",qa);function w1(e){return`
    ${Wt.getTemplateHTML(e)}
    <style>
      :host {
        --_menu-bg: rgb(20 20 30 / .8);
        background: var(--media-settings-menu-background,
            var(--media-menu-background,
              var(--media-control-background,
                var(--media-secondary-color, var(--_menu-bg)))));
        min-width: var(--media-settings-menu-min-width, 170px);
        border-radius: 2px 2px 0 0;
        overflow: hidden;
      }

      @-moz-document url-prefix() {
        :host{
          --_menu-bg: rgb(20 20 30);
        }
      }

      :host([role="menu"]) {
        
        justify-content: end;
      }

      slot:not([name]) {
        justify-content: var(--media-settings-menu-justify-content);
        flex-direction: var(--media-settings-menu-flex-direction, column);
        overflow: visible;
      }

      #container.has-expanded {
        --media-settings-menu-item-opacity: 0;
      }
    </style>
  `}class Vp extends Wt{get anchorElement(){return this.anchor!=="auto"?super.anchorElement:at(this).querySelector("media-settings-menu-button")}}Vp.getTemplateHTML=w1;E.customElements.get("media-settings-menu")||E.customElements.define("media-settings-menu",Vp);function I1(e){return`
    ${qa.getTemplateHTML.call(this,e)}
    <style>
      slot:not([name="submenu"]) {
        opacity: var(--media-settings-menu-item-opacity, var(--media-menu-item-opacity));
      }

      :host([aria-expanded="true"]:hover) {
        background: transparent;
      }
    </style>
  `}function R1(e){return`
    <svg aria-hidden="true" viewBox="0 0 20 24">
      <path d="m8.12 17.585-.742-.669 4.2-4.665-4.2-4.666.743-.669 4.803 5.335-4.803 5.334Z"/>
    </svg>
  `}class _o extends qa{}_o.shadowRootOptions={mode:"open"};_o.getTemplateHTML=I1;_o.getSuffixSlotInnerHTML=R1;E.customElements.get("media-settings-menu-item")||E.customElements.define("media-settings-menu-item",_o);class Ya extends Ce{connectedCallback(){super.connectedCallback(),this.invokeTargetElement&&this.setAttribute("aria-haspopup","menu")}get invokeTarget(){return this.getAttribute("invoketarget")}set invokeTarget(t){this.setAttribute("invoketarget",`${t}`)}get invokeTargetElement(){var t;return this.invokeTarget?(t=ro(this))==null?void 0:t.querySelector(`#${this.invokeTarget}`):null}handleClick(){var t;(t=this.invokeTargetElement)==null||t.dispatchEvent(new iu({relatedTarget:this}))}}E.customElements.get("media-chrome-menu-button")||E.customElements.define("media-chrome-menu-button",Ya);function C1(){return`
    <style>
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">
      <svg aria-hidden="true" viewBox="0 0 24 24">
        <path d="M4.5 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm7.5 0a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm7.5 0a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z"/>
      </svg>
    </slot>
  `}function D1(){return C("Settings")}class uu extends Ya{static get observedAttributes(){return[...super.observedAttributes,"target"]}connectedCallback(){super.connectedCallback(),this.setAttribute("aria-label",C("settings"))}get invokeTargetElement(){return this.invokeTarget!=null?super.invokeTargetElement:at(this).querySelector("media-settings-menu")}}uu.getSlotTemplateHTML=C1;uu.getTooltipContentHTML=D1;E.customElements.get("media-settings-menu-button")||E.customElements.define("media-settings-menu-button",uu);var cu=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},qp=(e,t,i)=>(cu(e,t,"read from private field"),i?i.call(e):t.get(e)),yn=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Yl=(e,t,i,a)=>(cu(e,t,"write to private field"),t.set(e,i),i),Tn=(e,t,i)=>(cu(e,t,"access private method"),i),Ar,Xs,fs,Gl,Es,jl;class L1 extends Wt{constructor(){super(...arguments),yn(this,fs),yn(this,Es),yn(this,Ar,[]),yn(this,Xs,void 0)}static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_AUDIO_TRACK_LIST,u.MEDIA_AUDIO_TRACK_ENABLED,u.MEDIA_AUDIO_TRACK_UNAVAILABLE]}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_AUDIO_TRACK_ENABLED&&i!==a?this.value=a:t===u.MEDIA_AUDIO_TRACK_LIST&&i!==a&&(Yl(this,Ar,kb(a??"")),Tn(this,fs,Gl).call(this))}connectedCallback(){super.connectedCallback(),this.addEventListener("change",Tn(this,Es,jl))}disconnectedCallback(){super.disconnectedCallback(),this.removeEventListener("change",Tn(this,Es,jl))}get anchorElement(){var t;return this.anchor!=="auto"?super.anchorElement:(t=at(this))==null?void 0:t.querySelector("media-audio-track-menu-button")}get mediaAudioTrackList(){return qp(this,Ar)}set mediaAudioTrackList(t){Yl(this,Ar,t),Tn(this,fs,Gl).call(this)}get mediaAudioTrackEnabled(){var t;return(t=ae(this,u.MEDIA_AUDIO_TRACK_ENABLED))!=null?t:""}set mediaAudioTrackEnabled(t){re(this,u.MEDIA_AUDIO_TRACK_ENABLED,t)}}Ar=new WeakMap;Xs=new WeakMap;fs=new WeakSet;Gl=function(){if(qp(this,Xs)===JSON.stringify(this.mediaAudioTrackList))return;Yl(this,Xs,JSON.stringify(this.mediaAudioTrackList));const e=this.mediaAudioTrackList;this.defaultSlot.textContent="";for(const t of e){const i=this.formatMenuItemText(t.label,t),a=Ba({type:"radio",text:i,value:`${t.id}`,checked:t.enabled});a.prepend(Gi(this,"checked-indicator")),this.defaultSlot.append(a)}};Es=new WeakSet;jl=function(){if(this.value==null)return;const e=new E.CustomEvent(R.MEDIA_AUDIO_TRACK_REQUEST,{composed:!0,bubbles:!0,detail:this.value});this.dispatchEvent(e)};E.customElements.get("media-audio-track-menu")||E.customElements.define("media-audio-track-menu",L1);const M1=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M11 17H9.5V7H11v10Zm-3-3H6.5v-4H8v4Zm6-5h-1.5v6H14V9Zm3 7h-1.5V8H17v8Z"/>
  <path d="M22 12c0 5.523-4.477 10-10 10S2 17.523 2 12 6.477 2 12 2s10 4.477 10 10Zm-2 0a8 8 0 1 0-16 0 8 8 0 0 0 16 0Z"/>
</svg>`;function x1(){return`
    <style>
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">${M1}</slot>
  `}function O1(){return C("Audio")}class hu extends Ya{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_AUDIO_TRACK_ENABLED,u.MEDIA_AUDIO_TRACK_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),this.setAttribute("aria-label",C("Audio"))}get invokeTargetElement(){var t;return this.invokeTarget!=null?super.invokeTargetElement:(t=at(this))==null?void 0:t.querySelector("media-audio-track-menu")}get mediaAudioTrackEnabled(){var t;return(t=ae(this,u.MEDIA_AUDIO_TRACK_ENABLED))!=null?t:""}set mediaAudioTrackEnabled(t){re(this,u.MEDIA_AUDIO_TRACK_ENABLED,t)}}hu.getSlotTemplateHTML=x1;hu.getTooltipContentHTML=O1;E.customElements.get("media-audio-track-menu-button")||E.customElements.define("media-audio-track-menu-button",hu);var mu=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},N1=(e,t,i)=>(mu(e,t,"read from private field"),t.get(e)),Uo=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},P1=(e,t,i,a)=>(mu(e,t,"write to private field"),t.set(e,i),i),Ho=(e,t,i)=>(mu(e,t,"access private method"),i),Js,Ql,Yp,_s,Zl;const $1=`
  <svg aria-hidden="true" viewBox="0 0 26 24" part="captions-indicator indicator">
    <path d="M22.83 5.68a2.58 2.58 0 0 0-2.3-2.5c-3.62-.24-11.44-.24-15.06 0a2.58 2.58 0 0 0-2.3 2.5c-.23 4.21-.23 8.43 0 12.64a2.58 2.58 0 0 0 2.3 2.5c3.62.24 11.44.24 15.06 0a2.58 2.58 0 0 0 2.3-2.5c.23-4.21.23-8.43 0-12.64Zm-11.39 9.45a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.92 3.92 0 0 1 .92-2.77 3.18 3.18 0 0 1 2.43-1 2.94 2.94 0 0 1 2.13.78c.364.359.62.813.74 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.17 1.61 1.61 0 0 0-1.29.58 2.79 2.79 0 0 0-.5 1.89 3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.48 1.48 0 0 0 1-.37 2.1 2.1 0 0 0 .59-1.14l1.4.44a3.23 3.23 0 0 1-1.07 1.69Zm7.22 0a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.88 3.88 0 0 1 .93-2.77 3.14 3.14 0 0 1 2.42-1 3 3 0 0 1 2.16.82 2.8 2.8 0 0 1 .73 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.21 1.61 1.61 0 0 0-1.29.58A2.79 2.79 0 0 0 15 12a3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.44 1.44 0 0 0 1-.37 2.1 2.1 0 0 0 .6-1.15l1.4.44a3.17 3.17 0 0 1-1.1 1.7Z"/>
  </svg>`;function U1(e){return`
    ${Wt.getTemplateHTML(e)}
    <slot name="captions-indicator" hidden>${$1}</slot>
  `}class Gp extends Wt{constructor(){super(...arguments),Uo(this,Ql),Uo(this,_s),Uo(this,Js,void 0)}static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_SUBTITLES_LIST,u.MEDIA_SUBTITLES_SHOWING]}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_SUBTITLES_LIST&&i!==a?Ho(this,Ql,Yp).call(this):t===u.MEDIA_SUBTITLES_SHOWING&&i!==a&&(this.value=a)}connectedCallback(){super.connectedCallback(),this.addEventListener("change",Ho(this,_s,Zl))}disconnectedCallback(){super.disconnectedCallback(),this.removeEventListener("change",Ho(this,_s,Zl))}get anchorElement(){return this.anchor!=="auto"?super.anchorElement:at(this).querySelector("media-captions-menu-button")}get mediaSubtitlesList(){return Fc(this,u.MEDIA_SUBTITLES_LIST)}set mediaSubtitlesList(t){Kc(this,u.MEDIA_SUBTITLES_LIST,t)}get mediaSubtitlesShowing(){return Fc(this,u.MEDIA_SUBTITLES_SHOWING)}set mediaSubtitlesShowing(t){Kc(this,u.MEDIA_SUBTITLES_SHOWING,t)}}Js=new WeakMap;Ql=new WeakSet;Yp=function(){var e;if(N1(this,Js)===JSON.stringify(this.mediaSubtitlesList))return;P1(this,Js,JSON.stringify(this.mediaSubtitlesList)),this.defaultSlot.textContent="";const t=!this.value,i=Ba({type:"radio",text:this.formatMenuItemText(C("Off")),value:"off",checked:t});i.prepend(Gi(this,"checked-indicator")),this.defaultSlot.append(i);const a=this.mediaSubtitlesList;for(const r of a){const n=Ba({type:"radio",text:this.formatMenuItemText(r.label,r),value:sl(r),checked:this.value==sl(r)});n.prepend(Gi(this,"checked-indicator")),((e=r.kind)!=null?e:"subs")==="captions"&&n.append(Gi(this,"captions-indicator")),this.defaultSlot.append(n)}};_s=new WeakSet;Zl=function(){const e=this.mediaSubtitlesShowing,t=this.getAttribute(u.MEDIA_SUBTITLES_SHOWING),i=this.value!==t;if(e?.length&&i&&this.dispatchEvent(new E.CustomEvent(R.MEDIA_DISABLE_SUBTITLES_REQUEST,{composed:!0,bubbles:!0,detail:e})),!this.value||!i)return;const a=new E.CustomEvent(R.MEDIA_SHOW_SUBTITLES_REQUEST,{composed:!0,bubbles:!0,detail:this.value});this.dispatchEvent(a)};Gp.getTemplateHTML=U1;const Fc=(e,t)=>{const i=e.getAttribute(t);return i?uo(i):[]},Kc=(e,t,i)=>{if(!i?.length){e.removeAttribute(t);return}const a=Yr(i);e.getAttribute(t)!==a&&e.setAttribute(t,a)};E.customElements.get("media-captions-menu")||E.customElements.define("media-captions-menu",Gp);const H1=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M22.83 5.68a2.58 2.58 0 0 0-2.3-2.5c-3.62-.24-11.44-.24-15.06 0a2.58 2.58 0 0 0-2.3 2.5c-.23 4.21-.23 8.43 0 12.64a2.58 2.58 0 0 0 2.3 2.5c3.62.24 11.44.24 15.06 0a2.58 2.58 0 0 0 2.3-2.5c.23-4.21.23-8.43 0-12.64Zm-11.39 9.45a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.92 3.92 0 0 1 .92-2.77 3.18 3.18 0 0 1 2.43-1 2.94 2.94 0 0 1 2.13.78c.364.359.62.813.74 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.17 1.61 1.61 0 0 0-1.29.58 2.79 2.79 0 0 0-.5 1.89 3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.48 1.48 0 0 0 1-.37 2.1 2.1 0 0 0 .59-1.14l1.4.44a3.23 3.23 0 0 1-1.07 1.69Zm7.22 0a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.88 3.88 0 0 1 .93-2.77 3.14 3.14 0 0 1 2.42-1 3 3 0 0 1 2.16.82 2.8 2.8 0 0 1 .73 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.21 1.61 1.61 0 0 0-1.29.58A2.79 2.79 0 0 0 15 12a3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.44 1.44 0 0 0 1-.37 2.1 2.1 0 0 0 .6-1.15l1.4.44a3.17 3.17 0 0 1-1.1 1.7Z"/>
</svg>`,B1=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M17.73 14.09a1.4 1.4 0 0 1-1 .37 1.579 1.579 0 0 1-1.27-.58A3 3 0 0 1 15 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34A2.89 2.89 0 0 0 19 9.07a3 3 0 0 0-2.14-.78 3.14 3.14 0 0 0-2.42 1 3.91 3.91 0 0 0-.93 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.17 3.17 0 0 0 1.07-1.74l-1.4-.45c-.083.43-.3.822-.62 1.12Zm-7.22 0a1.43 1.43 0 0 1-1 .37 1.58 1.58 0 0 1-1.27-.58A3 3 0 0 1 7.76 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34a2.81 2.81 0 0 0-.74-1.32 2.94 2.94 0 0 0-2.13-.78 3.18 3.18 0 0 0-2.43 1 4 4 0 0 0-.92 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.23 3.23 0 0 0 1.07-1.74l-1.4-.45a2.06 2.06 0 0 1-.6 1.07Zm12.32-8.41a2.59 2.59 0 0 0-2.3-2.51C18.72 3.05 15.86 3 13 3c-2.86 0-5.72.05-7.53.17a2.59 2.59 0 0 0-2.3 2.51c-.23 4.207-.23 8.423 0 12.63a2.57 2.57 0 0 0 2.3 2.5c1.81.13 4.67.19 7.53.19 2.86 0 5.72-.06 7.53-.19a2.57 2.57 0 0 0 2.3-2.5c.23-4.207.23-8.423 0-12.63Zm-1.49 12.53a1.11 1.11 0 0 1-.91 1.11c-1.67.11-4.45.18-7.43.18-2.98 0-5.76-.07-7.43-.18a1.11 1.11 0 0 1-.91-1.11c-.21-4.14-.21-8.29 0-12.43a1.11 1.11 0 0 1 .91-1.11C7.24 4.56 10 4.49 13 4.49s5.76.07 7.43.18a1.11 1.11 0 0 1 .91 1.11c.21 4.14.21 8.29 0 12.43Z"/>
</svg>`;function W1(){return`
    <style>
      :host([aria-checked="true"]) slot[name=off] {
        display: none !important;
      }

      
      :host(:not([aria-checked="true"])) slot[name=on] {
        display: none !important;
      }

      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="on">${H1}</slot>
      <slot name="off">${B1}</slot>
    </slot>
  `}function F1(){return C("Captions")}const Vc=e=>{e.setAttribute("aria-checked",Mm(e).toString())};class pu extends Ya{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_SUBTITLES_LIST,u.MEDIA_SUBTITLES_SHOWING]}connectedCallback(){super.connectedCallback(),this.setAttribute("aria-label",C("closed captions")),Vc(this)}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_SUBTITLES_SHOWING&&Vc(this)}get invokeTargetElement(){var t;return this.invokeTarget!=null?super.invokeTargetElement:(t=at(this))==null?void 0:t.querySelector("media-captions-menu")}get mediaSubtitlesList(){return qc(this,u.MEDIA_SUBTITLES_LIST)}set mediaSubtitlesList(t){Yc(this,u.MEDIA_SUBTITLES_LIST,t)}get mediaSubtitlesShowing(){return qc(this,u.MEDIA_SUBTITLES_SHOWING)}set mediaSubtitlesShowing(t){Yc(this,u.MEDIA_SUBTITLES_SHOWING,t)}}pu.getSlotTemplateHTML=W1;pu.getTooltipContentHTML=F1;const qc=(e,t)=>{const i=e.getAttribute(t);return i?uo(i):[]},Yc=(e,t,i)=>{if(!i?.length){e.removeAttribute(t);return}const a=Yr(i);e.getAttribute(t)!==a&&e.setAttribute(t,a)};E.customElements.get("media-captions-menu-button")||E.customElements.define("media-captions-menu-button",pu);var jp=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},ba=(e,t,i)=>(jp(e,t,"read from private field"),i?i.call(e):t.get(e)),Bo=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},rr=(e,t,i)=>(jp(e,t,"access private method"),i),pi,kr,bs,gs,zl;const Wo={RATES:"rates"};class K1 extends Wt{constructor(){super(),Bo(this,kr),Bo(this,gs),Bo(this,pi,new Sd(this,Wo.RATES,{defaultValue:lp})),rr(this,kr,bs).call(this)}static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_PLAYBACK_RATE,Wo.RATES]}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_PLAYBACK_RATE&&i!=a?this.value=a:t===Wo.RATES&&i!=a&&(ba(this,pi).value=a,rr(this,kr,bs).call(this))}connectedCallback(){super.connectedCallback(),this.addEventListener("change",rr(this,gs,zl))}disconnectedCallback(){super.disconnectedCallback(),this.removeEventListener("change",rr(this,gs,zl))}get anchorElement(){return this.anchor!=="auto"?super.anchorElement:at(this).querySelector("media-playback-rate-menu-button")}get rates(){return ba(this,pi)}set rates(t){t?Array.isArray(t)?ba(this,pi).value=t.join(" "):typeof t=="string"&&(ba(this,pi).value=t):ba(this,pi).value="",rr(this,kr,bs).call(this)}get mediaPlaybackRate(){return ie(this,u.MEDIA_PLAYBACK_RATE,Sa)}set mediaPlaybackRate(t){de(this,u.MEDIA_PLAYBACK_RATE,t)}}pi=new WeakMap;kr=new WeakSet;bs=function(){this.defaultSlot.textContent="";for(const e of ba(this,pi)){const t=Ba({type:"radio",text:this.formatMenuItemText(`${e}x`,e),value:e,checked:this.mediaPlaybackRate===Number(e)});t.prepend(Gi(this,"checked-indicator")),this.defaultSlot.append(t)}};gs=new WeakSet;zl=function(){if(!this.value)return;const e=new E.CustomEvent(R.MEDIA_PLAYBACK_RATE_REQUEST,{composed:!0,bubbles:!0,detail:this.value});this.dispatchEvent(e)};E.customElements.get("media-playback-rate-menu")||E.customElements.define("media-playback-rate-menu",K1);const ys=1;function V1(e){return`
    <style>
      :host {
        min-width: 5ch;
        padding: var(--media-button-padding, var(--media-control-padding, 10px 5px));
      }
      
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">${e.mediaplaybackrate||ys}x</slot>
  `}function q1(){return C("Playback rate")}class vu extends Ya{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_PLAYBACK_RATE]}constructor(){var t;super(),this.container=this.shadowRoot.querySelector('slot[name="icon"]'),this.container.innerHTML=`${(t=this.mediaPlaybackRate)!=null?t:ys}x`}attributeChangedCallback(t,i,a){if(super.attributeChangedCallback(t,i,a),t===u.MEDIA_PLAYBACK_RATE){const r=a?+a:Number.NaN,n=Number.isNaN(r)?ys:r;this.container.innerHTML=`${n}x`,this.setAttribute("aria-label",C("Playback rate {playbackRate}",{playbackRate:n}))}}get invokeTargetElement(){return this.invokeTarget!=null?super.invokeTargetElement:at(this).querySelector("media-playback-rate-menu")}get mediaPlaybackRate(){return ie(this,u.MEDIA_PLAYBACK_RATE,ys)}set mediaPlaybackRate(t){de(this,u.MEDIA_PLAYBACK_RATE,t)}}vu.getSlotTemplateHTML=V1;vu.getTooltipContentHTML=q1;E.customElements.get("media-playback-rate-menu-button")||E.customElements.define("media-playback-rate-menu-button",vu);var fu=(e,t,i)=>{if(!t.has(e))throw TypeError("Cannot "+i)},Sr=(e,t,i)=>(fu(e,t,"read from private field"),i?i.call(e):t.get(e)),An=(e,t,i)=>{if(t.has(e))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(e):t.set(e,i)},Gc=(e,t,i,a)=>(fu(e,t,"write to private field"),t.set(e,i),i),ra=(e,t,i)=>(fu(e,t,"access private method"),i),wr,wa,ga,Ir,Ts,Xl;class Y1 extends Wt{constructor(){super(...arguments),An(this,ga),An(this,Ts),An(this,wr,[]),An(this,wa,{})}static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_RENDITION_LIST,u.MEDIA_RENDITION_SELECTED,u.MEDIA_RENDITION_UNAVAILABLE,u.MEDIA_HEIGHT]}attributeChangedCallback(t,i,a){super.attributeChangedCallback(t,i,a),t===u.MEDIA_RENDITION_SELECTED&&i!==a?(this.value=a??"auto",ra(this,ga,Ir).call(this)):t===u.MEDIA_RENDITION_LIST&&i!==a?(Gc(this,wr,gb(a)),ra(this,ga,Ir).call(this)):t===u.MEDIA_HEIGHT&&i!==a&&ra(this,ga,Ir).call(this)}connectedCallback(){super.connectedCallback(),this.addEventListener("change",ra(this,Ts,Xl))}disconnectedCallback(){super.disconnectedCallback(),this.removeEventListener("change",ra(this,Ts,Xl))}get anchorElement(){return this.anchor!=="auto"?super.anchorElement:at(this).querySelector("media-rendition-menu-button")}get mediaRenditionList(){return Sr(this,wr)}set mediaRenditionList(t){Gc(this,wr,t),ra(this,ga,Ir).call(this)}get mediaRenditionSelected(){return ae(this,u.MEDIA_RENDITION_SELECTED)}set mediaRenditionSelected(t){re(this,u.MEDIA_RENDITION_SELECTED,t)}get mediaHeight(){return ie(this,u.MEDIA_HEIGHT)}set mediaHeight(t){de(this,u.MEDIA_HEIGHT,t)}}wr=new WeakMap;wa=new WeakMap;ga=new WeakSet;Ir=function(){if(Sr(this,wa).mediaRenditionList===JSON.stringify(this.mediaRenditionList)&&Sr(this,wa).mediaHeight===this.mediaHeight)return;Sr(this,wa).mediaRenditionList=JSON.stringify(this.mediaRenditionList),Sr(this,wa).mediaHeight=this.mediaHeight;const e=this.mediaRenditionList.sort((n,s)=>s.height-n.height);for(const n of e)n.selected=n.id===this.mediaRenditionSelected;this.defaultSlot.textContent="";const t=!this.mediaRenditionSelected;for(const n of e){const s=this.formatMenuItemText(`${Math.min(n.width,n.height)}p`,n),o=Ba({type:"radio",text:s,value:`${n.id}`,checked:n.selected&&!t});o.prepend(Gi(this,"checked-indicator")),this.defaultSlot.append(o)}const i=t?this.formatMenuItemText(`${C("Auto")} (${this.mediaHeight}p)`):this.formatMenuItemText(C("Auto")),a=Ba({type:"radio",text:i,value:"auto",checked:t}),r=this.mediaHeight>0?`${C("Auto")} (${this.mediaHeight}p)`:C("Auto");a.dataset.description=r,a.prepend(Gi(this,"checked-indicator")),this.defaultSlot.append(a)};Ts=new WeakSet;Xl=function(){if(this.value==null)return;const e=new E.CustomEvent(R.MEDIA_RENDITION_REQUEST,{composed:!0,bubbles:!0,detail:this.value});this.dispatchEvent(e)};E.customElements.get("media-rendition-menu")||E.customElements.define("media-rendition-menu",Y1);const G1=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M13.5 2.5h2v6h-2v-2h-11v-2h11v-2Zm4 2h4v2h-4v-2Zm-12 4h2v6h-2v-2h-3v-2h3v-2Zm4 2h12v2h-12v-2Zm1 4h2v6h-2v-2h-8v-2h8v-2Zm4 2h7v2h-7v-2Z" />
</svg>`;function j1(){return`
    <style>
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">${G1}</slot>
  `}function Q1(){return C("Quality")}class Eu extends Ya{static get observedAttributes(){return[...super.observedAttributes,u.MEDIA_RENDITION_SELECTED,u.MEDIA_RENDITION_UNAVAILABLE,u.MEDIA_HEIGHT]}connectedCallback(){super.connectedCallback(),this.setAttribute("aria-label",C("quality"))}get invokeTargetElement(){return this.invokeTarget!=null?super.invokeTargetElement:at(this).querySelector("media-rendition-menu")}get mediaRenditionSelected(){return ae(this,u.MEDIA_RENDITION_SELECTED)}set mediaRenditionSelected(t){re(this,u.MEDIA_RENDITION_SELECTED,t)}get mediaHeight(){return ie(this,u.MEDIA_HEIGHT)}set mediaHeight(t){de(this,u.MEDIA_HEIGHT,t)}}Eu.getSlotTemplateHTML=j1;Eu.getTooltipContentHTML=Q1;E.customElements.get("media-rendition-menu-button")||E.customElements.define("media-rendition-menu-button",Eu);var Qp=e=>{throw TypeError(e)},_u=(e,t,i)=>t.has(e)||Qp("Cannot "+i),j=(e,t,i)=>(_u(e,t,"read from private field"),i?i.call(e):t.get(e)),ht=(e,t,i)=>t.has(e)?Qp("Cannot add the same private member more than once"):t instanceof WeakSet?t.add(e):t.set(e,i),Ht=(e,t,i,a)=>(_u(e,t,"write to private field"),t.set(e,i),i),ve=(e,t,i)=>(_u(e,t,"access private method"),i),bo=class{addEventListener(){}removeEventListener(){}dispatchEvent(e){return!0}};if(typeof DocumentFragment>"u"){class e extends bo{}globalThis.DocumentFragment=e}var bu=class extends bo{},Z1=class extends bo{},z1={get(e){},define(e,t,i){},getName(e){return null},upgrade(e){},whenDefined(e){return Promise.resolve(bu)}},As,X1=class{constructor(t,i={}){ht(this,As),Ht(this,As,i?.detail)}get detail(){return j(this,As)}initCustomEvent(){}};As=new WeakMap;function J1(e,t){return new bu}var Zp={document:{createElement:J1},DocumentFragment,customElements:z1,CustomEvent:X1,EventTarget:bo,HTMLElement:bu,HTMLVideoElement:Z1},zp=typeof window>"u"||typeof globalThis.customElements>"u",Ot=zp?Zp:globalThis,gu=zp?Zp.document:globalThis.document;function ey(e){let t="";return Object.entries(e).forEach(([i,a])=>{a!=null&&(t+=`${Jl(i)}: ${a}; `)}),t?t.trim():void 0}function Jl(e){return e.replace(/([a-z])([A-Z])/g,"$1-$2").toLowerCase()}function Xp(e){return e.replace(/[-_]([a-z])/g,(t,i)=>i.toUpperCase())}function Ye(e){if(e==null)return;let t=+e;return Number.isNaN(t)?void 0:t}function Jp(e){let t=ty(e).toString();return t?"?"+t:""}function ty(e){let t={};for(let i in e)e[i]!=null&&(t[i]=e[i]);return new URLSearchParams(t)}var ev=(e,t)=>!e||!t?!1:e.contains(t)?!0:ev(e,t.getRootNode().host),tv="mux.com",iy=()=>{try{return"3.5.1"}catch{}return"UNKNOWN"},ay=iy(),iv=()=>ay,ry=(e,{token:t,customDomain:i=tv,thumbnailTime:a,programTime:r}={})=>{var n;let s=t==null?a:void 0,{aud:o}=(n=Ia(t))!=null?n:{};if(!(t&&o!=="t"))return`https://image.${i}/${e}/thumbnail.webp${Jp({token:t,time:s,program_time:r})}`},ny=(e,{token:t,customDomain:i=tv,programStartTime:a,programEndTime:r}={})=>{var n;let{aud:s}=(n=Ia(t))!=null?n:{};if(!(t&&s!=="s"))return`https://image.${i}/${e}/storyboard.vtt${Jp({token:t,format:"webp",program_start_time:a,program_end_time:r})}`},yu=e=>{if(e){if([G.LIVE,G.ON_DEMAND].includes(e))return e;if(e!=null&&e.includes("live"))return G.LIVE}},sy={crossorigin:"crossOrigin",playsinline:"playsInline"};function oy(e){var t;return(t=sy[e])!=null?t:Xp(e)}var ya,Ta,$e,ly=class{constructor(t,i){ht(this,ya),ht(this,Ta),ht(this,$e,[]),Ht(this,ya,t),Ht(this,Ta,i)}[Symbol.iterator](){return j(this,$e).values()}get length(){return j(this,$e).length}get value(){var t;return(t=j(this,$e).join(" "))!=null?t:""}set value(t){var i;t!==this.value&&(Ht(this,$e,[]),this.add(...(i=t?.split(" "))!=null?i:[]))}toString(){return this.value}item(t){return j(this,$e)[t]}values(){return j(this,$e).values()}keys(){return j(this,$e).keys()}forEach(t){j(this,$e).forEach(t)}add(...t){var i,a;t.forEach(r=>{this.contains(r)||j(this,$e).push(r)}),!(this.value===""&&!((i=j(this,ya))!=null&&i.hasAttribute(`${j(this,Ta)}`)))&&((a=j(this,ya))==null||a.setAttribute(`${j(this,Ta)}`,`${this.value}`))}remove(...t){var i;t.forEach(a=>{j(this,$e).splice(j(this,$e).indexOf(a),1)}),(i=j(this,ya))==null||i.setAttribute(`${j(this,Ta)}`,`${this.value}`)}contains(t){return j(this,$e).includes(t)}toggle(t,i){return typeof i<"u"?i?(this.add(t),!0):(this.remove(t),!1):this.contains(t)?(this.remove(t),!1):(this.add(t),!0)}replace(t,i){this.remove(t),this.add(i)}};ya=new WeakMap,Ta=new WeakMap,$e=new WeakMap;var av=`[mux-player ${iv()}]`;function jt(...e){console.warn(av,...e)}function et(...e){console.error(av,...e)}function rv(e){var t;let i=(t=e.message)!=null?t:"";e.context&&(i+=` ${e.context}`),e.file&&(i+=` ${L("Read more: ")}
https://github.com/muxinc/elements/blob/main/errors/${e.file}`),jt(i)}var Le={AUTOPLAY:"autoplay",CROSSORIGIN:"crossorigin",LOOP:"loop",MUTED:"muted",PLAYSINLINE:"playsinline",PRELOAD:"preload"},Mi={VOLUME:"volume",PLAYBACKRATE:"playbackrate",MUTED:"muted"},jc=Object.freeze({length:0,start(e){let t=e>>>0;if(t>=this.length)throw new DOMException(`Failed to execute 'start' on 'TimeRanges': The index provided (${t}) is greater than or equal to the maximum bound (${this.length}).`);return 0},end(e){let t=e>>>0;if(t>=this.length)throw new DOMException(`Failed to execute 'end' on 'TimeRanges': The index provided (${t}) is greater than or equal to the maximum bound (${this.length}).`);return 0}}),dy=Object.values(Le).filter(e=>Le.PLAYSINLINE!==e),uy=Object.values(Mi),cy=[...dy,...uy],hy=class extends Ot.HTMLElement{static get observedAttributes(){return cy}constructor(){super()}attributeChangedCallback(e,t,i){var a,r;switch(e){case Mi.MUTED:{this.media&&(this.media.muted=i!=null,this.media.defaultMuted=i!=null);return}case Mi.VOLUME:{let n=(a=Ye(i))!=null?a:1;this.media&&(this.media.volume=n);return}case Mi.PLAYBACKRATE:{let n=(r=Ye(i))!=null?r:1;this.media&&(this.media.playbackRate=n,this.media.defaultPlaybackRate=n);return}}}play(){var e,t;return(t=(e=this.media)==null?void 0:e.play())!=null?t:Promise.reject()}pause(){var e;(e=this.media)==null||e.pause()}load(){var e;(e=this.media)==null||e.load()}get media(){var e;return(e=this.shadowRoot)==null?void 0:e.querySelector("mux-video")}get audioTracks(){return this.media.audioTracks}get videoTracks(){return this.media.videoTracks}get audioRenditions(){return this.media.audioRenditions}get videoRenditions(){return this.media.videoRenditions}get paused(){var e,t;return(t=(e=this.media)==null?void 0:e.paused)!=null?t:!0}get duration(){var e,t;return(t=(e=this.media)==null?void 0:e.duration)!=null?t:NaN}get ended(){var e,t;return(t=(e=this.media)==null?void 0:e.ended)!=null?t:!1}get buffered(){var e,t;return(t=(e=this.media)==null?void 0:e.buffered)!=null?t:jc}get seekable(){var e,t;return(t=(e=this.media)==null?void 0:e.seekable)!=null?t:jc}get readyState(){var e,t;return(t=(e=this.media)==null?void 0:e.readyState)!=null?t:0}get videoWidth(){var e,t;return(t=(e=this.media)==null?void 0:e.videoWidth)!=null?t:0}get videoHeight(){var e,t;return(t=(e=this.media)==null?void 0:e.videoHeight)!=null?t:0}get currentSrc(){var e,t;return(t=(e=this.media)==null?void 0:e.currentSrc)!=null?t:""}get currentTime(){var e,t;return(t=(e=this.media)==null?void 0:e.currentTime)!=null?t:0}set currentTime(e){this.media&&(this.media.currentTime=Number(e))}get volume(){var e,t;return(t=(e=this.media)==null?void 0:e.volume)!=null?t:1}set volume(e){this.media&&(this.media.volume=Number(e))}get playbackRate(){var e,t;return(t=(e=this.media)==null?void 0:e.playbackRate)!=null?t:1}set playbackRate(e){this.media&&(this.media.playbackRate=Number(e))}get defaultPlaybackRate(){var e;return(e=Ye(this.getAttribute(Mi.PLAYBACKRATE)))!=null?e:1}set defaultPlaybackRate(e){e!=null?this.setAttribute(Mi.PLAYBACKRATE,`${e}`):this.removeAttribute(Mi.PLAYBACKRATE)}get crossOrigin(){return nr(this,Le.CROSSORIGIN)}set crossOrigin(e){this.setAttribute(Le.CROSSORIGIN,`${e}`)}get autoplay(){return nr(this,Le.AUTOPLAY)!=null}set autoplay(e){e?this.setAttribute(Le.AUTOPLAY,typeof e=="string"?e:""):this.removeAttribute(Le.AUTOPLAY)}get loop(){return nr(this,Le.LOOP)!=null}set loop(e){e?this.setAttribute(Le.LOOP,""):this.removeAttribute(Le.LOOP)}get muted(){var e,t;return(t=(e=this.media)==null?void 0:e.muted)!=null?t:!1}set muted(e){this.media&&(this.media.muted=!!e)}get defaultMuted(){return nr(this,Le.MUTED)!=null}set defaultMuted(e){e?this.setAttribute(Le.MUTED,""):this.removeAttribute(Le.MUTED)}get playsInline(){return nr(this,Le.PLAYSINLINE)!=null}set playsInline(e){et("playsInline is set to true by default and is not currently supported as a setter.")}get preload(){return this.media?this.media.preload:this.getAttribute("preload")}set preload(e){["","none","metadata","auto"].includes(e)?this.setAttribute(Le.PRELOAD,e):this.removeAttribute(Le.PRELOAD)}};function nr(e,t){return e.media?e.media.getAttribute(t):e.getAttribute(t)}var Qc=hy,my=`:host {
  --media-control-display: var(--controls);
  --media-loading-indicator-display: var(--loading-indicator);
  --media-dialog-display: var(--dialog);
  --media-play-button-display: var(--play-button);
  --media-live-button-display: var(--live-button);
  --media-seek-backward-button-display: var(--seek-backward-button);
  --media-seek-forward-button-display: var(--seek-forward-button);
  --media-mute-button-display: var(--mute-button);
  --media-captions-button-display: var(--captions-button);
  --media-captions-menu-button-display: var(--captions-menu-button, var(--media-captions-button-display));
  --media-rendition-menu-button-display: var(--rendition-menu-button);
  --media-audio-track-menu-button-display: var(--audio-track-menu-button);
  --media-airplay-button-display: var(--airplay-button);
  --media-pip-button-display: var(--pip-button);
  --media-fullscreen-button-display: var(--fullscreen-button);
  --media-cast-button-display: var(--cast-button, var(--_cast-button-drm-display));
  --media-playback-rate-button-display: var(--playback-rate-button);
  --media-playback-rate-menu-button-display: var(--playback-rate-menu-button);
  --media-volume-range-display: var(--volume-range);
  --media-time-range-display: var(--time-range);
  --media-time-display-display: var(--time-display);
  --media-duration-display-display: var(--duration-display);
  --media-title-display-display: var(--title-display);

  display: inline-block;
  line-height: 0;
  width: 100%;
}

a {
  color: #fff;
  font-size: 0.9em;
  text-decoration: underline;
}

media-theme {
  display: inline-block;
  line-height: 0;
  width: 100%;
  height: 100%;
  direction: ltr;
}

media-poster-image {
  display: inline-block;
  line-height: 0;
  width: 100%;
  height: 100%;
}

media-poster-image:not([src]):not([placeholdersrc]) {
  display: none;
}

::part(top),
[part~='top'] {
  --media-control-display: var(--controls, var(--top-controls));
  --media-play-button-display: var(--play-button, var(--top-play-button));
  --media-live-button-display: var(--live-button, var(--top-live-button));
  --media-seek-backward-button-display: var(--seek-backward-button, var(--top-seek-backward-button));
  --media-seek-forward-button-display: var(--seek-forward-button, var(--top-seek-forward-button));
  --media-mute-button-display: var(--mute-button, var(--top-mute-button));
  --media-captions-button-display: var(--captions-button, var(--top-captions-button));
  --media-captions-menu-button-display: var(
    --captions-menu-button,
    var(--media-captions-button-display, var(--top-captions-menu-button))
  );
  --media-rendition-menu-button-display: var(--rendition-menu-button, var(--top-rendition-menu-button));
  --media-audio-track-menu-button-display: var(--audio-track-menu-button, var(--top-audio-track-menu-button));
  --media-airplay-button-display: var(--airplay-button, var(--top-airplay-button));
  --media-pip-button-display: var(--pip-button, var(--top-pip-button));
  --media-fullscreen-button-display: var(--fullscreen-button, var(--top-fullscreen-button));
  --media-cast-button-display: var(--cast-button, var(--top-cast-button, var(--_cast-button-drm-display)));
  --media-playback-rate-button-display: var(--playback-rate-button, var(--top-playback-rate-button));
  --media-playback-rate-menu-button-display: var(
    --captions-menu-button,
    var(--media-playback-rate-button-display, var(--top-playback-rate-menu-button))
  );
  --media-volume-range-display: var(--volume-range, var(--top-volume-range));
  --media-time-range-display: var(--time-range, var(--top-time-range));
  --media-time-display-display: var(--time-display, var(--top-time-display));
  --media-duration-display-display: var(--duration-display, var(--top-duration-display));
  --media-title-display-display: var(--title-display, var(--top-title-display));
}

::part(center),
[part~='center'] {
  --media-control-display: var(--controls, var(--center-controls));
  --media-play-button-display: var(--play-button, var(--center-play-button));
  --media-live-button-display: var(--live-button, var(--center-live-button));
  --media-seek-backward-button-display: var(--seek-backward-button, var(--center-seek-backward-button));
  --media-seek-forward-button-display: var(--seek-forward-button, var(--center-seek-forward-button));
  --media-mute-button-display: var(--mute-button, var(--center-mute-button));
  --media-captions-button-display: var(--captions-button, var(--center-captions-button));
  --media-captions-menu-button-display: var(
    --captions-menu-button,
    var(--media-captions-button-display, var(--center-captions-menu-button))
  );
  --media-rendition-menu-button-display: var(--rendition-menu-button, var(--center-rendition-menu-button));
  --media-audio-track-menu-button-display: var(--audio-track-menu-button, var(--center-audio-track-menu-button));
  --media-airplay-button-display: var(--airplay-button, var(--center-airplay-button));
  --media-pip-button-display: var(--pip-button, var(--center-pip-button));
  --media-fullscreen-button-display: var(--fullscreen-button, var(--center-fullscreen-button));
  --media-cast-button-display: var(--cast-button, var(--center-cast-button, var(--_cast-button-drm-display)));
  --media-playback-rate-button-display: var(--playback-rate-button, var(--center-playback-rate-button));
  --media-playback-rate-menu-button-display: var(
    --playback-rate-menu-button,
    var(--media-playback-rate-button-display, var(--center-playback-rate-menu-button))
  );
  --media-volume-range-display: var(--volume-range, var(--center-volume-range));
  --media-time-range-display: var(--time-range, var(--center-time-range));
  --media-time-display-display: var(--time-display, var(--center-time-display));
  --media-duration-display-display: var(--duration-display, var(--center-duration-display));
}

::part(bottom),
[part~='bottom'] {
  --media-control-display: var(--controls, var(--bottom-controls));
  --media-play-button-display: var(--play-button, var(--bottom-play-button));
  --media-live-button-display: var(--live-button, var(--bottom-live-button));
  --media-seek-backward-button-display: var(--seek-backward-button, var(--bottom-seek-backward-button));
  --media-seek-forward-button-display: var(--seek-forward-button, var(--bottom-seek-forward-button));
  --media-mute-button-display: var(--mute-button, var(--bottom-mute-button));
  --media-captions-button-display: var(--captions-button, var(--bottom-captions-button));
  --media-captions-menu-button-display: var(
    --captions-menu-button,
    var(--media-captions-button-display, var(--bottom-captions-menu-button))
  );
  --media-rendition-menu-button-display: var(--rendition-menu-button, var(--bottom-rendition-menu-button));
  --media-audio-track-menu-button-display: var(--audio-track-menu-button, var(--bottom-audio-track-menu-button));
  --media-airplay-button-display: var(--airplay-button, var(--bottom-airplay-button));
  --media-pip-button-display: var(--pip-button, var(--bottom-pip-button));
  --media-fullscreen-button-display: var(--fullscreen-button, var(--bottom-fullscreen-button));
  --media-cast-button-display: var(--cast-button, var(--bottom-cast-button, var(--_cast-button-drm-display)));
  --media-playback-rate-button-display: var(--playback-rate-button, var(--bottom-playback-rate-button));
  --media-playback-rate-menu-button-display: var(
    --playback-rate-menu-button,
    var(--media-playback-rate-button-display, var(--bottom-playback-rate-menu-button))
  );
  --media-volume-range-display: var(--volume-range, var(--bottom-volume-range));
  --media-time-range-display: var(--time-range, var(--bottom-time-range));
  --media-time-display-display: var(--time-display, var(--bottom-time-display));
  --media-duration-display-display: var(--duration-display, var(--bottom-duration-display));
  --media-title-display-display: var(--title-display, var(--bottom-title-display));
}

:host([no-tooltips]) {
  --media-tooltip-display: none;
}
`,sr=new WeakMap,py=class nv{constructor(t,i){this.element=t,this.type=i,this.element.addEventListener(this.type,this);let a=sr.get(this.element);a&&a.set(this.type,this)}set(t){if(typeof t=="function")this.handleEvent=t.bind(this.element);else if(typeof t=="object"&&typeof t.handleEvent=="function")this.handleEvent=t.handleEvent.bind(t);else{this.element.removeEventListener(this.type,this);let i=sr.get(this.element);i&&i.delete(this.type)}}static for(t){sr.has(t.element)||sr.set(t.element,new Map);let i=t.attributeName.slice(2),a=sr.get(t.element);return a&&a.has(i)?a.get(i):new nv(t.element,i)}};function vy(e,t){return e instanceof pt&&e.attributeName.startsWith("on")?(py.for(e).set(t),e.element.removeAttributeNS(e.attributeNamespace,e.attributeName),!0):!1}function fy(e,t){return t instanceof sv&&e instanceof Va?(t.renderInto(e),!0):!1}function Ey(e,t){return t instanceof DocumentFragment&&e instanceof Va?(t.childNodes.length&&e.replace(...t.childNodes),!0):!1}function _y(e,t){if(e instanceof pt){let i=e.attributeNamespace,a=e.element.getAttributeNS(i,e.attributeName);return String(t)!==a&&(e.value=String(t)),!0}return e.value=String(t),!0}function by(e,t){if(e instanceof pt&&t instanceof Element){let i=e.element;return i[e.attributeName]!==t&&(e.element.removeAttributeNS(e.attributeNamespace,e.attributeName),i[e.attributeName]=t),!0}return!1}function gy(e,t){if(typeof t=="boolean"&&e instanceof pt){let i=e.attributeNamespace,a=e.element.hasAttributeNS(i,e.attributeName);return t!==a&&(e.booleanValue=t),!0}return!1}function yy(e,t){return t===!1&&e instanceof Va?(e.replace(""),!0):!1}function Ty(e,t){by(e,t)||gy(e,t)||vy(e,t)||yy(e,t)||fy(e,t)||Ey(e,t)||_y(e,t)}var Fo=new Map,Zc=new WeakMap,zc=new WeakMap,sv=class{constructor(t,i,a){this.strings=t,this.values=i,this.processor=a,this.stringsKey=this.strings.join("")}get template(){if(Fo.has(this.stringsKey))return Fo.get(this.stringsKey);{let t=gu.createElement("template"),i=this.strings.length-1;return t.innerHTML=this.strings.reduce((a,r,n)=>a+r+(n<i?`{{ ${n} }}`:""),""),Fo.set(this.stringsKey,t),t}}renderInto(t){var i;let a=this.template;if(Zc.get(t)!==a){Zc.set(t,a);let n=new fo(a,this.values,this.processor);zc.set(t,n),t instanceof Va?t.replace(...n.children):t.appendChild(n);return}let r=zc.get(t);(i=r?.update)==null||i.call(r,this.values)}},Ay={processCallback(e,t,i){var a;if(i){for(let[r,n]of t)if(r in i){let s=(a=i[r])!=null?a:"";Ty(n,s)}}}};function ks(e,...t){return new sv(e,t,Ay)}function ky(e,t){e.renderInto(t)}var Sy=e=>{let{tokens:t}=e;return t.drm?":host(:not([cast-receiver])) { --_cast-button-drm-display: none; }":""},wy=e=>ks`
  <style>
    ${Sy(e)}
    ${my}
  </style>
  ${Dy(e)}
`,Iy=e=>{let t=e.hotKeys?`${e.hotKeys}`:"";return yu(e.streamType)==="live"&&(t+=" noarrowleft noarrowright"),t},Ry={TOP:"top",CENTER:"center",BOTTOM:"bottom",LAYER:"layer",MEDIA_LAYER:"media-layer",POSTER_LAYER:"poster-layer",VERTICAL_LAYER:"vertical-layer",CENTERED_LAYER:"centered-layer",GESTURE_LAYER:"gesture-layer",CONTROLLER_LAYER:"controller",BUTTON:"button",RANGE:"range",DISPLAY:"display",CONTROL_BAR:"control-bar",MENU_BUTTON:"menu-button",MENU:"menu",OPTION:"option",POSTER:"poster",LIVE:"live",PLAY:"play",PRE_PLAY:"pre-play",SEEK_BACKWARD:"seek-backward",SEEK_FORWARD:"seek-forward",MUTE:"mute",CAPTIONS:"captions",AIRPLAY:"airplay",PIP:"pip",FULLSCREEN:"fullscreen",CAST:"cast",PLAYBACK_RATE:"playback-rate",VOLUME:"volume",TIME:"time",TITLE:"title",AUDIO_TRACK:"audio-track",RENDITION:"rendition"},Cy=Object.values(Ry).join(", "),Dy=e=>{var t,i,a,r,n,s,o,l,d,m,p,h,c,v,g,_,y,T,f,S,D,O,H,Y,Q,W,P,De,He,Be,ce,xe,ft,Oe,rt;return ks`
  <media-theme
    template="${e.themeTemplate||!1}"
    defaultstreamtype="${(t=e.defaultStreamType)!=null?t:!1}"
    hotkeys="${Iy(e)||!1}"
    nohotkeys="${e.noHotKeys||!e.hasSrc||!1}"
    noautoseektolive="${!!((i=e.streamType)!=null&&i.includes(G.LIVE))&&e.targetLiveWindow!==0}"
    novolumepref="${e.novolumepref||!1}"
    disabled="${!e.hasSrc||e.isDialogOpen}"
    audio="${(a=e.audio)!=null?a:!1}"
    style="${(r=ey({"--media-primary-color":e.primaryColor,"--media-secondary-color":e.secondaryColor,"--media-accent-color":e.accentColor}))!=null?r:!1}"
    defaultsubtitles="${!e.defaultHiddenCaptions}"
    forwardseekoffset="${(n=e.forwardSeekOffset)!=null?n:!1}"
    backwardseekoffset="${(s=e.backwardSeekOffset)!=null?s:!1}"
    playbackrates="${(o=e.playbackRates)!=null?o:!1}"
    defaultshowremainingtime="${(l=e.defaultShowRemainingTime)!=null?l:!1}"
    defaultduration="${(d=e.defaultDuration)!=null?d:!1}"
    hideduration="${(m=e.hideDuration)!=null?m:!1}"
    title="${(p=e.title)!=null?p:!1}"
    videotitle="${(h=e.videoTitle)!=null?h:!1}"
    proudlydisplaymuxbadge="${(c=e.proudlyDisplayMuxBadge)!=null?c:!1}"
    exportparts="${Cy}"
    onclose="${e.onCloseErrorDialog}"
    onfocusin="${e.onFocusInErrorDialog}"
  >
    <mux-video
      slot="media"
      target-live-window="${(v=e.targetLiveWindow)!=null?v:!1}"
      stream-type="${(g=yu(e.streamType))!=null?g:!1}"
      crossorigin="${(_=e.crossOrigin)!=null?_:""}"
      playsinline
      autoplay="${(y=e.autoplay)!=null?y:!1}"
      muted="${(T=e.muted)!=null?T:!1}"
      loop="${(f=e.loop)!=null?f:!1}"
      preload="${(S=e.preload)!=null?S:!1}"
      debug="${(D=e.debug)!=null?D:!1}"
      prefer-cmcd="${(O=e.preferCmcd)!=null?O:!1}"
      disable-tracking="${(H=e.disableTracking)!=null?H:!1}"
      disable-cookies="${(Y=e.disableCookies)!=null?Y:!1}"
      prefer-playback="${(Q=e.preferPlayback)!=null?Q:!1}"
      start-time="${e.startTime!=null?e.startTime:!1}"
      beacon-collection-domain="${(W=e.beaconCollectionDomain)!=null?W:!1}"
      player-init-time="${(P=e.playerInitTime)!=null?P:!1}"
      player-software-name="${(De=e.playerSoftwareName)!=null?De:!1}"
      player-software-version="${(He=e.playerSoftwareVersion)!=null?He:!1}"
      env-key="${(Be=e.envKey)!=null?Be:!1}"
      custom-domain="${(ce=e.customDomain)!=null?ce:!1}"
      src="${e.src?e.src:e.playbackId?Xo(e):!1}"
      cast-src="${e.src?e.src:e.playbackId?Xo(e):!1}"
      cast-receiver="${(xe=e.castReceiver)!=null?xe:!1}"
      drm-token="${(Oe=(ft=e.tokens)==null?void 0:ft.drm)!=null?Oe:!1}"
      exportparts="video"
    >
      ${e.storyboard?ks`<track label="thumbnails" default kind="metadata" src="${e.storyboard}" />`:ks``}
      <slot></slot>
    </mux-video>
    <slot name="poster" slot="poster">
      <media-poster-image
        part="poster"
        exportparts="poster, img"
        src="${e.poster?e.poster:!1}"
        placeholdersrc="${(rt=e.placeholder)!=null?rt:!1}"
      ></media-poster-image>
    </slot>
  </media-theme>
`},ov=e=>e.charAt(0).toUpperCase()+e.slice(1),Ly=(e,t=!1)=>{var i,a;if(e.muxCode){let r=ov((i=e.errorCategory)!=null?i:"video"),n=to((a=e.errorCategory)!=null?a:te.VIDEO);if(e.muxCode===x.NETWORK_OFFLINE)return L("Your device appears to be offline",t);if(e.muxCode===x.NETWORK_TOKEN_EXPIRED)return L("{category} URL has expired",t).format({category:r});if([x.NETWORK_TOKEN_SUB_MISMATCH,x.NETWORK_TOKEN_AUD_MISMATCH,x.NETWORK_TOKEN_AUD_MISSING,x.NETWORK_TOKEN_MALFORMED].includes(e.muxCode))return L("{category} URL is formatted incorrectly",t).format({category:r});if(e.muxCode===x.NETWORK_TOKEN_MISSING)return L("Invalid {categoryName} URL",t).format({categoryName:n});if(e.muxCode===x.NETWORK_NOT_FOUND)return L("{category} does not exist",t).format({category:r});if(e.muxCode===x.NETWORK_NOT_READY){let s=e.streamType==="live"?"Live stream":"Video";return L("{mediaType} is not currently available",t).format({mediaType:s})}}if(e.code){if(e.code===w.MEDIA_ERR_NETWORK)return L("Network Error",t);if(e.code===w.MEDIA_ERR_DECODE)return L("Media Error",t);if(e.code===w.MEDIA_ERR_SRC_NOT_SUPPORTED)return L("Source Not Supported",t)}return L("Error",t)},My=(e,t=!1)=>{var i,a;if(e.muxCode){let r=ov((i=e.errorCategory)!=null?i:"video"),n=to((a=e.errorCategory)!=null?a:te.VIDEO);return e.muxCode===x.NETWORK_OFFLINE?L("Check your internet connection and try reloading this video.",t):e.muxCode===x.NETWORK_TOKEN_EXPIRED?L("The video’s secured {tokenNamePrefix}-token has expired.",t).format({tokenNamePrefix:n}):e.muxCode===x.NETWORK_TOKEN_SUB_MISMATCH?L("The video’s playback ID does not match the one encoded in the {tokenNamePrefix}-token.",t).format({tokenNamePrefix:n}):e.muxCode===x.NETWORK_TOKEN_MALFORMED?L("{category} URL is formatted incorrectly",t).format({category:r}):[x.NETWORK_TOKEN_AUD_MISMATCH,x.NETWORK_TOKEN_AUD_MISSING].includes(e.muxCode)?L("The {tokenNamePrefix}-token is formatted with incorrect information.",t).format({tokenNamePrefix:n}):[x.NETWORK_TOKEN_MISSING,x.NETWORK_INVALID_URL].includes(e.muxCode)?L("The video URL or {tokenNamePrefix}-token are formatted with incorrect or incomplete information.",t).format({tokenNamePrefix:n}):e.muxCode===x.NETWORK_NOT_FOUND?"":e.message}return e.code&&(e.code===w.MEDIA_ERR_NETWORK||e.code===w.MEDIA_ERR_DECODE||(e.code,w.MEDIA_ERR_SRC_NOT_SUPPORTED)),e.message},xy=(e,t=!1)=>{let i=Ly(e,t).toString(),a=My(e,t).toString();return{title:i,message:a}},Oy=e=>{if(e.muxCode){if(e.muxCode===x.NETWORK_TOKEN_EXPIRED)return"403-expired-token.md";if(e.muxCode===x.NETWORK_TOKEN_MALFORMED)return"403-malformatted-token.md";if([x.NETWORK_TOKEN_AUD_MISMATCH,x.NETWORK_TOKEN_AUD_MISSING].includes(e.muxCode))return"403-incorrect-aud-value.md";if(e.muxCode===x.NETWORK_TOKEN_SUB_MISMATCH)return"403-playback-id-mismatch.md";if(e.muxCode===x.NETWORK_TOKEN_MISSING)return"missing-signed-tokens.md";if(e.muxCode===x.NETWORK_NOT_FOUND)return"404-not-found.md";if(e.muxCode===x.NETWORK_NOT_READY)return"412-not-playable.md"}if(e.code){if(e.code===w.MEDIA_ERR_NETWORK)return"";if(e.code===w.MEDIA_ERR_DECODE)return"media-decode-error.md";if(e.code===w.MEDIA_ERR_SRC_NOT_SUPPORTED)return"media-src-not-supported.md"}return""},Xc=(e,t)=>{let i=Oy(e);return{message:e.message,context:e.context,file:i}},Ny=`<template id="media-theme-gerwig">
  <style>
    @keyframes pre-play-hide {
      0% {
        transform: scale(1);
        opacity: 1;
      }

      30% {
        transform: scale(0.7);
      }

      100% {
        transform: scale(1.5);
        opacity: 0;
      }
    }

    :host {
      --_primary-color: var(--media-primary-color, #fff);
      --_secondary-color: var(--media-secondary-color, transparent);
      --_accent-color: var(--media-accent-color, #fa50b5);
      --_text-color: var(--media-text-color, #000);

      --media-icon-color: var(--_primary-color);
      --media-control-background: var(--_secondary-color);
      --media-control-hover-background: var(--_accent-color);
      --media-time-buffered-color: rgba(255, 255, 255, 0.4);
      --media-preview-time-text-shadow: none;
      --media-control-height: 14px;
      --media-control-padding: 6px;
      --media-tooltip-container-margin: 6px;
      --media-tooltip-distance: 18px;

      color: var(--_primary-color);
      display: inline-block;
      width: 100%;
      height: 100%;
    }

    :host([audio]) {
      --_secondary-color: var(--media-secondary-color, black);
      --media-preview-time-text-shadow: none;
    }

    :host([audio]) ::slotted([slot='media']) {
      height: 0px;
    }

    :host([audio]) media-loading-indicator {
      display: none;
    }

    :host([audio]) media-controller {
      background: transparent;
    }

    :host([audio]) media-controller::part(vertical-layer) {
      background: transparent;
    }

    :host([audio]) media-control-bar {
      width: 100%;
      background-color: var(--media-control-background);
    }

    /*
     * 0.433s is the transition duration for VTT Regions.
     * Borrowed here, so the captions don't move too fast.
     */
    media-controller {
      --media-webkit-text-track-transform: translateY(0) scale(0.98);
      --media-webkit-text-track-transition: transform 0.433s ease-out 0.3s;
    }
    media-controller:is([mediapaused], :not([userinactive])) {
      --media-webkit-text-track-transform: translateY(-50px) scale(0.98);
      --media-webkit-text-track-transition: transform 0.15s ease;
    }

    /*
     * CSS specific to iOS devices.
     * See: https://stackoverflow.com/questions/30102792/css-media-query-to-target-only-ios-devices/60220757#60220757
     */
    @supports (-webkit-touch-callout: none) {
      /* Disable subtitle adjusting for iOS Safari */
      media-controller[mediaisfullscreen] {
        --media-webkit-text-track-transform: unset;
        --media-webkit-text-track-transition: unset;
      }
    }

    media-time-range {
      --media-box-padding-left: 6px;
      --media-box-padding-right: 6px;
      --media-range-bar-color: var(--_accent-color);
      --media-time-range-buffered-color: var(--_primary-color);
      --media-range-track-color: transparent;
      --media-range-track-background: rgba(255, 255, 255, 0.4);
      --media-range-thumb-background: radial-gradient(
        circle,
        #000 0%,
        #000 25%,
        var(--_accent-color) 25%,
        var(--_accent-color)
      );
      --media-range-thumb-width: 12px;
      --media-range-thumb-height: 12px;
      --media-range-thumb-transform: scale(0);
      --media-range-thumb-transition: transform 0.3s;
      --media-range-thumb-opacity: 1;
      --media-preview-background: var(--_primary-color);
      --media-box-arrow-background: var(--_primary-color);
      --media-preview-thumbnail-border: 5px solid var(--_primary-color);
      --media-preview-border-radius: 5px;
      --media-text-color: var(--_text-color);
      --media-control-hover-background: transparent;
      --media-preview-chapter-text-shadow: none;
      color: var(--_accent-color);
      padding: 0 6px;
    }

    :host([audio]) media-time-range {
      --media-preview-time-padding: 1.5px 6px;
      --media-preview-box-margin: 0 0 -5px;
    }

    media-time-range:hover {
      --media-range-thumb-transform: scale(1);
    }

    media-preview-thumbnail {
      border-bottom-width: 0;
    }

    [part~='menu'] {
      border-radius: 2px;
      border: 1px solid rgba(0, 0, 0, 0.1);
      bottom: 50px;
      padding: 2.5px 10px;
    }

    [part~='menu']::part(indicator) {
      fill: var(--_accent-color);
    }

    [part~='menu']::part(menu-item) {
      box-sizing: border-box;
      display: flex;
      align-items: center;
      padding: 6px 10px;
      min-height: 34px;
    }

    [part~='menu']::part(checked) {
      font-weight: 700;
    }

    media-captions-menu,
    media-rendition-menu,
    media-audio-track-menu,
    media-playback-rate-menu {
      position: absolute; /* ensure they don't take up space in DOM on load */
      --media-menu-background: var(--_primary-color);
      --media-menu-item-checked-background: transparent;
      --media-text-color: var(--_text-color);
      --media-menu-item-hover-background: transparent;
      --media-menu-item-hover-outline: var(--_accent-color) solid 1px;
    }

    media-rendition-menu {
      min-width: 140px;
    }

    /* The icon is a circle so make it 16px high instead of 14px for more balance. */
    media-audio-track-menu-button {
      --media-control-padding: 5px;
      --media-control-height: 16px;
    }

    media-playback-rate-menu-button {
      --media-control-padding: 6px 3px;
      min-width: 4.4ch;
    }

    media-playback-rate-menu {
      --media-menu-flex-direction: row;
      --media-menu-item-checked-background: var(--_accent-color);
      --media-menu-item-checked-indicator-display: none;
      margin-right: 6px;
      padding: 0;
      --media-menu-gap: 0.25em;
    }

    media-playback-rate-menu[part~='menu']::part(menu-item) {
      padding: 6px 6px 6px 8px;
    }

    media-playback-rate-menu[part~='menu']::part(checked) {
      color: #fff;
    }

    :host(:not([audio])) media-time-range {
      /* Adding px is required here for calc() */
      --media-range-padding: 0px;
      background: transparent;
      z-index: 10;
      height: 10px;
      bottom: -3px;
      width: 100%;
    }

    media-control-bar :is([role='button'], [role='switch'], button) {
      line-height: 0;
    }

    media-control-bar :is([part*='button'], [part*='range'], [part*='display']) {
      border-radius: 3px;
    }

    .spacer {
      flex-grow: 1;
      background-color: var(--media-control-background, rgba(20, 20, 30, 0.7));
    }

    media-control-bar[slot~='top-chrome'] {
      min-height: 42px;
      pointer-events: none;
    }

    media-control-bar {
      --gradient-steps:
        hsl(0 0% 0% / 0) 0%, hsl(0 0% 0% / 0.013) 8.1%, hsl(0 0% 0% / 0.049) 15.5%, hsl(0 0% 0% / 0.104) 22.5%,
        hsl(0 0% 0% / 0.175) 29%, hsl(0 0% 0% / 0.259) 35.3%, hsl(0 0% 0% / 0.352) 41.2%, hsl(0 0% 0% / 0.45) 47.1%,
        hsl(0 0% 0% / 0.55) 52.9%, hsl(0 0% 0% / 0.648) 58.8%, hsl(0 0% 0% / 0.741) 64.7%, hsl(0 0% 0% / 0.825) 71%,
        hsl(0 0% 0% / 0.896) 77.5%, hsl(0 0% 0% / 0.951) 84.5%, hsl(0 0% 0% / 0.987) 91.9%, hsl(0 0% 0%) 100%;
    }

    :host([title]:not([audio])) media-control-bar[slot='top-chrome']::before {
      content: '';
      position: absolute;
      width: 100%;
      padding-bottom: min(100px, 25%);
      background: linear-gradient(to top, var(--gradient-steps));
      opacity: 0.8;
      pointer-events: none;
    }

    :host(:not([audio])) media-control-bar[part~='bottom']::before {
      content: '';
      position: absolute;
      width: 100%;
      bottom: 0;
      left: 0;
      padding-bottom: min(100px, 25%);
      background: linear-gradient(to bottom, var(--gradient-steps));
      opacity: 0.8;
      z-index: 1;
      pointer-events: none;
    }

    media-control-bar[part~='bottom'] > * {
      z-index: 20;
    }

    media-control-bar[part~='bottom'] {
      padding: 6px 6px;
    }

    media-control-bar[slot~='top-chrome'] > * {
      --media-control-background: transparent;
      --media-control-hover-background: transparent;
      position: relative;
    }

    media-controller::part(vertical-layer) {
      transition: background-color 1s;
    }

    media-controller:is([mediapaused], :not([userinactive]))::part(vertical-layer) {
      background-color: var(--controls-backdrop-color, var(--controls, transparent));
      transition: background-color 0.25s;
    }

    .center-controls {
      --media-button-icon-width: 100%;
      --media-button-icon-height: auto;
      --media-tooltip-display: none;
      pointer-events: none;
      width: 100%;
      display: flex;
      flex-flow: row;
      align-items: center;
      justify-content: center;
      filter: drop-shadow(0 0 2px rgb(0 0 0 / 0.25)) drop-shadow(0 0 6px rgb(0 0 0 / 0.25));
      paint-order: stroke;
      stroke: rgba(102, 102, 102, 1);
      stroke-width: 0.3px;
      text-shadow:
        0 0 2px rgb(0 0 0 / 0.25),
        0 0 6px rgb(0 0 0 / 0.25);
    }

    .center-controls media-play-button {
      --media-control-background: transparent;
      --media-control-hover-background: transparent;
      --media-control-padding: 0;
      width: 40px;
    }

    [breakpointsm] .center-controls media-play-button {
      width: 90px;
      height: 90px;
      border-radius: 50%;
      transition: background 0.4s;
      padding: 24px;
      --media-control-background: #000;
      --media-control-hover-background: var(--_accent-color);
    }

    .center-controls media-seek-backward-button,
    .center-controls media-seek-forward-button {
      --media-control-background: transparent;
      --media-control-hover-background: transparent;
      padding: 0;
      margin: 0 20px;
      width: max(33px, min(8%, 40px));
    }

    [breakpointsm]:not([audio]) .center-controls.pre-playback {
      display: grid;
      align-items: initial;
      justify-content: initial;
      height: 100%;
      overflow: hidden;
    }

    [breakpointsm]:not([audio]) .center-controls.pre-playback media-play-button {
      place-self: var(--_pre-playback-place, center);
      grid-area: 1 / 1;
      margin: 16px;
    }

    /* Show and hide controls or pre-playback state */

    [breakpointsm]:is([mediahasplayed], :not([mediapaused])):not([audio])
      .center-controls.pre-playback
      media-play-button {
      /* Using \`forwards\` would lead to a laggy UI after the animation got in the end state */
      animation: 0.3s linear pre-play-hide;
      opacity: 0;
      pointer-events: none;
    }

    .autoplay-unmute {
      --media-control-hover-background: transparent;
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      filter: drop-shadow(0 0 2px rgb(0 0 0 / 0.25)) drop-shadow(0 0 6px rgb(0 0 0 / 0.25));
    }

    .autoplay-unmute-btn {
      --media-control-height: 16px;
      border-radius: 8px;
      background: #000;
      color: var(--_primary-color);
      display: flex;
      align-items: center;
      padding: 8px 16px;
      font-size: 18px;
      font-weight: 500;
      cursor: pointer;
    }

    .autoplay-unmute-btn:hover {
      background: var(--_accent-color);
    }

    [breakpointsm] .autoplay-unmute-btn {
      --media-control-height: 30px;
      padding: 14px 24px;
      font-size: 26px;
    }

    .autoplay-unmute-btn svg {
      margin: 0 6px 0 0;
    }

    [breakpointsm] .autoplay-unmute-btn svg {
      margin: 0 10px 0 0;
    }

    media-controller:not([audio]):not([mediahasplayed]) *:is(media-control-bar, media-time-range) {
      display: none;
    }

    media-error-dialog:not([mediaerrorcode]) {
      opacity: 0;
    }

    media-loading-indicator {
      --media-loading-icon-width: 100%;
      --media-button-icon-height: auto;
      display: var(--media-control-display, var(--media-loading-indicator-display, flex));
      pointer-events: none;
      position: absolute;
      width: min(15%, 150px);
      flex-flow: row;
      align-items: center;
      justify-content: center;
    }

    /* Intentionally don't target the div for transition but the children
     of the div. Prevents messing with media-chrome's autohide feature. */
    media-loading-indicator + div * {
      transition: opacity 0.15s;
      opacity: 1;
    }

    media-loading-indicator[medialoading]:not([mediapaused]) ~ div > * {
      opacity: 0;
      transition-delay: 400ms;
    }

    media-volume-range {
      width: min(100%, 100px);
      --media-range-padding-left: 10px;
      --media-range-padding-right: 10px;
      --media-range-thumb-width: 12px;
      --media-range-thumb-height: 12px;
      --media-range-thumb-background: radial-gradient(
        circle,
        #000 0%,
        #000 25%,
        var(--_primary-color) 25%,
        var(--_primary-color)
      );
      --media-control-hover-background: none;
    }

    media-time-display {
      white-space: nowrap;
    }

    /* Generic style for explicitly disabled controls */
    media-control-bar[part~='bottom'] [disabled],
    media-control-bar[part~='bottom'] [aria-disabled='true'] {
      opacity: 60%;
      cursor: not-allowed;
    }

    media-text-display {
      --media-font-size: 16px;
      --media-control-padding: 14px;
      font-weight: 500;
    }

    media-play-button.animated *:is(g, path) {
      transition: all 0.3s;
    }

    media-play-button.animated[mediapaused] .pause-icon-pt1 {
      opacity: 0;
    }

    media-play-button.animated[mediapaused] .pause-icon-pt2 {
      transform-origin: center center;
      transform: scaleY(0);
    }

    media-play-button.animated[mediapaused] .play-icon {
      clip-path: inset(0 0 0 0);
    }

    media-play-button.animated:not([mediapaused]) .play-icon {
      clip-path: inset(0 0 0 100%);
    }

    media-seek-forward-button,
    media-seek-backward-button {
      --media-font-weight: 400;
    }

    .mute-icon {
      display: inline-block;
    }

    .mute-icon :is(path, g) {
      transition: opacity 0.5s;
    }

    .muted {
      opacity: 0;
    }

    media-mute-button[mediavolumelevel='low'] :is(.volume-medium, .volume-high),
    media-mute-button[mediavolumelevel='medium'] :is(.volume-high) {
      opacity: 0;
    }

    media-mute-button[mediavolumelevel='off'] .unmuted {
      opacity: 0;
    }

    media-mute-button[mediavolumelevel='off'] .muted {
      opacity: 1;
    }

    /**
     * Our defaults for these buttons are to hide them at small sizes
     * users can override this with CSS
     */
    media-controller:not([breakpointsm]):not([audio]) {
      --bottom-play-button: none;
      --bottom-seek-backward-button: none;
      --bottom-seek-forward-button: none;
      --bottom-time-display: none;
      --bottom-playback-rate-menu-button: none;
      --bottom-pip-button: none;
    }

    [part='mux-badge'] {
      position: absolute;
      bottom: 10px;
      right: 10px;
      z-index: 2;
      opacity: 0.6;
      transition:
        opacity 0.2s ease-in-out,
        bottom 0.2s ease-in-out;
    }

    [part='mux-badge']:hover {
      opacity: 1;
    }

    [part='mux-badge'] a {
      font-size: 14px;
      font-family: var(--_font-family);
      color: var(--_primary-color);
      text-decoration: none;
      display: flex;
      align-items: center;
      gap: 5px;
    }

    [part='mux-badge'] .mux-badge-text {
      transition: opacity 0.5s ease-in-out;
      opacity: 0;
    }

    [part='mux-badge'] .mux-badge-logo {
      width: 40px;
      height: auto;
      display: inline-block;
    }

    [part='mux-badge'] .mux-badge-logo svg {
      width: 100%;
      height: 100%;
      fill: white;
    }

    media-controller:not([userinactive]):not([mediahasplayed]) [part='mux-badge'],
    media-controller:not([userinactive]) [part='mux-badge'],
    media-controller[mediahasplayed][mediapaused] [part='mux-badge'] {
      transition: bottom 0.1s ease-in-out;
    }

    media-controller[userinactive]:not([mediapaused]) [part='mux-badge'] {
      transition: bottom 0.2s ease-in-out 0.62s;
    }

    media-controller:not([userinactive]) [part='mux-badge'] .mux-badge-text,
    media-controller[mediahasplayed][mediapaused] [part='mux-badge'] .mux-badge-text {
      opacity: 1;
    }

    media-controller[userinactive]:not([mediapaused]) [part='mux-badge'] .mux-badge-text {
      opacity: 0;
    }

    media-controller[userinactive]:not([mediapaused]) [part='mux-badge'] {
      bottom: 10px;
    }

    media-controller:not([userinactive]):not([mediahasplayed]) [part='mux-badge'] {
      bottom: 10px;
    }

    media-controller:not([userinactive])[mediahasplayed] [part='mux-badge'],
    media-controller[mediahasplayed][mediapaused] [part='mux-badge'] {
      bottom: calc(28px + var(--media-control-height, 0px) + var(--media-control-padding, 0px) * 2);
    }
  </style>

  <template partial="TitleDisplay">
    <template if="videotitle">
      <template if="videotitle != true">
        <media-text-display part="top title display" class="title-display">{{videotitle}}</media-text-display>
      </template>
    </template>
    <template if="!videotitle">
      <template if="title">
        <media-text-display part="top title display" class="title-display">{{title}}</media-text-display>
      </template>
    </template>
  </template>

  <template partial="PlayButton">
    <media-play-button
      part="{{section ?? 'bottom'}} play button"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
      class="animated"
    >
      <svg aria-hidden="true" viewBox="0 0 18 14" slot="icon">
        <g class="play-icon">
          <path
            d="M15.5987 6.2911L3.45577 0.110898C2.83667 -0.204202 2.06287 0.189698 2.06287 0.819798V13.1802C2.06287 13.8103 2.83667 14.2042 3.45577 13.8891L15.5987 7.7089C16.2178 7.3938 16.2178 6.6061 15.5987 6.2911Z"
          />
        </g>
        <g class="pause-icon">
          <path
            class="pause-icon-pt1"
            d="M5.90709 0H2.96889C2.46857 0 2.06299 0.405585 2.06299 0.9059V13.0941C2.06299 13.5944 2.46857 14 2.96889 14H5.90709C6.4074 14 6.81299 13.5944 6.81299 13.0941V0.9059C6.81299 0.405585 6.4074 0 5.90709 0Z"
          />
          <path
            class="pause-icon-pt2"
            d="M15.1571 0H12.2189C11.7186 0 11.313 0.405585 11.313 0.9059V13.0941C11.313 13.5944 11.7186 14 12.2189 14H15.1571C15.6574 14 16.063 13.5944 16.063 13.0941V0.9059C16.063 0.405585 15.6574 0 15.1571 0Z"
          />
        </g>
      </svg>
    </media-play-button>
  </template>

  <template partial="PrePlayButton">
    <media-play-button
      part="{{section ?? 'center'}} play button pre-play"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
    >
      <svg aria-hidden="true" viewBox="0 0 18 14" slot="icon" style="transform: translate(3px, 0)">
        <path
          d="M15.5987 6.2911L3.45577 0.110898C2.83667 -0.204202 2.06287 0.189698 2.06287 0.819798V13.1802C2.06287 13.8103 2.83667 14.2042 3.45577 13.8891L15.5987 7.7089C16.2178 7.3938 16.2178 6.6061 15.5987 6.2911Z"
        />
      </svg>
    </media-play-button>
  </template>

  <template partial="SeekBackwardButton">
    <media-seek-backward-button
      seekoffset="{{backwardseekoffset}}"
      part="{{section ?? 'bottom'}} seek-backward button"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
    >
      <svg viewBox="0 0 22 14" aria-hidden="true" slot="icon">
        <path
          d="M3.65 2.07888L0.0864 6.7279C-0.0288 6.87812 -0.0288 7.12188 0.0864 7.2721L3.65 11.9211C3.7792 12.0896 4 11.9703 4 11.7321V2.26787C4 2.02968 3.7792 1.9104 3.65 2.07888Z"
        />
        <text transform="translate(6 12)" style="font-size: 14px; font-family: 'ArialMT', 'Arial'">
          {{backwardseekoffset}}
        </text>
      </svg>
    </media-seek-backward-button>
  </template>

  <template partial="SeekForwardButton">
    <media-seek-forward-button
      seekoffset="{{forwardseekoffset}}"
      part="{{section ?? 'bottom'}} seek-forward button"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
    >
      <svg viewBox="0 0 22 14" aria-hidden="true" slot="icon">
        <g>
          <text transform="translate(-1 12)" style="font-size: 14px; font-family: 'ArialMT', 'Arial'">
            {{forwardseekoffset}}
          </text>
          <path
            d="M18.35 11.9211L21.9136 7.2721C22.0288 7.12188 22.0288 6.87812 21.9136 6.7279L18.35 2.07888C18.2208 1.91041 18 2.02968 18 2.26787V11.7321C18 11.9703 18.2208 12.0896 18.35 11.9211Z"
          />
        </g>
      </svg>
    </media-seek-forward-button>
  </template>

  <template partial="MuteButton">
    <media-mute-button part="bottom mute button" disabled="{{disabled}}" aria-disabled="{{disabled}}">
      <svg viewBox="0 0 18 14" slot="icon" class="mute-icon" aria-hidden="true">
        <g class="unmuted">
          <path
            d="M6.76786 1.21233L3.98606 3.98924H1.19937C0.593146 3.98924 0.101743 4.51375 0.101743 5.1607V6.96412L0 6.99998L0.101743 7.03583V8.83926C0.101743 9.48633 0.593146 10.0108 1.19937 10.0108H3.98606L6.76773 12.7877C7.23561 13.2547 8 12.9007 8 12.2171V1.78301C8 1.09925 7.23574 0.745258 6.76786 1.21233Z"
          />
          <path
            class="volume-low"
            d="M10 3.54781C10.7452 4.55141 11.1393 5.74511 11.1393 6.99991C11.1393 8.25471 10.7453 9.44791 10 10.4515L10.7988 11.0496C11.6734 9.87201 12.1356 8.47161 12.1356 6.99991C12.1356 5.52821 11.6735 4.12731 10.7988 2.94971L10 3.54781Z"
          />
          <path
            class="volume-medium"
            d="M12.3778 2.40086C13.2709 3.76756 13.7428 5.35806 13.7428 7.00026C13.7428 8.64246 13.2709 10.233 12.3778 11.5992L13.2106 12.1484C14.2107 10.6185 14.739 8.83796 14.739 7.00016C14.739 5.16236 14.2107 3.38236 13.2106 1.85156L12.3778 2.40086Z"
          />
          <path
            class="volume-high"
            d="M15.5981 0.75L14.7478 1.2719C15.7937 2.9919 16.3468 4.9723 16.3468 7C16.3468 9.0277 15.7937 11.0082 14.7478 12.7281L15.5981 13.25C16.7398 11.3722 17.343 9.211 17.343 7C17.343 4.789 16.7398 2.6268 15.5981 0.75Z"
          />
        </g>
        <g class="muted">
          <path
            fill-rule="evenodd"
            clip-rule="evenodd"
            d="M4.39976 4.98924H1.19937C1.19429 4.98924 1.17777 4.98961 1.15296 5.01609C1.1271 5.04369 1.10174 5.09245 1.10174 5.1607V8.83926C1.10174 8.90761 1.12714 8.95641 1.15299 8.984C1.17779 9.01047 1.1943 9.01084 1.19937 9.01084H4.39977L7 11.6066V2.39357L4.39976 4.98924ZM7.47434 1.92006C7.4743 1.9201 7.47439 1.92002 7.47434 1.92006V1.92006ZM6.76773 12.7877L3.98606 10.0108H1.19937C0.593146 10.0108 0.101743 9.48633 0.101743 8.83926V7.03583L0 6.99998L0.101743 6.96412V5.1607C0.101743 4.51375 0.593146 3.98924 1.19937 3.98924H3.98606L6.76786 1.21233C7.23574 0.745258 8 1.09925 8 1.78301V12.2171C8 12.9007 7.23561 13.2547 6.76773 12.7877Z"
          />
          <path
            fill-rule="evenodd"
            clip-rule="evenodd"
            d="M15.2677 9.30323C15.463 9.49849 15.7796 9.49849 15.9749 9.30323C16.1701 9.10796 16.1701 8.79138 15.9749 8.59612L14.2071 6.82841L15.9749 5.06066C16.1702 4.8654 16.1702 4.54882 15.9749 4.35355C15.7796 4.15829 15.4631 4.15829 15.2678 4.35355L13.5 6.1213L11.7322 4.35348C11.537 4.15822 11.2204 4.15822 11.0251 4.35348C10.8298 4.54874 10.8298 4.86532 11.0251 5.06058L12.7929 6.82841L11.0251 8.59619C10.8299 8.79146 10.8299 9.10804 11.0251 9.3033C11.2204 9.49856 11.537 9.49856 11.7323 9.3033L13.5 7.53552L15.2677 9.30323Z"
          />
        </g>
      </svg>
    </media-mute-button>
  </template>

  <template partial="PipButton">
    <media-pip-button part="bottom pip button" disabled="{{disabled}}" aria-disabled="{{disabled}}">
      <svg viewBox="0 0 18 14" aria-hidden="true" slot="icon">
        <path
          d="M15.9891 0H2.011C0.9004 0 0 0.9003 0 2.0109V11.989C0 13.0996 0.9004 14 2.011 14H15.9891C17.0997 14 18 13.0997 18 11.9891V2.0109C18 0.9003 17.0997 0 15.9891 0ZM17 11.9891C17 12.5465 16.5465 13 15.9891 13H2.011C1.4536 13 1.0001 12.5465 1.0001 11.9891V2.0109C1.0001 1.4535 1.4536 0.9999 2.011 0.9999H15.9891C16.5465 0.9999 17 1.4535 17 2.0109V11.9891Z"
        />
        <path
          d="M15.356 5.67822H8.19523C8.03253 5.67822 7.90063 5.81012 7.90063 5.97282V11.3836C7.90063 11.5463 8.03253 11.6782 8.19523 11.6782H15.356C15.5187 11.6782 15.6506 11.5463 15.6506 11.3836V5.97282C15.6506 5.81012 15.5187 5.67822 15.356 5.67822Z"
        />
      </svg>
    </media-pip-button>
  </template>

  <template partial="CaptionsMenu">
    <media-captions-menu-button part="bottom captions button">
      <svg aria-hidden="true" viewBox="0 0 18 14" slot="on">
        <path
          d="M15.989 0H2.011C0.9004 0 0 0.9003 0 2.0109V11.9891C0 13.0997 0.9004 14 2.011 14H15.989C17.0997 14 18 13.0997 18 11.9891V2.0109C18 0.9003 17.0997 0 15.989 0ZM4.2292 8.7639C4.5954 9.1902 5.0935 9.4031 5.7233 9.4031C6.1852 9.4031 6.5544 9.301 6.8302 9.0969C7.1061 8.8933 7.2863 8.614 7.3702 8.26H8.4322C8.3062 8.884 8.0093 9.3733 7.5411 9.7273C7.0733 10.0813 6.4703 10.2581 5.732 10.2581C5.108 10.2581 4.5699 10.1219 4.1168 9.8489C3.6637 9.5759 3.3141 9.1946 3.0685 8.7058C2.8224 8.2165 2.6994 7.6511 2.6994 7.009C2.6994 6.3611 2.8224 5.7927 3.0685 5.3034C3.3141 4.8146 3.6637 4.4323 4.1168 4.1559C4.5699 3.88 5.108 3.7418 5.732 3.7418C6.4703 3.7418 7.0733 3.922 7.5411 4.2818C8.0094 4.6422 8.3062 5.1461 8.4322 5.794H7.3702C7.2862 5.4283 7.106 5.1368 6.8302 4.921C6.5544 4.7052 6.1852 4.5968 5.7233 4.5968C5.0934 4.5968 4.5954 4.8116 4.2292 5.2404C3.8635 5.6696 3.6804 6.259 3.6804 7.009C3.6804 7.7531 3.8635 8.3381 4.2292 8.7639ZM11.0974 8.7639C11.4636 9.1902 11.9617 9.4031 12.5915 9.4031C13.0534 9.4031 13.4226 9.301 13.6984 9.0969C13.9743 8.8933 14.1545 8.614 14.2384 8.26H15.3004C15.1744 8.884 14.8775 9.3733 14.4093 9.7273C13.9415 10.0813 13.3385 10.2581 12.6002 10.2581C11.9762 10.2581 11.4381 10.1219 10.985 9.8489C10.5319 9.5759 10.1823 9.1946 9.9367 8.7058C9.6906 8.2165 9.5676 7.6511 9.5676 7.009C9.5676 6.3611 9.6906 5.7927 9.9367 5.3034C10.1823 4.8146 10.5319 4.4323 10.985 4.1559C11.4381 3.88 11.9762 3.7418 12.6002 3.7418C13.3385 3.7418 13.9415 3.922 14.4093 4.2818C14.8776 4.6422 15.1744 5.1461 15.3004 5.794H14.2384C14.1544 5.4283 13.9742 5.1368 13.6984 4.921C13.4226 4.7052 13.0534 4.5968 12.5915 4.5968C11.9616 4.5968 11.4636 4.8116 11.0974 5.2404C10.7317 5.6696 10.5486 6.259 10.5486 7.009C10.5486 7.7531 10.7317 8.3381 11.0974 8.7639Z"
        />
      </svg>
      <svg aria-hidden="true" viewBox="0 0 18 14" slot="off">
        <path
          d="M5.73219 10.258C5.10819 10.258 4.57009 10.1218 4.11699 9.8488C3.66389 9.5758 3.31429 9.1945 3.06869 8.7057C2.82259 8.2164 2.69958 7.651 2.69958 7.0089C2.69958 6.361 2.82259 5.7926 3.06869 5.3033C3.31429 4.8145 3.66389 4.4322 4.11699 4.1558C4.57009 3.8799 5.10819 3.7417 5.73219 3.7417C6.47049 3.7417 7.07348 3.9219 7.54128 4.2817C8.00958 4.6421 8.30638 5.146 8.43238 5.7939H7.37039C7.28639 5.4282 7.10618 5.1367 6.83039 4.9209C6.55459 4.7051 6.18538 4.5967 5.72348 4.5967C5.09358 4.5967 4.59559 4.8115 4.22939 5.2403C3.86369 5.6695 3.68058 6.2589 3.68058 7.0089C3.68058 7.753 3.86369 8.338 4.22939 8.7638C4.59559 9.1901 5.09368 9.403 5.72348 9.403C6.18538 9.403 6.55459 9.3009 6.83039 9.0968C7.10629 8.8932 7.28649 8.6139 7.37039 8.2599H8.43238C8.30638 8.8839 8.00948 9.3732 7.54128 9.7272C7.07348 10.0812 6.47049 10.258 5.73219 10.258Z"
        />
        <path
          d="M12.6003 10.258C11.9763 10.258 11.4382 10.1218 10.9851 9.8488C10.532 9.5758 10.1824 9.1945 9.93685 8.7057C9.69075 8.2164 9.56775 7.651 9.56775 7.0089C9.56775 6.361 9.69075 5.7926 9.93685 5.3033C10.1824 4.8145 10.532 4.4322 10.9851 4.1558C11.4382 3.8799 11.9763 3.7417 12.6003 3.7417C13.3386 3.7417 13.9416 3.9219 14.4094 4.2817C14.8777 4.6421 15.1745 5.146 15.3005 5.7939H14.2385C14.1545 5.4282 13.9743 5.1367 13.6985 4.9209C13.4227 4.7051 13.0535 4.5967 12.5916 4.5967C11.9617 4.5967 11.4637 4.8115 11.0975 5.2403C10.7318 5.6695 10.5487 6.2589 10.5487 7.0089C10.5487 7.753 10.7318 8.338 11.0975 8.7638C11.4637 9.1901 11.9618 9.403 12.5916 9.403C13.0535 9.403 13.4227 9.3009 13.6985 9.0968C13.9744 8.8932 14.1546 8.6139 14.2385 8.2599H15.3005C15.1745 8.8839 14.8776 9.3732 14.4094 9.7272C13.9416 10.0812 13.3386 10.258 12.6003 10.258Z"
        />
        <path
          d="M15.9891 1C16.5465 1 17 1.4535 17 2.011V11.9891C17 12.5465 16.5465 13 15.9891 13H2.0109C1.4535 13 1 12.5465 1 11.9891V2.0109C1 1.4535 1.4535 0.9999 2.0109 0.9999L15.9891 1ZM15.9891 0H2.0109C0.9003 0 0 0.9003 0 2.0109V11.9891C0 13.0997 0.9003 14 2.0109 14H15.9891C17.0997 14 18 13.0997 18 11.9891V2.0109C18 0.9003 17.0997 0 15.9891 0Z"
        />
      </svg>
    </media-captions-menu-button>
    <media-captions-menu
      hidden
      anchor="auto"
      part="bottom captions menu"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
      exportparts="menu-item"
    >
      <div slot="checked-indicator">
        <style>
          .indicator {
            position: relative;
            top: 1px;
            width: 0.9em;
            height: auto;
            fill: var(--_accent-color);
            margin-right: 5px;
          }

          [aria-checked='false'] .indicator {
            display: none;
          }
        </style>
        <svg viewBox="0 0 14 18" class="indicator">
          <path
            d="M12.252 3.48c-.115.033-.301.161-.425.291-.059.063-1.407 1.815-2.995 3.894s-2.897 3.79-2.908 3.802c-.013.014-.661-.616-1.672-1.624-.908-.905-1.702-1.681-1.765-1.723-.401-.27-.783-.211-1.176.183a1.285 1.285 0 0 0-.261.342.582.582 0 0 0-.082.35c0 .165.01.205.08.35.075.153.213.296 2.182 2.271 1.156 1.159 2.17 2.159 2.253 2.222.189.143.338.196.539.194.203-.003.412-.104.618-.299.205-.193 6.7-8.693 6.804-8.903a.716.716 0 0 0 .085-.345c.01-.179.005-.203-.062-.339-.124-.252-.45-.531-.746-.639a.784.784 0 0 0-.469-.027"
            fill-rule="evenodd"
          />
        </svg></div
    ></media-captions-menu>
  </template>

  <template partial="AirplayButton">
    <media-airplay-button part="bottom airplay button" disabled="{{disabled}}" aria-disabled="{{disabled}}">
      <svg viewBox="0 0 18 14" aria-hidden="true" slot="icon">
        <path
          d="M16.1383 0H1.8618C0.8335 0 0 0.8335 0 1.8617V10.1382C0 11.1664 0.8335 12 1.8618 12H3.076C3.1204 11.9433 3.1503 11.8785 3.2012 11.826L4.004 11H1.8618C1.3866 11 1 10.6134 1 10.1382V1.8617C1 1.3865 1.3866 0.9999 1.8618 0.9999H16.1383C16.6135 0.9999 17.0001 1.3865 17.0001 1.8617V10.1382C17.0001 10.6134 16.6135 11 16.1383 11H13.9961L14.7989 11.826C14.8499 11.8785 14.8798 11.9432 14.9241 12H16.1383C17.1665 12 18.0001 11.1664 18.0001 10.1382V1.8617C18 0.8335 17.1665 0 16.1383 0Z"
        />
        <path
          d="M9.55061 8.21903C9.39981 8.06383 9.20001 7.98633 9.00011 7.98633C8.80021 7.98633 8.60031 8.06383 8.44951 8.21903L4.09771 12.697C3.62471 13.1838 3.96961 13.9998 4.64831 13.9998H13.3518C14.0304 13.9998 14.3754 13.1838 13.9023 12.697L9.55061 8.21903Z"
        />
      </svg>
    </media-airplay-button>
  </template>

  <template partial="FullscreenButton">
    <media-fullscreen-button part="bottom fullscreen button" disabled="{{disabled}}" aria-disabled="{{disabled}}">
      <svg viewBox="0 0 18 14" aria-hidden="true" slot="enter">
        <path
          d="M1.00745 4.39539L1.01445 1.98789C1.01605 1.43049 1.47085 0.978289 2.02835 0.979989L6.39375 0.992589L6.39665 -0.007411L2.03125 -0.020011C0.920646 -0.023211 0.0176463 0.874489 0.0144463 1.98509L0.00744629 4.39539H1.00745Z"
        />
        <path
          d="M17.0144 2.03431L17.0076 4.39541H18.0076L18.0144 2.03721C18.0176 0.926712 17.1199 0.0237125 16.0093 0.0205125L11.6439 0.0078125L11.641 1.00781L16.0064 1.02041C16.5638 1.02201 17.016 1.47681 17.0144 2.03431Z"
        />
        <path
          d="M16.9925 9.60498L16.9855 12.0124C16.9839 12.5698 16.5291 13.022 15.9717 13.0204L11.6063 13.0078L11.6034 14.0078L15.9688 14.0204C17.0794 14.0236 17.9823 13.1259 17.9855 12.0153L17.9925 9.60498H16.9925Z"
        />
        <path
          d="M0.985626 11.9661L0.992426 9.60498H-0.0074737L-0.0142737 11.9632C-0.0174737 13.0738 0.880226 13.9767 1.99083 13.98L6.35623 13.9926L6.35913 12.9926L1.99373 12.98C1.43633 12.9784 0.983926 12.5236 0.985626 11.9661Z"
        />
      </svg>
      <svg viewBox="0 0 18 14" aria-hidden="true" slot="exit">
        <path
          d="M5.39655 -0.0200195L5.38955 2.38748C5.38795 2.94488 4.93315 3.39708 4.37565 3.39538L0.0103463 3.38278L0.00744629 4.38278L4.37285 4.39538C5.48345 4.39858 6.38635 3.50088 6.38965 2.39028L6.39665 -0.0200195H5.39655Z"
        />
        <path
          d="M12.6411 2.36891L12.6479 0.0078125H11.6479L11.6411 2.36601C11.6379 3.47651 12.5356 4.37951 13.6462 4.38271L18.0116 4.39531L18.0145 3.39531L13.6491 3.38271C13.0917 3.38111 12.6395 2.92641 12.6411 2.36891Z"
        />
        <path
          d="M12.6034 14.0204L12.6104 11.613C12.612 11.0556 13.0668 10.6034 13.6242 10.605L17.9896 10.6176L17.9925 9.61759L13.6271 9.60499C12.5165 9.60179 11.6136 10.4995 11.6104 11.6101L11.6034 14.0204H12.6034Z"
        />
        <path
          d="M5.359 11.6315L5.3522 13.9926H6.3522L6.359 11.6344C6.3622 10.5238 5.4645 9.62088 4.3539 9.61758L-0.0115043 9.60498L-0.0144043 10.605L4.351 10.6176C4.9084 10.6192 5.3607 11.074 5.359 11.6315Z"
        />
      </svg>
    </media-fullscreen-button>
  </template>

  <template partial="CastButton">
    <media-cast-button part="bottom cast button" disabled="{{disabled}}" aria-disabled="{{disabled}}">
      <svg viewBox="0 0 18 14" aria-hidden="true" slot="enter">
        <path
          d="M16.0072 0H2.0291C0.9185 0 0.0181 0.9003 0.0181 2.011V5.5009C0.357 5.5016 0.6895 5.5275 1.0181 5.5669V2.011C1.0181 1.4536 1.4716 1 2.029 1H16.0072C16.5646 1 17.0181 1.4536 17.0181 2.011V11.9891C17.0181 12.5465 16.5646 13 16.0072 13H8.4358C8.4746 13.3286 8.4999 13.6611 8.4999 13.9999H16.0071C17.1177 13.9999 18.018 13.0996 18.018 11.989V2.011C18.0181 0.9003 17.1178 0 16.0072 0ZM0 6.4999V7.4999C3.584 7.4999 6.5 10.4159 6.5 13.9999H7.5C7.5 9.8642 4.1357 6.4999 0 6.4999ZM0 8.7499V9.7499C2.3433 9.7499 4.25 11.6566 4.25 13.9999H5.25C5.25 11.1049 2.895 8.7499 0 8.7499ZM0.0181 11V14H3.0181C3.0181 12.3431 1.675 11 0.0181 11Z"
        />
      </svg>
      <svg viewBox="0 0 18 14" aria-hidden="true" slot="exit">
        <path
          d="M15.9891 0H2.01103C0.900434 0 3.35947e-05 0.9003 3.35947e-05 2.011V5.5009C0.338934 5.5016 0.671434 5.5275 1.00003 5.5669V2.011C1.00003 1.4536 1.45353 1 2.01093 1H15.9891C16.5465 1 17 1.4536 17 2.011V11.9891C17 12.5465 16.5465 13 15.9891 13H8.41773C8.45653 13.3286 8.48183 13.6611 8.48183 13.9999H15.989C17.0996 13.9999 17.9999 13.0996 17.9999 11.989V2.011C18 0.9003 17.0997 0 15.9891 0ZM-0.0180664 6.4999V7.4999C3.56593 7.4999 6.48193 10.4159 6.48193 13.9999H7.48193C7.48193 9.8642 4.11763 6.4999 -0.0180664 6.4999ZM-0.0180664 8.7499V9.7499C2.32523 9.7499 4.23193 11.6566 4.23193 13.9999H5.23193C5.23193 11.1049 2.87693 8.7499 -0.0180664 8.7499ZM3.35947e-05 11V14H3.00003C3.00003 12.3431 1.65693 11 3.35947e-05 11Z"
        />
        <path d="M2.15002 5.634C5.18352 6.4207 7.57252 8.8151 8.35282 11.8499H15.8501V2.1499H2.15002V5.634Z" />
      </svg>
    </media-cast-button>
  </template>

  <template partial="LiveButton">
    <media-live-button part="{{section ?? 'top'}} live button" disabled="{{disabled}}" aria-disabled="{{disabled}}">
      <span slot="text">Live</span>
    </media-live-button>
  </template>

  <template partial="PlaybackRateMenu">
    <media-playback-rate-menu-button part="bottom playback-rate button"></media-playback-rate-menu-button>
    <media-playback-rate-menu
      hidden
      anchor="auto"
      rates="{{playbackrates}}"
      exportparts="menu-item"
      part="bottom playback-rate menu"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
    ></media-playback-rate-menu>
  </template>

  <template partial="VolumeRange">
    <media-volume-range
      part="bottom volume range"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
    ></media-volume-range>
  </template>

  <template partial="TimeDisplay">
    <media-time-display
      remaining="{{defaultshowremainingtime}}"
      showduration="{{!hideduration}}"
      part="bottom time display"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
    ></media-time-display>
  </template>

  <template partial="TimeRange">
    <media-time-range part="bottom time range" disabled="{{disabled}}" aria-disabled="{{disabled}}">
      <media-preview-thumbnail slot="preview"></media-preview-thumbnail>
      <media-preview-chapter-display slot="preview"></media-preview-chapter-display>
      <media-preview-time-display slot="preview"></media-preview-time-display>
      <div slot="preview" part="arrow"></div>
    </media-time-range>
  </template>

  <template partial="AudioTrackMenu">
    <media-audio-track-menu-button part="bottom audio-track button">
      <svg aria-hidden="true" slot="icon" viewBox="0 0 18 16">
        <path d="M9 15A7 7 0 1 1 9 1a7 7 0 0 1 0 14Zm0 1A8 8 0 1 0 9 0a8 8 0 0 0 0 16Z" />
        <path
          d="M5.2 6.3a.5.5 0 0 1 .5.5v2.4a.5.5 0 1 1-1 0V6.8a.5.5 0 0 1 .5-.5Zm2.4-2.4a.5.5 0 0 1 .5.5v7.2a.5.5 0 0 1-1 0V4.4a.5.5 0 0 1 .5-.5ZM10 5.5a.5.5 0 0 1 .5.5v4a.5.5 0 0 1-1 0V6a.5.5 0 0 1 .5-.5Zm2.4-.8a.5.5 0 0 1 .5.5v5.6a.5.5 0 0 1-1 0V5.2a.5.5 0 0 1 .5-.5Z"
        />
      </svg>
    </media-audio-track-menu-button>
    <media-audio-track-menu
      hidden
      anchor="auto"
      part="bottom audio-track menu"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
      exportparts="menu-item"
    >
      <div slot="checked-indicator">
        <style>
          .indicator {
            position: relative;
            top: 1px;
            width: 0.9em;
            height: auto;
            fill: var(--_accent-color);
            margin-right: 5px;
          }

          [aria-checked='false'] .indicator {
            display: none;
          }
        </style>
        <svg viewBox="0 0 14 18" class="indicator">
          <path
            d="M12.252 3.48c-.115.033-.301.161-.425.291-.059.063-1.407 1.815-2.995 3.894s-2.897 3.79-2.908 3.802c-.013.014-.661-.616-1.672-1.624-.908-.905-1.702-1.681-1.765-1.723-.401-.27-.783-.211-1.176.183a1.285 1.285 0 0 0-.261.342.582.582 0 0 0-.082.35c0 .165.01.205.08.35.075.153.213.296 2.182 2.271 1.156 1.159 2.17 2.159 2.253 2.222.189.143.338.196.539.194.203-.003.412-.104.618-.299.205-.193 6.7-8.693 6.804-8.903a.716.716 0 0 0 .085-.345c.01-.179.005-.203-.062-.339-.124-.252-.45-.531-.746-.639a.784.784 0 0 0-.469-.027"
            fill-rule="evenodd"
          />
        </svg>
      </div>
    </media-audio-track-menu>
  </template>

  <template partial="RenditionMenu">
    <media-rendition-menu-button part="bottom rendition button">
      <svg aria-hidden="true" slot="icon" viewBox="0 0 18 14">
        <path
          d="M2.25 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4ZM9 9a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm6.75 0a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z"
        />
      </svg>
    </media-rendition-menu-button>
    <media-rendition-menu
      hidden
      anchor="auto"
      part="bottom rendition menu"
      disabled="{{disabled}}"
      aria-disabled="{{disabled}}"
    >
      <div slot="checked-indicator">
        <style>
          .indicator {
            position: relative;
            top: 1px;
            width: 0.9em;
            height: auto;
            fill: var(--_accent-color);
            margin-right: 5px;
          }

          [aria-checked='false'] .indicator {
            opacity: 0;
          }
        </style>
        <svg viewBox="0 0 14 18" class="indicator">
          <path
            d="M12.252 3.48c-.115.033-.301.161-.425.291-.059.063-1.407 1.815-2.995 3.894s-2.897 3.79-2.908 3.802c-.013.014-.661-.616-1.672-1.624-.908-.905-1.702-1.681-1.765-1.723-.401-.27-.783-.211-1.176.183a1.285 1.285 0 0 0-.261.342.582.582 0 0 0-.082.35c0 .165.01.205.08.35.075.153.213.296 2.182 2.271 1.156 1.159 2.17 2.159 2.253 2.222.189.143.338.196.539.194.203-.003.412-.104.618-.299.205-.193 6.7-8.693 6.804-8.903a.716.716 0 0 0 .085-.345c.01-.179.005-.203-.062-.339-.124-.252-.45-.531-.746-.639a.784.784 0 0 0-.469-.027"
            fill-rule="evenodd"
          />
        </svg>
      </div>
    </media-rendition-menu>
  </template>

  <template partial="MuxBadge">
    <div part="mux-badge">
      <a href="https://www.mux.com/player" target="_blank">
        <span class="mux-badge-text">Powered by</span>
        <div class="mux-badge-logo">
          <svg
            viewBox="0 0 1600 500"
            style="fill-rule: evenodd; clip-rule: evenodd; stroke-linejoin: round; stroke-miterlimit: 2"
          >
            <g>
              <path
                d="M994.287,93.486c-17.121,-0 -31,-13.879 -31,-31c0,-17.121 13.879,-31 31,-31c17.121,-0 31,13.879 31,31c0,17.121 -13.879,31 -31,31m0,-93.486c-34.509,-0 -62.484,27.976 -62.484,62.486l0,187.511c0,68.943 -56.09,125.033 -125.032,125.033c-68.942,-0 -125.03,-56.09 -125.03,-125.033l0,-187.511c0,-34.51 -27.976,-62.486 -62.485,-62.486c-34.509,-0 -62.484,27.976 -62.484,62.486l0,187.511c0,137.853 112.149,250.003 249.999,250.003c137.851,-0 250.001,-112.15 250.001,-250.003l0,-187.511c0,-34.51 -27.976,-62.486 -62.485,-62.486"
                style="fill-rule: nonzero"
              ></path>
              <path
                d="M1537.51,468.511c-17.121,-0 -31,-13.879 -31,-31c0,-17.121 13.879,-31 31,-31c17.121,-0 31,13.879 31,31c0,17.121 -13.879,31 -31,31m-275.883,-218.509l-143.33,143.329c-24.402,24.402 -24.402,63.966 0,88.368c24.402,24.402 63.967,24.402 88.369,-0l143.33,-143.329l143.328,143.329c24.402,24.4 63.967,24.402 88.369,-0c24.403,-24.402 24.403,-63.966 0.001,-88.368l-143.33,-143.329l0.001,-0.004l143.329,-143.329c24.402,-24.402 24.402,-63.965 0,-88.367c-24.402,-24.402 -63.967,-24.402 -88.369,-0l-143.329,143.328l-143.329,-143.328c-24.402,-24.401 -63.967,-24.402 -88.369,-0c-24.402,24.402 -24.402,63.965 0,88.367l143.329,143.329l0,0.004Z"
                style="fill-rule: nonzero"
              ></path>
              <path
                d="M437.511,468.521c-17.121,-0 -31,-13.879 -31,-31c0,-17.121 13.879,-31 31,-31c17.121,-0 31,13.879 31,31c0,17.121 -13.879,31 -31,31m23.915,-463.762c-23.348,-9.672 -50.226,-4.327 -68.096,13.544l-143.331,143.329l-143.33,-143.329c-17.871,-17.871 -44.747,-23.216 -68.096,-13.544c-23.349,9.671 -38.574,32.455 -38.574,57.729l0,375.026c0,34.51 27.977,62.486 62.487,62.486c34.51,-0 62.486,-27.976 62.486,-62.486l0,-224.173l80.843,80.844c24.404,24.402 63.965,24.402 88.369,-0l80.843,-80.844l0,224.173c0,34.51 27.976,62.486 62.486,62.486c34.51,-0 62.486,-27.976 62.486,-62.486l0,-375.026c0,-25.274 -15.224,-48.058 -38.573,-57.729"
                style="fill-rule: nonzero"
              ></path>
            </g>
          </svg>
        </div>
      </a>
    </div>
  </template>

  <media-controller
    part="controller"
    defaultstreamtype="{{defaultstreamtype ?? 'on-demand'}}"
    breakpoints="sm:470"
    gesturesdisabled="{{disabled}}"
    hotkeys="{{hotkeys}}"
    nohotkeys="{{nohotkeys}}"
    novolumepref="{{novolumepref}}"
    audio="{{audio}}"
    noautoseektolive="{{noautoseektolive}}"
    defaultsubtitles="{{defaultsubtitles}}"
    defaultduration="{{defaultduration ?? false}}"
    keyboardforwardseekoffset="{{forwardseekoffset}}"
    keyboardbackwardseekoffset="{{backwardseekoffset}}"
    exportparts="layer, media-layer, poster-layer, vertical-layer, centered-layer, gesture-layer"
    style="--_pre-playback-place:{{preplaybackplace ?? 'center'}}"
  >
    <slot name="media" slot="media"></slot>
    <slot name="poster" slot="poster"></slot>

    <media-loading-indicator slot="centered-chrome" noautohide></media-loading-indicator>
    <media-error-dialog slot="dialog" noautohide></media-error-dialog>

    <template if="!audio">
      <!-- Pre-playback UI -->
      <!-- same for both on-demand and live -->
      <div slot="centered-chrome" class="center-controls pre-playback">
        <template if="!breakpointsm">{{>PlayButton section="center"}}</template>
        <template if="breakpointsm">{{>PrePlayButton section="center"}}</template>
      </div>

      <!-- Mux Badge -->
      <template if="proudlydisplaymuxbadge"> {{>MuxBadge}} </template>

      <!-- Autoplay centered unmute button -->
      <!--
        todo: figure out how show this with available state variables
        needs to show when:
        - autoplay is enabled
        - playback has been successful
        - audio is muted
        - in place / instead of the pre-plaback play button
        - not to show again after user has interacted with this button
          - OR user has interacted with the mute button in the control bar
      -->
      <!--
        There should be a >MuteButton to the left of the "Unmute" text, but a templating bug
        makes it appear even if commented out in the markup, add it back when code is un-commented
      -->
      <!-- <div slot="centered-chrome" class="autoplay-unmute">
        <div role="button" class="autoplay-unmute-btn">Unmute</div>
      </div> -->

      <template if="streamtype == 'on-demand'">
        <template if="breakpointsm">
          <media-control-bar part="control-bar top" slot="top-chrome">{{>TitleDisplay}} </media-control-bar>
        </template>
        {{>TimeRange}}
        <media-control-bar part="control-bar bottom">
          {{>PlayButton}} {{>SeekBackwardButton}} {{>SeekForwardButton}} {{>TimeDisplay}} {{>MuteButton}}
          {{>VolumeRange}}
          <div class="spacer"></div>
          {{>RenditionMenu}} {{>PlaybackRateMenu}} {{>AudioTrackMenu}} {{>CaptionsMenu}} {{>AirplayButton}}
          {{>CastButton}} {{>PipButton}} {{>FullscreenButton}}
        </media-control-bar>
      </template>

      <template if="streamtype == 'live'">
        <media-control-bar part="control-bar top" slot="top-chrome">
          {{>LiveButton}}
          <template if="breakpointsm"> {{>TitleDisplay}} </template>
        </media-control-bar>
        <template if="targetlivewindow > 0">{{>TimeRange}}</template>
        <media-control-bar part="control-bar bottom">
          {{>PlayButton}}
          <template if="targetlivewindow > 0">{{>SeekBackwardButton}} {{>SeekForwardButton}}</template>
          {{>MuteButton}} {{>VolumeRange}}
          <div class="spacer"></div>
          {{>RenditionMenu}} {{>AudioTrackMenu}} {{>CaptionsMenu}} {{>AirplayButton}} {{>CastButton}} {{>PipButton}}
          {{>FullscreenButton}}
        </media-control-bar>
      </template>
    </template>

    <template if="audio">
      <template if="streamtype == 'on-demand'">
        <template if="title">
          <media-control-bar part="control-bar top">{{>TitleDisplay}}</media-control-bar>
        </template>
        <media-control-bar part="control-bar bottom">
          {{>PlayButton}}
          <template if="breakpointsm"> {{>SeekBackwardButton}} {{>SeekForwardButton}} </template>
          {{>MuteButton}}
          <template if="breakpointsm">{{>VolumeRange}}</template>
          {{>TimeDisplay}} {{>TimeRange}}
          <template if="breakpointsm">{{>PlaybackRateMenu}}</template>
          {{>AirplayButton}} {{>CastButton}}
        </media-control-bar>
      </template>

      <template if="streamtype == 'live'">
        <template if="title">
          <media-control-bar part="control-bar top">{{>TitleDisplay}}</media-control-bar>
        </template>
        <media-control-bar part="control-bar bottom">
          {{>PlayButton}} {{>LiveButton section="bottom"}} {{>MuteButton}}
          <template if="breakpointsm">
            {{>VolumeRange}}
            <template if="targetlivewindow > 0"> {{>SeekBackwardButton}} {{>SeekForwardButton}} </template>
          </template>
          <template if="targetlivewindow > 0"> {{>TimeDisplay}} {{>TimeRange}} </template>
          <template if="!targetlivewindow"><div class="spacer"></div></template>
          {{>AirplayButton}} {{>CastButton}}
        </media-control-bar>
      </template>
    </template>

    <slot></slot>
  </media-controller>
</template>
`,ed=gu.createElement("template");"innerHTML"in ed&&(ed.innerHTML=Ny);var Jc,eh,lv=class extends Eo{};lv.template=(eh=(Jc=ed.content)==null?void 0:Jc.children)==null?void 0:eh[0];Ot.customElements.get("media-theme-gerwig")||Ot.customElements.define("media-theme-gerwig",lv);var Py="gerwig",Yt={SRC:"src",POSTER:"poster"},A={STYLE:"style",DEFAULT_HIDDEN_CAPTIONS:"default-hidden-captions",PRIMARY_COLOR:"primary-color",SECONDARY_COLOR:"secondary-color",ACCENT_COLOR:"accent-color",FORWARD_SEEK_OFFSET:"forward-seek-offset",BACKWARD_SEEK_OFFSET:"backward-seek-offset",PLAYBACK_TOKEN:"playback-token",THUMBNAIL_TOKEN:"thumbnail-token",STORYBOARD_TOKEN:"storyboard-token",DRM_TOKEN:"drm-token",STORYBOARD_SRC:"storyboard-src",THUMBNAIL_TIME:"thumbnail-time",AUDIO:"audio",NOHOTKEYS:"nohotkeys",HOTKEYS:"hotkeys",PLAYBACK_RATES:"playbackrates",DEFAULT_SHOW_REMAINING_TIME:"default-show-remaining-time",DEFAULT_DURATION:"default-duration",TITLE:"title",VIDEO_TITLE:"video-title",PLACEHOLDER:"placeholder",THEME:"theme",DEFAULT_STREAM_TYPE:"default-stream-type",TARGET_LIVE_WINDOW:"target-live-window",EXTRA_SOURCE_PARAMS:"extra-source-params",NO_VOLUME_PREF:"no-volume-pref",CAST_RECEIVER:"cast-receiver",NO_TOOLTIPS:"no-tooltips",PROUDLY_DISPLAY_MUX_BADGE:"proudly-display-mux-badge"},td=["audio","backwardseekoffset","defaultduration","defaultshowremainingtime","defaultsubtitles","noautoseektolive","disabled","exportparts","forwardseekoffset","hideduration","hotkeys","nohotkeys","playbackrates","defaultstreamtype","streamtype","style","targetlivewindow","template","title","videotitle","novolumepref","proudlydisplaymuxbadge"];function $y(e,t){var i,a;return{src:!e.playbackId&&e.src,playbackId:e.playbackId,hasSrc:!!e.playbackId||!!e.src||!!e.currentSrc,poster:e.poster,storyboard:e.storyboard,storyboardSrc:e.getAttribute(A.STORYBOARD_SRC),placeholder:e.getAttribute("placeholder"),themeTemplate:Hy(e),thumbnailTime:!e.tokens.thumbnail&&e.thumbnailTime,autoplay:e.autoplay,crossOrigin:e.crossOrigin,loop:e.loop,noHotKeys:e.hasAttribute(A.NOHOTKEYS),hotKeys:e.getAttribute(A.HOTKEYS),muted:e.muted,paused:e.paused,preload:e.preload,envKey:e.envKey,preferCmcd:e.preferCmcd,debug:e.debug,disableTracking:e.disableTracking,disableCookies:e.disableCookies,tokens:e.tokens,beaconCollectionDomain:e.beaconCollectionDomain,maxResolution:e.maxResolution,minResolution:e.minResolution,programStartTime:e.programStartTime,programEndTime:e.programEndTime,assetStartTime:e.assetStartTime,assetEndTime:e.assetEndTime,renditionOrder:e.renditionOrder,metadata:e.metadata,playerInitTime:e.playerInitTime,playerSoftwareName:e.playerSoftwareName,playerSoftwareVersion:e.playerSoftwareVersion,startTime:e.startTime,preferPlayback:e.preferPlayback,audio:e.audio,defaultStreamType:e.defaultStreamType,targetLiveWindow:e.getAttribute(b.TARGET_LIVE_WINDOW),streamType:yu(e.getAttribute(b.STREAM_TYPE)),primaryColor:e.getAttribute(A.PRIMARY_COLOR),secondaryColor:e.getAttribute(A.SECONDARY_COLOR),accentColor:e.getAttribute(A.ACCENT_COLOR),forwardSeekOffset:e.forwardSeekOffset,backwardSeekOffset:e.backwardSeekOffset,defaultHiddenCaptions:e.defaultHiddenCaptions,defaultDuration:e.defaultDuration,defaultShowRemainingTime:e.defaultShowRemainingTime,hideDuration:By(e),playbackRates:e.getAttribute(A.PLAYBACK_RATES),customDomain:(i=e.getAttribute(b.CUSTOM_DOMAIN))!=null?i:void 0,title:e.getAttribute(A.TITLE),videoTitle:(a=e.getAttribute(A.VIDEO_TITLE))!=null?a:e.getAttribute(A.TITLE),novolumepref:e.hasAttribute(A.NO_VOLUME_PREF),proudlyDisplayMuxBadge:e.hasAttribute(A.PROUDLY_DISPLAY_MUX_BADGE),castReceiver:e.castReceiver,...t,extraSourceParams:e.extraSourceParams}}var Uy=rp.formatErrorMessage;rp.formatErrorMessage=e=>{var t,i;if(e instanceof w){let a=xy(e,!1);return`
      ${a!=null&&a.title?`<h3>${a.title}</h3>`:""}
      ${a!=null&&a.message||a!=null&&a.linkUrl?`<p>
        ${a?.message}
        ${a!=null&&a.linkUrl?`<a
              href="${a.linkUrl}"
              target="_blank"
              rel="external noopener"
              aria-label="${(t=a.linkText)!=null?t:""} ${L("(opens in a new window)")}"
              >${(i=a.linkText)!=null?i:a.linkUrl}</a
            >`:""}
      </p>`:""}
    `}return Uy(e)};function Hy(e){var t,i;let a=e.theme;if(a){let r=(i=(t=e.getRootNode())==null?void 0:t.getElementById)==null?void 0:i.call(t,a);if(r&&r instanceof HTMLTemplateElement)return r;a.startsWith("media-theme-")||(a=`media-theme-${a}`);let n=Ot.customElements.get(a);if(n!=null&&n.template)return n.template}}function By(e){var t;let i=(t=e.mediaController)==null?void 0:t.querySelector("media-time-display");return i&&getComputedStyle(i).getPropertyValue("--media-duration-display-display").trim()==="none"}function th(e){let t=e.videoTitle?{video_title:e.videoTitle}:{};return e.getAttributeNames().filter(i=>i.startsWith("metadata-")).reduce((i,a)=>{let r=e.getAttribute(a);return r!==null&&(i[a.replace(/^metadata-/,"").replace(/-/g,"_")]=r),i},t)}var Wy=Object.values(b),Fy=Object.values(Yt),Ky=Object.values(A),ih=iv(),ah="mux-player",rh={isDialogOpen:!1},Vy={redundant_streams:!0},Ss,ws,Is,xi,Rs,Oa,oe,mi,dv,id,Oi,nh,sh,oh,lh,qy=class extends Qc{constructor(){super(),ht(this,oe),ht(this,Ss),ht(this,ws,!1),ht(this,Is,{}),ht(this,xi,!0),ht(this,Rs,new ly(this,"hotkeys")),ht(this,Oa,{...rh,onCloseErrorDialog:e=>{var t;((t=e.composedPath()[0])==null?void 0:t.localName)==="media-error-dialog"&&ve(this,oe,id).call(this,{isDialogOpen:!1})},onFocusInErrorDialog:e=>{var t;((t=e.composedPath()[0])==null?void 0:t.localName)==="media-error-dialog"&&(ev(this,gu.activeElement)||e.preventDefault())}}),Ht(this,Ss,md()),this.attachShadow({mode:"open"}),ve(this,oe,dv).call(this),this.isConnected&&ve(this,oe,mi).call(this)}static get NAME(){return ah}static get VERSION(){return ih}static get observedAttributes(){var e;return[...(e=Qc.observedAttributes)!=null?e:[],...Fy,...Wy,...Ky]}get mediaTheme(){var e;return(e=this.shadowRoot)==null?void 0:e.querySelector("media-theme")}get mediaController(){var e,t;return(t=(e=this.mediaTheme)==null?void 0:e.shadowRoot)==null?void 0:t.querySelector("media-controller")}connectedCallback(){let e=this.media;e&&(e.metadata=th(this))}attributeChangedCallback(e,t,i){switch(ve(this,oe,mi).call(this),super.attributeChangedCallback(e,t,i),e){case A.HOTKEYS:j(this,Rs).value=i;break;case A.THUMBNAIL_TIME:{i!=null&&this.tokens.thumbnail&&jt(L("Use of thumbnail-time with thumbnail-token is currently unsupported. Ignore thumbnail-time.").toString());break}case A.THUMBNAIL_TOKEN:{if(i){let a=Ia(i);if(a){let{aud:r}=a,n=Dr.THUMBNAIL;r!==n&&jt(L("The {tokenNamePrefix}-token has an incorrect aud value: {aud}. aud value should be {expectedAud}.").format({aud:r,expectedAud:n,tokenNamePrefix:"thumbnail"}))}}break}case A.STORYBOARD_TOKEN:{if(i){let a=Ia(i);if(a){let{aud:r}=a,n=Dr.STORYBOARD;r!==n&&jt(L("The {tokenNamePrefix}-token has an incorrect aud value: {aud}. aud value should be {expectedAud}.").format({aud:r,expectedAud:n,tokenNamePrefix:"storyboard"}))}}break}case A.DRM_TOKEN:{if(i){let a=Ia(i);if(a){let{aud:r}=a,n=Dr.DRM;r!==n&&jt(L("The {tokenNamePrefix}-token has an incorrect aud value: {aud}. aud value should be {expectedAud}.").format({aud:r,expectedAud:n,tokenNamePrefix:"drm"}))}}break}case b.PLAYBACK_ID:{i!=null&&i.includes("?token")&&et(L("The specificed playback ID {playbackId} contains a token which must be provided via the playback-token attribute.").format({playbackId:i}));break}case b.STREAM_TYPE:i&&![G.LIVE,G.ON_DEMAND,G.UNKNOWN].includes(i)?["ll-live","live:dvr","ll-live:dvr"].includes(this.streamType)?this.targetLiveWindow=i.includes("dvr")?Number.POSITIVE_INFINITY:0:rv({file:"invalid-stream-type.md",message:L("Invalid stream-type value supplied: `{streamType}`. Please provide stream-type as either: `on-demand` or `live`").format({streamType:this.streamType})}):i===G.LIVE?this.getAttribute(A.TARGET_LIVE_WINDOW)==null&&(this.targetLiveWindow=0):this.targetLiveWindow=Number.NaN}[b.PLAYBACK_ID,Yt.SRC,A.PLAYBACK_TOKEN].includes(e)&&t!==i&&Ht(this,Oa,{...j(this,Oa),...rh}),ve(this,oe,Oi).call(this,{[oy(e)]:i})}async requestFullscreen(e){var t;if(!(!this.mediaController||this.mediaController.hasAttribute(u.MEDIA_IS_FULLSCREEN)))return(t=this.mediaController)==null||t.dispatchEvent(new Ot.CustomEvent(R.MEDIA_ENTER_FULLSCREEN_REQUEST,{composed:!0,bubbles:!0})),new Promise((i,a)=>{var r;(r=this.mediaController)==null||r.addEventListener(Jt.MEDIA_IS_FULLSCREEN,()=>i(),{once:!0})})}async exitFullscreen(){var e;if(!(!this.mediaController||!this.mediaController.hasAttribute(u.MEDIA_IS_FULLSCREEN)))return(e=this.mediaController)==null||e.dispatchEvent(new Ot.CustomEvent(R.MEDIA_EXIT_FULLSCREEN_REQUEST,{composed:!0,bubbles:!0})),new Promise((t,i)=>{var a;(a=this.mediaController)==null||a.addEventListener(Jt.MEDIA_IS_FULLSCREEN,()=>t(),{once:!0})})}get preferCmcd(){var e;return(e=this.getAttribute(b.PREFER_CMCD))!=null?e:void 0}set preferCmcd(e){e!==this.preferCmcd&&(e?xs.includes(e)?this.setAttribute(b.PREFER_CMCD,e):jt(`Invalid value for preferCmcd. Must be one of ${xs.join()}`):this.removeAttribute(b.PREFER_CMCD))}get hasPlayed(){var e,t;return(t=(e=this.mediaController)==null?void 0:e.hasAttribute(u.MEDIA_HAS_PLAYED))!=null?t:!1}get inLiveWindow(){var e;return(e=this.mediaController)==null?void 0:e.hasAttribute(u.MEDIA_TIME_IS_LIVE)}get _hls(){var e;return(e=this.media)==null?void 0:e._hls}get mux(){var e;return(e=this.media)==null?void 0:e.mux}get theme(){var e;return(e=this.getAttribute(A.THEME))!=null?e:Py}set theme(e){this.setAttribute(A.THEME,`${e}`)}get themeProps(){let e=this.mediaTheme;if(!e)return;let t={};for(let i of e.getAttributeNames()){if(td.includes(i))continue;let a=e.getAttribute(i);t[Xp(i)]=a===""?!0:a}return t}set themeProps(e){var t,i;ve(this,oe,mi).call(this);let a={...this.themeProps,...e};for(let r in a){if(td.includes(r))continue;let n=e?.[r];typeof n=="boolean"||n==null?(t=this.mediaTheme)==null||t.toggleAttribute(Jl(r),!!n):(i=this.mediaTheme)==null||i.setAttribute(Jl(r),n)}}get playbackId(){var e;return(e=this.getAttribute(b.PLAYBACK_ID))!=null?e:void 0}set playbackId(e){e?this.setAttribute(b.PLAYBACK_ID,e):this.removeAttribute(b.PLAYBACK_ID)}get src(){var e,t;return this.playbackId?(e=Si(this,Yt.SRC))!=null?e:void 0:(t=this.getAttribute(Yt.SRC))!=null?t:void 0}set src(e){e?this.setAttribute(Yt.SRC,e):this.removeAttribute(Yt.SRC)}get poster(){var e;let t=this.getAttribute(Yt.POSTER);if(t!=null)return t;let{tokens:i}=this;if(i.playback&&!i.thumbnail){jt("Missing expected thumbnail token. No poster image will be shown");return}if(this.playbackId&&!this.audio)return ry(this.playbackId,{customDomain:this.customDomain,thumbnailTime:(e=this.thumbnailTime)!=null?e:this.startTime,programTime:this.programStartTime,token:i.thumbnail})}set poster(e){e||e===""?this.setAttribute(Yt.POSTER,e):this.removeAttribute(Yt.POSTER)}get storyboardSrc(){var e;return(e=this.getAttribute(A.STORYBOARD_SRC))!=null?e:void 0}set storyboardSrc(e){e?this.setAttribute(A.STORYBOARD_SRC,e):this.removeAttribute(A.STORYBOARD_SRC)}get storyboard(){let{tokens:e}=this;if(this.storyboardSrc&&!e.storyboard)return this.storyboardSrc;if(!(this.audio||!this.playbackId||!this.streamType||[G.LIVE,G.UNKNOWN].includes(this.streamType)||e.playback&&!e.storyboard))return ny(this.playbackId,{customDomain:this.customDomain,token:e.storyboard,programStartTime:this.programStartTime,programEndTime:this.programEndTime})}get audio(){return this.hasAttribute(A.AUDIO)}set audio(e){if(!e){this.removeAttribute(A.AUDIO);return}this.setAttribute(A.AUDIO,"")}get hotkeys(){return j(this,Rs)}get nohotkeys(){return this.hasAttribute(A.NOHOTKEYS)}set nohotkeys(e){if(!e){this.removeAttribute(A.NOHOTKEYS);return}this.setAttribute(A.NOHOTKEYS,"")}get thumbnailTime(){return Ye(this.getAttribute(A.THUMBNAIL_TIME))}set thumbnailTime(e){this.setAttribute(A.THUMBNAIL_TIME,`${e}`)}get videoTitle(){var e,t;return(t=(e=this.getAttribute(A.VIDEO_TITLE))!=null?e:this.getAttribute(A.TITLE))!=null?t:""}set videoTitle(e){e!==this.videoTitle&&(e?this.setAttribute(A.VIDEO_TITLE,e):this.removeAttribute(A.VIDEO_TITLE))}get placeholder(){var e;return(e=Si(this,A.PLACEHOLDER))!=null?e:""}set placeholder(e){this.setAttribute(A.PLACEHOLDER,`${e}`)}get primaryColor(){var e,t;let i=this.getAttribute(A.PRIMARY_COLOR);if(i!=null||this.mediaTheme&&(i=(t=(e=Ot.getComputedStyle(this.mediaTheme))==null?void 0:e.getPropertyValue("--_primary-color"))==null?void 0:t.trim(),i))return i}set primaryColor(e){this.setAttribute(A.PRIMARY_COLOR,`${e}`)}get secondaryColor(){var e,t;let i=this.getAttribute(A.SECONDARY_COLOR);if(i!=null||this.mediaTheme&&(i=(t=(e=Ot.getComputedStyle(this.mediaTheme))==null?void 0:e.getPropertyValue("--_secondary-color"))==null?void 0:t.trim(),i))return i}set secondaryColor(e){this.setAttribute(A.SECONDARY_COLOR,`${e}`)}get accentColor(){var e,t;let i=this.getAttribute(A.ACCENT_COLOR);if(i!=null||this.mediaTheme&&(i=(t=(e=Ot.getComputedStyle(this.mediaTheme))==null?void 0:e.getPropertyValue("--_accent-color"))==null?void 0:t.trim(),i))return i}set accentColor(e){this.setAttribute(A.ACCENT_COLOR,`${e}`)}get defaultShowRemainingTime(){return this.hasAttribute(A.DEFAULT_SHOW_REMAINING_TIME)}set defaultShowRemainingTime(e){e?this.setAttribute(A.DEFAULT_SHOW_REMAINING_TIME,""):this.removeAttribute(A.DEFAULT_SHOW_REMAINING_TIME)}get playbackRates(){if(this.hasAttribute(A.PLAYBACK_RATES))return this.getAttribute(A.PLAYBACK_RATES).trim().split(/\s*,?\s+/).map(e=>Number(e)).filter(e=>!Number.isNaN(e)).sort((e,t)=>e-t)}set playbackRates(e){if(!e){this.removeAttribute(A.PLAYBACK_RATES);return}this.setAttribute(A.PLAYBACK_RATES,e.join(" "))}get forwardSeekOffset(){var e;return(e=Ye(this.getAttribute(A.FORWARD_SEEK_OFFSET)))!=null?e:10}set forwardSeekOffset(e){this.setAttribute(A.FORWARD_SEEK_OFFSET,`${e}`)}get backwardSeekOffset(){var e;return(e=Ye(this.getAttribute(A.BACKWARD_SEEK_OFFSET)))!=null?e:10}set backwardSeekOffset(e){this.setAttribute(A.BACKWARD_SEEK_OFFSET,`${e}`)}get defaultHiddenCaptions(){return this.hasAttribute(A.DEFAULT_HIDDEN_CAPTIONS)}set defaultHiddenCaptions(e){e?this.setAttribute(A.DEFAULT_HIDDEN_CAPTIONS,""):this.removeAttribute(A.DEFAULT_HIDDEN_CAPTIONS)}get defaultDuration(){return Ye(this.getAttribute(A.DEFAULT_DURATION))}set defaultDuration(e){e==null?this.removeAttribute(A.DEFAULT_DURATION):this.setAttribute(A.DEFAULT_DURATION,`${e}`)}get playerInitTime(){return this.hasAttribute(b.PLAYER_INIT_TIME)?Ye(this.getAttribute(b.PLAYER_INIT_TIME)):j(this,Ss)}set playerInitTime(e){e!=this.playerInitTime&&(e==null?this.removeAttribute(b.PLAYER_INIT_TIME):this.setAttribute(b.PLAYER_INIT_TIME,`${+e}`))}get playerSoftwareName(){var e;return(e=this.getAttribute(b.PLAYER_SOFTWARE_NAME))!=null?e:ah}get playerSoftwareVersion(){var e;return(e=this.getAttribute(b.PLAYER_SOFTWARE_VERSION))!=null?e:ih}get beaconCollectionDomain(){var e;return(e=this.getAttribute(b.BEACON_COLLECTION_DOMAIN))!=null?e:void 0}set beaconCollectionDomain(e){e!==this.beaconCollectionDomain&&(e?this.setAttribute(b.BEACON_COLLECTION_DOMAIN,e):this.removeAttribute(b.BEACON_COLLECTION_DOMAIN))}get maxResolution(){var e;return(e=this.getAttribute(b.MAX_RESOLUTION))!=null?e:void 0}set maxResolution(e){e!==this.maxResolution&&(e?this.setAttribute(b.MAX_RESOLUTION,e):this.removeAttribute(b.MAX_RESOLUTION))}get minResolution(){var e;return(e=this.getAttribute(b.MIN_RESOLUTION))!=null?e:void 0}set minResolution(e){e!==this.minResolution&&(e?this.setAttribute(b.MIN_RESOLUTION,e):this.removeAttribute(b.MIN_RESOLUTION))}get renditionOrder(){var e;return(e=this.getAttribute(b.RENDITION_ORDER))!=null?e:void 0}set renditionOrder(e){e!==this.renditionOrder&&(e?this.setAttribute(b.RENDITION_ORDER,e):this.removeAttribute(b.RENDITION_ORDER))}get programStartTime(){return Ye(this.getAttribute(b.PROGRAM_START_TIME))}set programStartTime(e){e==null?this.removeAttribute(b.PROGRAM_START_TIME):this.setAttribute(b.PROGRAM_START_TIME,`${e}`)}get programEndTime(){return Ye(this.getAttribute(b.PROGRAM_END_TIME))}set programEndTime(e){e==null?this.removeAttribute(b.PROGRAM_END_TIME):this.setAttribute(b.PROGRAM_END_TIME,`${e}`)}get assetStartTime(){return Ye(this.getAttribute(b.ASSET_START_TIME))}set assetStartTime(e){e==null?this.removeAttribute(b.ASSET_START_TIME):this.setAttribute(b.ASSET_START_TIME,`${e}`)}get assetEndTime(){return Ye(this.getAttribute(b.ASSET_END_TIME))}set assetEndTime(e){e==null?this.removeAttribute(b.ASSET_END_TIME):this.setAttribute(b.ASSET_END_TIME,`${e}`)}get extraSourceParams(){return this.hasAttribute(A.EXTRA_SOURCE_PARAMS)?[...new URLSearchParams(this.getAttribute(A.EXTRA_SOURCE_PARAMS)).entries()].reduce((e,[t,i])=>(e[t]=i,e),{}):Vy}set extraSourceParams(e){e==null?this.removeAttribute(A.EXTRA_SOURCE_PARAMS):this.setAttribute(A.EXTRA_SOURCE_PARAMS,new URLSearchParams(e).toString())}get customDomain(){var e;return(e=this.getAttribute(b.CUSTOM_DOMAIN))!=null?e:void 0}set customDomain(e){e!==this.customDomain&&(e?this.setAttribute(b.CUSTOM_DOMAIN,e):this.removeAttribute(b.CUSTOM_DOMAIN))}get envKey(){var e;return(e=Si(this,b.ENV_KEY))!=null?e:void 0}set envKey(e){this.setAttribute(b.ENV_KEY,`${e}`)}get noVolumePref(){return this.hasAttribute(A.NO_VOLUME_PREF)}set noVolumePref(e){e?this.setAttribute(A.NO_VOLUME_PREF,""):this.removeAttribute(A.NO_VOLUME_PREF)}get debug(){return Si(this,b.DEBUG)!=null}set debug(e){e?this.setAttribute(b.DEBUG,""):this.removeAttribute(b.DEBUG)}get disableTracking(){return Si(this,b.DISABLE_TRACKING)!=null}set disableTracking(e){this.toggleAttribute(b.DISABLE_TRACKING,!!e)}get disableCookies(){return Si(this,b.DISABLE_COOKIES)!=null}set disableCookies(e){e?this.setAttribute(b.DISABLE_COOKIES,""):this.removeAttribute(b.DISABLE_COOKIES)}get streamType(){var e,t,i;return(i=(t=this.getAttribute(b.STREAM_TYPE))!=null?t:(e=this.media)==null?void 0:e.streamType)!=null?i:G.UNKNOWN}set streamType(e){this.setAttribute(b.STREAM_TYPE,`${e}`)}get defaultStreamType(){var e,t,i;return(i=(t=this.getAttribute(A.DEFAULT_STREAM_TYPE))!=null?t:(e=this.mediaController)==null?void 0:e.getAttribute(A.DEFAULT_STREAM_TYPE))!=null?i:G.ON_DEMAND}set defaultStreamType(e){e?this.setAttribute(A.DEFAULT_STREAM_TYPE,e):this.removeAttribute(A.DEFAULT_STREAM_TYPE)}get targetLiveWindow(){var e,t;return this.hasAttribute(A.TARGET_LIVE_WINDOW)?+this.getAttribute(A.TARGET_LIVE_WINDOW):(t=(e=this.media)==null?void 0:e.targetLiveWindow)!=null?t:Number.NaN}set targetLiveWindow(e){e==this.targetLiveWindow||Number.isNaN(e)&&Number.isNaN(this.targetLiveWindow)||(e==null?this.removeAttribute(A.TARGET_LIVE_WINDOW):this.setAttribute(A.TARGET_LIVE_WINDOW,`${+e}`))}get liveEdgeStart(){var e;return(e=this.media)==null?void 0:e.liveEdgeStart}get startTime(){return Ye(Si(this,b.START_TIME))}set startTime(e){this.setAttribute(b.START_TIME,`${e}`)}get preferPlayback(){let e=this.getAttribute(b.PREFER_PLAYBACK);if(e===Nt.MSE||e===Nt.NATIVE)return e}set preferPlayback(e){e!==this.preferPlayback&&(e===Nt.MSE||e===Nt.NATIVE?this.setAttribute(b.PREFER_PLAYBACK,e):this.removeAttribute(b.PREFER_PLAYBACK))}get metadata(){var e;return(e=this.media)==null?void 0:e.metadata}set metadata(e){if(ve(this,oe,mi).call(this),!this.media){et("underlying media element missing when trying to set metadata. metadata will not be set.");return}this.media.metadata={...th(this),...e}}get _hlsConfig(){var e;return(e=this.media)==null?void 0:e._hlsConfig}set _hlsConfig(e){if(ve(this,oe,mi).call(this),!this.media){et("underlying media element missing when trying to set _hlsConfig. _hlsConfig will not be set.");return}this.media._hlsConfig=e}async addCuePoints(e){var t;if(ve(this,oe,mi).call(this),!this.media){et("underlying media element missing when trying to addCuePoints. cuePoints will not be added.");return}return(t=this.media)==null?void 0:t.addCuePoints(e)}get activeCuePoint(){var e;return(e=this.media)==null?void 0:e.activeCuePoint}get cuePoints(){var e,t;return(t=(e=this.media)==null?void 0:e.cuePoints)!=null?t:[]}addChapters(e){var t;if(ve(this,oe,mi).call(this),!this.media){et("underlying media element missing when trying to addChapters. chapters will not be added.");return}return(t=this.media)==null?void 0:t.addChapters(e)}get activeChapter(){var e;return(e=this.media)==null?void 0:e.activeChapter}get chapters(){var e,t;return(t=(e=this.media)==null?void 0:e.chapters)!=null?t:[]}getStartDate(){var e;return(e=this.media)==null?void 0:e.getStartDate()}get currentPdt(){var e;return(e=this.media)==null?void 0:e.currentPdt}get tokens(){let e=this.getAttribute(A.PLAYBACK_TOKEN),t=this.getAttribute(A.DRM_TOKEN),i=this.getAttribute(A.THUMBNAIL_TOKEN),a=this.getAttribute(A.STORYBOARD_TOKEN);return{...j(this,Is),...e!=null?{playback:e}:{},...t!=null?{drm:t}:{},...i!=null?{thumbnail:i}:{},...a!=null?{storyboard:a}:{}}}set tokens(e){Ht(this,Is,e??{})}get playbackToken(){var e;return(e=this.getAttribute(A.PLAYBACK_TOKEN))!=null?e:void 0}set playbackToken(e){this.setAttribute(A.PLAYBACK_TOKEN,`${e}`)}get drmToken(){var e;return(e=this.getAttribute(A.DRM_TOKEN))!=null?e:void 0}set drmToken(e){this.setAttribute(A.DRM_TOKEN,`${e}`)}get thumbnailToken(){var e;return(e=this.getAttribute(A.THUMBNAIL_TOKEN))!=null?e:void 0}set thumbnailToken(e){this.setAttribute(A.THUMBNAIL_TOKEN,`${e}`)}get storyboardToken(){var e;return(e=this.getAttribute(A.STORYBOARD_TOKEN))!=null?e:void 0}set storyboardToken(e){this.setAttribute(A.STORYBOARD_TOKEN,`${e}`)}addTextTrack(e,t,i,a){var r;let n=(r=this.media)==null?void 0:r.nativeEl;if(n)return dd(n,e,t,i,a)}removeTextTrack(e){var t;let i=(t=this.media)==null?void 0:t.nativeEl;if(i)return e_(i,e)}get textTracks(){var e;return(e=this.media)==null?void 0:e.textTracks}get castReceiver(){var e;return(e=this.getAttribute(A.CAST_RECEIVER))!=null?e:void 0}set castReceiver(e){e!==this.castReceiver&&(e?this.setAttribute(A.CAST_RECEIVER,e):this.removeAttribute(A.CAST_RECEIVER))}get castCustomData(){var e;return(e=this.media)==null?void 0:e.castCustomData}set castCustomData(e){if(!this.media){et("underlying media element missing when trying to set castCustomData. castCustomData will not be set.");return}this.media.castCustomData=e}get noTooltips(){return this.hasAttribute(A.NO_TOOLTIPS)}set noTooltips(e){if(!e){this.removeAttribute(A.NO_TOOLTIPS);return}this.setAttribute(A.NO_TOOLTIPS,"")}get proudlyDisplayMuxBadge(){return this.hasAttribute(A.PROUDLY_DISPLAY_MUX_BADGE)}set proudlyDisplayMuxBadge(e){e?this.setAttribute(A.PROUDLY_DISPLAY_MUX_BADGE,""):this.removeAttribute(A.PROUDLY_DISPLAY_MUX_BADGE)}};Ss=new WeakMap,ws=new WeakMap,Is=new WeakMap,xi=new WeakMap,Rs=new WeakMap,Oa=new WeakMap,oe=new WeakSet,mi=function(){var e,t,i,a;if(!j(this,ws)){Ht(this,ws,!0),ve(this,oe,Oi).call(this);try{if(customElements.upgrade(this.mediaTheme),!(this.mediaTheme instanceof Ot.HTMLElement))throw""}catch{et("<media-theme> failed to upgrade!")}try{customElements.upgrade(this.media)}catch{et("underlying media element failed to upgrade!")}try{if(customElements.upgrade(this.mediaController),!(this.mediaController instanceof yg))throw""}catch{et("<media-controller> failed to upgrade!")}ve(this,oe,nh).call(this),ve(this,oe,sh).call(this),ve(this,oe,oh).call(this),Ht(this,xi,(t=(e=this.mediaController)==null?void 0:e.hasAttribute(M.USER_INACTIVE))!=null?t:!0),ve(this,oe,lh).call(this),(i=this.media)==null||i.addEventListener("streamtypechange",()=>ve(this,oe,Oi).call(this)),(a=this.media)==null||a.addEventListener("loadstart",()=>ve(this,oe,Oi).call(this))}},dv=function(){var e,t;try{(e=window?.CSS)==null||e.registerProperty({name:"--media-primary-color",syntax:"<color>",inherits:!0}),(t=window?.CSS)==null||t.registerProperty({name:"--media-secondary-color",syntax:"<color>",inherits:!0})}catch{}},id=function(e){Object.assign(j(this,Oa),e),ve(this,oe,Oi).call(this)},Oi=function(e={}){ky(wy($y(this,{...j(this,Oa),...e})),this.shadowRoot)},nh=function(){let e=t=>{var i,a;if(!(t!=null&&t.startsWith("theme-")))return;let r=t.replace(/^theme-/,"");if(td.includes(r))return;let n=this.getAttribute(t);n!=null?(i=this.mediaTheme)==null||i.setAttribute(r,n):(a=this.mediaTheme)==null||a.removeAttribute(r)};new MutationObserver(t=>{for(let{attributeName:i}of t)e(i)}).observe(this,{attributes:!0}),this.getAttributeNames().forEach(e)},sh=function(){let e=t=>{var i;let a=(i=this.media)==null?void 0:i.error;if(!(a instanceof w)){let{message:n,code:s}=a??{};a=new w(n,s)}if(!(a!=null&&a.fatal)){jt(a),a.data&&jt(`${a.name} data:`,a.data);return}let r=Xc(a);r.message&&rv(r),et(a),a.data&&et(`${a.name} data:`,a.data),ve(this,oe,id).call(this,{isDialogOpen:!0})};this.addEventListener("error",e),this.media&&(this.media.errorTranslator=(t={})=>{var i,a,r;if(!(((i=this.media)==null?void 0:i.error)instanceof w))return t;let n=Xc((a=this.media)==null?void 0:a.error);return{player_error_code:(r=this.media)==null?void 0:r.error.code,player_error_message:n.message?String(n.message):t.player_error_message,player_error_context:n.context?String(n.context):t.player_error_context}})},oh=function(){var e,t,i,a;let r=()=>ve(this,oe,Oi).call(this);(t=(e=this.media)==null?void 0:e.textTracks)==null||t.addEventListener("addtrack",r),(a=(i=this.media)==null?void 0:i.textTracks)==null||a.addEventListener("removetrack",r)},lh=function(){var e,t;if(!/Firefox/i.test(navigator.userAgent))return;let i,a=new WeakMap,r=()=>this.streamType===G.LIVE&&!this.secondaryColor&&this.offsetWidth>=800,n=(l,d,m=!1)=>{r()||Array.from(l&&l.activeCues||[]).forEach(p=>{if(!(!p.snapToLines||p.line<-5||p.line>=0&&p.line<10))if(!d||this.paused){let h=p.text.split(`
`).length,c=-3;this.streamType===G.LIVE&&(c=-2);let v=c-h;if(p.line===v&&!m)return;a.has(p)||a.set(p,p.line),p.line=v}else setTimeout(()=>{p.line=a.get(p)||"auto"},500)})},s=()=>{var l,d;n(i,(d=(l=this.mediaController)==null?void 0:l.hasAttribute(M.USER_INACTIVE))!=null?d:!1)},o=()=>{var l,d;let m=Array.from(((d=(l=this.mediaController)==null?void 0:l.media)==null?void 0:d.textTracks)||[]).filter(p=>["subtitles","captions"].includes(p.kind)&&p.mode==="showing")[0];m!==i&&i?.removeEventListener("cuechange",s),i=m,i?.addEventListener("cuechange",s),n(i,j(this,xi))};o(),(e=this.textTracks)==null||e.addEventListener("change",o),(t=this.textTracks)==null||t.addEventListener("addtrack",o),this.addEventListener("userinactivechange",()=>{var l,d;let m=(d=(l=this.mediaController)==null?void 0:l.hasAttribute(M.USER_INACTIVE))!=null?d:!0;j(this,xi)!==m&&(Ht(this,xi,m),n(i,j(this,xi)))})};function Si(e,t){return e.media?e.media.getAttribute(t):e.getAttribute(t)}var dh=qy,uv=class{addEventListener(){}removeEventListener(){}dispatchEvent(e){return!0}};if(typeof DocumentFragment>"u"){class e extends uv{}globalThis.DocumentFragment=e}var Yy=class extends uv{},Gy={get(e){},define(e,t,i){},getName(e){return null},upgrade(e){},whenDefined(e){return Promise.resolve(Yy)}},jy={customElements:Gy},Qy=typeof window>"u"||typeof globalThis.customElements>"u",Ko=Qy?jy:globalThis;Ko.customElements.get("mux-player")||(Ko.customElements.define("mux-player",dh),Ko.MuxPlayerElement=dh);var cv=parseInt(Ur.version)>=19,uh={className:"class",classname:"class",htmlFor:"for",crossOrigin:"crossorigin",viewBox:"viewBox",playsInline:"playsinline",autoPlay:"autoplay",playbackRate:"playbackrate"},Zy=e=>e==null,zy=(e,t)=>Zy(t)?!1:e in t,Xy=e=>e.replace(/[A-Z]/g,t=>`-${t.toLowerCase()}`),Jy=(e,t)=>{if(!(!cv&&typeof t=="boolean"&&!t)){if(zy(e,uh))return uh[e];if(typeof t<"u")return/[A-Z]/.test(e)?Xy(e):e}},eT=(e,t)=>!cv&&typeof e=="boolean"?"":e,tT=(e={})=>{let{ref:t,...i}=e;return Object.entries(i).reduce((a,[r,n])=>{let s=Jy(r,n);if(!s)return a;let o=eT(n);return a[s]=o,a},{})};function ch(e,t){if(typeof e=="function")return e(t);e!=null&&(e.current=t)}function iT(...e){return t=>{let i=!1,a=e.map(r=>{let n=ch(r,t);return!i&&typeof n=="function"&&(i=!0),n});if(i)return()=>{for(let r=0;r<a.length;r++){let n=a[r];typeof n=="function"?n():ch(e[r],null)}}}}function aT(...e){return Hr.useCallback(iT(...e),e)}var rT=Object.prototype.hasOwnProperty,nT=(e,t)=>{if(Object.is(e,t))return!0;if(typeof e!="object"||e===null||typeof t!="object"||t===null)return!1;if(Array.isArray(e))return!Array.isArray(t)||e.length!==t.length?!1:e.some((r,n)=>t[n]===r);let i=Object.keys(e),a=Object.keys(t);if(i.length!==a.length)return!1;for(let r=0;r<i.length;r++)if(!rT.call(t,i[r])||!Object.is(e[i[r]],t[i[r]]))return!1;return!0},hv=(e,t,i)=>!nT(t,e[i]),sT=(e,t,i)=>{e[i]=t},oT=(e,t,i,a=sT,r=hv)=>Hr.useEffect(()=>{let n=i?.current;n&&r(n,t,e)&&a(n,t,e)},[i?.current,t]),gt=oT,lT=()=>{try{return"3.5.1"}catch{}return"UNKNOWN"},dT=lT(),uT=()=>dT,ne=(e,t,i)=>Hr.useEffect(()=>{let a=t?.current;if(!a||!i)return;let r=e,n=i;return a.addEventListener(r,n),()=>{a.removeEventListener(r,n)}},[t?.current,i,e]),cT=Ur.forwardRef(({children:e,...t},i)=>Ur.createElement("mux-player",{suppressHydrationWarning:!0,...tT(t),ref:i},e)),hT=(e,t)=>{let{onAbort:i,onCanPlay:a,onCanPlayThrough:r,onEmptied:n,onLoadStart:s,onLoadedData:o,onLoadedMetadata:l,onProgress:d,onDurationChange:m,onVolumeChange:p,onRateChange:h,onResize:c,onWaiting:v,onPlay:g,onPlaying:_,onTimeUpdate:y,onPause:T,onSeeking:f,onSeeked:S,onStalled:D,onSuspend:O,onEnded:H,onError:Y,onCuePointChange:Q,onChapterChange:W,metadata:P,tokens:De,paused:He,playbackId:Be,playbackRates:ce,currentTime:xe,themeProps:ft,extraSourceParams:Oe,castCustomData:rt,_hlsConfig:Et,...Ne}=t;return gt("playbackRates",ce,e),gt("metadata",P,e),gt("extraSourceParams",Oe,e),gt("_hlsConfig",Et,e),gt("themeProps",ft,e),gt("tokens",De,e),gt("playbackId",Be,e),gt("castCustomData",rt,e),gt("paused",He,e,(We,Ze)=>{Ze!=null&&(Ze?We.pause():We.play())},(We,Ze,zi)=>We.hasAttribute("autoplay")&&!We.hasPlayed?!1:hv(We,Ze,zi)),gt("currentTime",xe,e,(We,Ze)=>{Ze!=null&&(We.currentTime=Ze)}),ne("abort",e,i),ne("canplay",e,a),ne("canplaythrough",e,r),ne("emptied",e,n),ne("loadstart",e,s),ne("loadeddata",e,o),ne("loadedmetadata",e,l),ne("progress",e,d),ne("durationchange",e,m),ne("volumechange",e,p),ne("ratechange",e,h),ne("resize",e,c),ne("waiting",e,v),ne("play",e,g),ne("playing",e,_),ne("timeupdate",e,y),ne("pause",e,T),ne("seeking",e,f),ne("seeked",e,S),ne("stalled",e,D),ne("suspend",e,O),ne("ended",e,H),ne("error",e,Y),ne("cuepointchange",e,Q),ne("chapterchange",e,W),[Ne]},mT=uT(),pT="mux-player-react",vT=Ur.forwardRef((e,t)=>{var i;let a=Hr.useRef(null),r=aT(a,t),[n]=hT(a,e),[s]=Hr.useState((i=e.playerInitTime)!=null?i:md());return Ur.createElement(cT,{ref:r,defaultHiddenCaptions:e.defaultHiddenCaptions,playerSoftwareName:pT,playerSoftwareVersion:mT,playerInitTime:s,...n})}),PT=vT;export{gT as MaxResolution,w as MediaError,yT as MinResolution,TT as RenditionOrder,PT as default,md as generatePlayerInitTime,pT as playerSoftwareName,mT as playerSoftwareVersion};
