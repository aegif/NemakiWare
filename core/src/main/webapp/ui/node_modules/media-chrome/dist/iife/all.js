var MediaChrome=(()=>{var Po=Object.defineProperty;var Bd=Object.getOwnPropertyDescriptor;var Vd=Object.getOwnPropertyNames;var Kd=Object.prototype.hasOwnProperty;var Uo=(t,i)=>{for(var e in i)Po(t,e,{get:i[e],enumerable:!0})},Gd=(t,i,e,r)=>{if(i&&typeof i=="object"||typeof i=="function")for(let n of Vd(i))!Kd.call(t,n)&&n!==e&&Po(t,n,{get:()=>i[n],enumerable:!(r=Bd(i,n))||r.enumerable});return t};var qd=t=>Gd(Po({},"__esModule",{value:!0}),t);var Oo=(t,i,e)=>{if(!i.has(t))throw TypeError("Cannot "+e)};var s=(t,i,e)=>(Oo(t,i,"read from private field"),e?e.call(t):i.get(t)),c=(t,i,e)=>{if(i.has(t))throw TypeError("Cannot add the same private member more than once");i instanceof WeakSet?i.add(t):i.set(t,e)},h=(t,i,e,r)=>(Oo(t,i,"write to private field"),r?r.call(t,e):i.set(t,e),e);var la=(t,i,e,r)=>({set _(n){h(t,i,n,e)},get _(){return s(t,i,r)}}),p=(t,i,e)=>(Oo(t,i,"access private method"),e);var km={};Uo(km,{AttrPart:()=>fe,AttrPartList:()=>_o,ChildNodePart:()=>sn,InnerTemplatePart:()=>Oi,MediaAirplayButton:()=>il,MediaAudioTrackMenu:()=>Yr,MediaAudioTrackMenuButton:()=>Rt,MediaCaptionsButton:()=>sl,MediaCaptionsMenu:()=>Ci,MediaCaptionsMenuButton:()=>xt,MediaCastButton:()=>ll,MediaChromeButton:()=>Ja,MediaChromeDialog:()=>pl,MediaChromeMenu:()=>K,MediaChromeMenuButton:()=>z,MediaChromeMenuItem:()=>be,MediaChromeRange:()=>Tl,MediaContainer:()=>xa,MediaControlBar:()=>Al,MediaController:()=>Za,MediaDurationDisplay:()=>Sl,MediaErrorDialog:()=>Ll,MediaFullscreenButton:()=>_l,MediaGestureReceiver:()=>Vi,MediaLiveButton:()=>xl,MediaLoadingIndicator:()=>Dl,MediaMuteButton:()=>Ul,MediaPipButton:()=>Nl,MediaPlayButton:()=>Bl,MediaPlaybackRateButton:()=>Fl,MediaPlaybackRateMenu:()=>Jr,MediaPlaybackRateMenuButton:()=>Ct,MediaPosterImage:()=>Vl,MediaPreviewChapterDisplay:()=>Kl,MediaPreviewThumbnail:()=>Ar,MediaPreviewTimeDisplay:()=>Gl,MediaRenditionMenu:()=>rn,MediaRenditionMenuButton:()=>Pt,MediaSeekBackwardButton:()=>ql,MediaSeekForwardButton:()=>Wl,MediaSettingsMenu:()=>Ri,MediaSettingsMenuButton:()=>_t,MediaSettingsMenuItem:()=>Ze,MediaTextDisplay:()=>yl,MediaThemeElement:()=>Hi,MediaTimeDisplay:()=>Ql,MediaTimeRange:()=>ed,MediaTooltip:()=>er,MediaVolumeRange:()=>td,Part:()=>on,TemplateInstance:()=>et,constants:()=>En,defaultProcessor:()=>_d,parse:()=>Ms,t:()=>g,timeUtils:()=>fn,tokenize:()=>Is});var En={};Uo(En,{AttributeToStateChangeEventMap:()=>Ho,AvailabilityStates:()=>Z,MediaStateChangeEvents:()=>ve,MediaStateReceiverAttributes:()=>_,MediaUIAttributes:()=>a,MediaUIEvents:()=>b,MediaUIProps:()=>hn,PointerTypes:()=>pn,ReadyStates:()=>jd,StateChangeEventToAttributeMap:()=>Yd,StreamTypes:()=>ee,TextTrackKinds:()=>W,TextTrackModes:()=>Me,VolumeLevels:()=>Qd,WebkitPresentationModes:()=>No});var b={MEDIA_PLAY_REQUEST:"mediaplayrequest",MEDIA_PAUSE_REQUEST:"mediapauserequest",MEDIA_MUTE_REQUEST:"mediamuterequest",MEDIA_UNMUTE_REQUEST:"mediaunmuterequest",MEDIA_VOLUME_REQUEST:"mediavolumerequest",MEDIA_SEEK_REQUEST:"mediaseekrequest",MEDIA_AIRPLAY_REQUEST:"mediaairplayrequest",MEDIA_ENTER_FULLSCREEN_REQUEST:"mediaenterfullscreenrequest",MEDIA_EXIT_FULLSCREEN_REQUEST:"mediaexitfullscreenrequest",MEDIA_PREVIEW_REQUEST:"mediapreviewrequest",MEDIA_ENTER_PIP_REQUEST:"mediaenterpiprequest",MEDIA_EXIT_PIP_REQUEST:"mediaexitpiprequest",MEDIA_ENTER_CAST_REQUEST:"mediaentercastrequest",MEDIA_EXIT_CAST_REQUEST:"mediaexitcastrequest",MEDIA_SHOW_TEXT_TRACKS_REQUEST:"mediashowtexttracksrequest",MEDIA_HIDE_TEXT_TRACKS_REQUEST:"mediahidetexttracksrequest",MEDIA_SHOW_SUBTITLES_REQUEST:"mediashowsubtitlesrequest",MEDIA_DISABLE_SUBTITLES_REQUEST:"mediadisablesubtitlesrequest",MEDIA_TOGGLE_SUBTITLES_REQUEST:"mediatogglesubtitlesrequest",MEDIA_PLAYBACK_RATE_REQUEST:"mediaplaybackraterequest",MEDIA_RENDITION_REQUEST:"mediarenditionrequest",MEDIA_AUDIO_TRACK_REQUEST:"mediaaudiotrackrequest",MEDIA_SEEK_TO_LIVE_REQUEST:"mediaseektoliverequest",REGISTER_MEDIA_STATE_RECEIVER:"registermediastatereceiver",UNREGISTER_MEDIA_STATE_RECEIVER:"unregistermediastatereceiver"},_={MEDIA_CHROME_ATTRIBUTES:"mediachromeattributes",MEDIA_CONTROLLER:"mediacontroller"},hn={MEDIA_AIRPLAY_UNAVAILABLE:"mediaAirplayUnavailable",MEDIA_AUDIO_TRACK_ENABLED:"mediaAudioTrackEnabled",MEDIA_AUDIO_TRACK_LIST:"mediaAudioTrackList",MEDIA_AUDIO_TRACK_UNAVAILABLE:"mediaAudioTrackUnavailable",MEDIA_BUFFERED:"mediaBuffered",MEDIA_CAST_UNAVAILABLE:"mediaCastUnavailable",MEDIA_CHAPTERS_CUES:"mediaChaptersCues",MEDIA_CURRENT_TIME:"mediaCurrentTime",MEDIA_DURATION:"mediaDuration",MEDIA_ENDED:"mediaEnded",MEDIA_ERROR:"mediaError",MEDIA_ERROR_CODE:"mediaErrorCode",MEDIA_ERROR_MESSAGE:"mediaErrorMessage",MEDIA_FULLSCREEN_UNAVAILABLE:"mediaFullscreenUnavailable",MEDIA_HAS_PLAYED:"mediaHasPlayed",MEDIA_HEIGHT:"mediaHeight",MEDIA_IS_AIRPLAYING:"mediaIsAirplaying",MEDIA_IS_CASTING:"mediaIsCasting",MEDIA_IS_FULLSCREEN:"mediaIsFullscreen",MEDIA_IS_PIP:"mediaIsPip",MEDIA_LOADING:"mediaLoading",MEDIA_MUTED:"mediaMuted",MEDIA_PAUSED:"mediaPaused",MEDIA_PIP_UNAVAILABLE:"mediaPipUnavailable",MEDIA_PLAYBACK_RATE:"mediaPlaybackRate",MEDIA_PREVIEW_CHAPTER:"mediaPreviewChapter",MEDIA_PREVIEW_COORDS:"mediaPreviewCoords",MEDIA_PREVIEW_IMAGE:"mediaPreviewImage",MEDIA_PREVIEW_TIME:"mediaPreviewTime",MEDIA_RENDITION_LIST:"mediaRenditionList",MEDIA_RENDITION_SELECTED:"mediaRenditionSelected",MEDIA_RENDITION_UNAVAILABLE:"mediaRenditionUnavailable",MEDIA_SEEKABLE:"mediaSeekable",MEDIA_STREAM_TYPE:"mediaStreamType",MEDIA_SUBTITLES_LIST:"mediaSubtitlesList",MEDIA_SUBTITLES_SHOWING:"mediaSubtitlesShowing",MEDIA_TARGET_LIVE_WINDOW:"mediaTargetLiveWindow",MEDIA_TIME_IS_LIVE:"mediaTimeIsLive",MEDIA_VOLUME:"mediaVolume",MEDIA_VOLUME_LEVEL:"mediaVolumeLevel",MEDIA_VOLUME_UNAVAILABLE:"mediaVolumeUnavailable",MEDIA_WIDTH:"mediaWidth"},da=Object.entries(hn),a=da.reduce((t,[i,e])=>(t[i]=e.toLowerCase(),t),{}),Wd={USER_INACTIVE_CHANGE:"userinactivechange",BREAKPOINTS_CHANGE:"breakpointchange",BREAKPOINTS_COMPUTED:"breakpointscomputed"},ve=da.reduce((t,[i,e])=>(t[i]=e.toLowerCase(),t),{...Wd}),Yd=Object.entries(ve).reduce((t,[i,e])=>{let r=a[i];return r&&(t[e]=r),t},{userinactivechange:"userinactive"}),Ho=Object.entries(a).reduce((t,[i,e])=>{let r=ve[i];return r&&(t[e]=r),t},{userinactive:"userinactivechange"}),W={SUBTITLES:"subtitles",CAPTIONS:"captions",DESCRIPTIONS:"descriptions",CHAPTERS:"chapters",METADATA:"metadata"},Me={DISABLED:"disabled",HIDDEN:"hidden",SHOWING:"showing"},jd={HAVE_NOTHING:0,HAVE_METADATA:1,HAVE_CURRENT_DATA:2,HAVE_FUTURE_DATA:3,HAVE_ENOUGH_DATA:4},pn={MOUSE:"mouse",PEN:"pen",TOUCH:"touch"},Z={UNAVAILABLE:"unavailable",UNSUPPORTED:"unsupported"},ee={LIVE:"live",ON_DEMAND:"on-demand",UNKNOWN:"unknown"},Qd={HIGH:"high",MEDIUM:"medium",LOW:"low",OFF:"off"},No={INLINE:"inline",FULLSCREEN:"fullscreen",PICTURE_IN_PICTURE:"picture-in-picture"};var fn={};Uo(fn,{emptyTimeRanges:()=>ga,formatAsTimePhrase:()=>Ne,formatTime:()=>te,serializeTimeRanges:()=>tu});function ua(t){return t==null?void 0:t.map(zd).join(" ")}function ca(t){return t==null?void 0:t.split(/\s+/).map(Zd)}function zd(t){if(t){let{id:i,width:e,height:r}=t;return[i,e,r].filter(n=>n!=null).join(":")}}function Zd(t){if(t){let[i,e,r]=t.split(":");return{id:i,width:+e,height:+r}}}function ma(t){return t==null?void 0:t.map(Xd).join(" ")}function ha(t){return t==null?void 0:t.split(/\s+/).map(Jd)}function Xd(t){if(t){let{id:i,kind:e,language:r,label:n}=t;return[i,e,r,n].filter(o=>o!=null).join(":")}}function Jd(t){if(t){let[i,e,r,n]=t.split(":");return{id:i,kind:e,language:r,label:n}}}function pa(t){return t.replace(/[-_]([a-z])/g,(i,e)=>e.toUpperCase())}function Vt(t){return typeof t=="number"&&!Number.isNaN(t)&&Number.isFinite(t)}function gn(t){return typeof t!="string"?!1:!isNaN(t)&&!isNaN(parseFloat(t))}var bn=t=>new Promise(i=>setTimeout(i,t));var Ea=[{singular:"hour",plural:"hours"},{singular:"minute",plural:"minutes"},{singular:"second",plural:"seconds"}],eu=(t,i)=>{let e=t===1?Ea[i].singular:Ea[i].plural;return`${t} ${e}`},Ne=t=>{if(!Vt(t))return"";let i=Math.abs(t),e=i!==t,r=new Date(0,0,0,0,0,i,0);return`${[r.getHours(),r.getMinutes(),r.getSeconds()].map((d,m)=>d&&eu(d,m)).filter(d=>d).join(", ")}${e?" remaining":""}`};function te(t,i){let e=!1;t<0&&(e=!0,t=0-t),t=t<0?0:t;let r=Math.floor(t%60),n=Math.floor(t/60%60),o=Math.floor(t/3600),l=Math.floor(i/60%60),d=Math.floor(i/3600);return(isNaN(t)||t===1/0)&&(o=n=r="0"),o=o>0||d>0?o+":":"",n=((o||l>=10)&&n<10?"0"+n:n)+":",r=r<10?"0"+r:r,(e?"-":"")+o+n+r}var ga=Object.freeze({length:0,start(t){let i=t>>>0;if(i>=this.length)throw new DOMException(`Failed to execute 'start' on 'TimeRanges': The index provided (${i}) is greater than or equal to the maximum bound (${this.length}).`);return 0},end(t){let i=t>>>0;if(i>=this.length)throw new DOMException(`Failed to execute 'end' on 'TimeRanges': The index provided (${i}) is greater than or equal to the maximum bound (${this.length}).`);return 0}});function tu(t=ga){return Array.from(t).map((i,e)=>[Number(t.start(e).toFixed(3)),Number(t.end(e).toFixed(3))].join(":")).join(" ")}var ba={"Start airplay":"Start airplay","Stop airplay":"Stop airplay",Audio:"Audio",Captions:"Captions","Enable captions":"Enable captions","Disable captions":"Disable captions","Start casting":"Start casting","Stop casting":"Stop casting","Enter fullscreen mode":"Enter fullscreen mode","Exit fullscreen mode":"Exit fullscreen mode",Mute:"Mute",Unmute:"Unmute","Enter picture in picture mode":"Enter picture in picture mode","Exit picture in picture mode":"Exit picture in picture mode",Play:"Play",Pause:"Pause","Playback rate":"Playback rate","Playback rate {playbackRate}":"Playback rate {playbackRate}",Quality:"Quality","Seek backward":"Seek backward","Seek forward":"Seek forward",Settings:"Settings",Auto:"Auto","audio player":"audio player","video player":"video player",volume:"volume",seek:"seek","closed captions":"closed captions","current playback rate":"current playback rate","playback time":"playback time","media loading":"media loading",settings:"settings","audio tracks":"audio tracks",quality:"quality",play:"play",pause:"pause",mute:"mute",unmute:"unmute",live:"live",Off:"Off","start airplay":"start airplay","stop airplay":"stop airplay","start casting":"start casting","stop casting":"stop casting","enter fullscreen mode":"enter fullscreen mode","exit fullscreen mode":"exit fullscreen mode","enter picture in picture mode":"enter picture in picture mode","exit picture in picture mode":"exit picture in picture mode","seek to live":"seek to live","playing live":"playing live","seek back {seekOffset} seconds":"seek back {seekOffset} seconds","seek forward {seekOffset} seconds":"seek forward {seekOffset} seconds","Network Error":"Network Error","Decode Error":"Decode Error","Source Not Supported":"Source Not Supported","Encryption Error":"Encryption Error","A network error caused the media download to fail.":"A network error caused the media download to fail.","A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.":"A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.","An unsupported error occurred. The server or network failed, or your browser does not support this format.":"An unsupported error occurred. The server or network failed, or your browser does not support this format.","The media is encrypted and there are no keys to decrypt it.":"The media is encrypted and there are no keys to decrypt it."};var Fo={en:ba},fa,$o=((fa=globalThis.navigator)==null?void 0:fa.language)||"en",va=t=>{$o=t};var iu=t=>{var e,r,n;let[i]=$o.split("-");return((e=Fo[$o])==null?void 0:e[t])||((r=Fo[i])==null?void 0:r[t])||((n=Fo.en)==null?void 0:n[t])||t},g=(t,i={})=>iu(t).replace(/\{(\w+)\}/g,(e,r)=>r in i?String(i[r]):`{${r}}`);var vn=class{addEventListener(){}removeEventListener(){}dispatchEvent(){return!0}},Tn=class extends vn{},An=class extends Tn{constructor(){super(...arguments);this.role=null}},Bo=class{observe(){}unobserve(){}disconnect(){}},Ta={createElement:function(){return new $i.HTMLElement},createElementNS:function(){return new $i.HTMLElement},addEventListener(){},removeEventListener(){},dispatchEvent(t){return!1}},$i={ResizeObserver:Bo,document:Ta,Node:Tn,Element:An,HTMLElement:class extends An{constructor(){super(...arguments);this.innerHTML=""}get content(){return new $i.DocumentFragment}},DocumentFragment:class extends vn{},customElements:{get:function(){},define:function(){},whenDefined:function(){}},localStorage:{getItem(t){return null},setItem(t,i){},removeItem(t){}},CustomEvent:function(){},getComputedStyle:function(){},navigator:{languages:[],get userAgent(){return""}},matchMedia(t){return{matches:!1,media:t}},DOMParser:class{parseFromString(i,e){return{body:{textContent:i}}}}},Aa=typeof window=="undefined"||typeof window.customElements=="undefined",ya=Object.keys($i).every(t=>t in globalThis),u=Aa&&!ya?$i:globalThis,F=Aa&&!ya?Ta:globalThis.document;var Sa=new WeakMap,Vo=t=>{let i=Sa.get(t);return i||Sa.set(t,i=new Set),i},Ia=new u.ResizeObserver(t=>{for(let i of t)for(let e of Vo(i.target))e(i)});function Le(t,i){Vo(t).add(i),Ia.observe(t)}function ke(t,i){let e=Vo(t);e.delete(i),e.size||Ia.unobserve(t)}function $(t){let i={};for(let e of t)i[e.name]=e.value;return i}function B(t){var i;return(i=yn(t))!=null?i:Te(t,"media-controller")}function yn(t){var r;let{MEDIA_CONTROLLER:i}=_,e=t.getAttribute(i);if(e)return(r=tt(t))==null?void 0:r.getElementById(e)}var Sn=(t,i,e=".value")=>{let r=t.querySelector(e);r&&(r.textContent=i)},ru=(t,i)=>{let e=`slot[name="${i}"]`,r=t.shadowRoot.querySelector(e);return r?r.children:[]},In=(t,i)=>ru(t,i)[0],Y=(t,i)=>!t||!i?!1:t!=null&&t.contains(i)?!0:Y(t,i.getRootNode().host),Te=(t,i)=>{if(!t)return null;let e=t.closest(i);return e||Te(t.getRootNode().host,i)};function Bi(t=document){var e;let i=t==null?void 0:t.activeElement;return i?(e=Bi(i.shadowRoot))!=null?e:i:null}function tt(t){var e;let i=(e=t==null?void 0:t.getRootNode)==null?void 0:e.call(t);return i instanceof ShadowRoot||i instanceof Document?i:null}function Mn(t,{depth:i=3,checkOpacity:e=!0,checkVisibilityCSS:r=!0}={}){if(t.checkVisibility)return t.checkVisibility({checkOpacity:e,checkVisibilityCSS:r});let n=t;for(;n&&i>0;){let o=getComputedStyle(n);if(e&&o.opacity==="0"||r&&o.visibility==="hidden"||o.display==="none")return!1;n=n.parentElement,i--}return!0}function Ma(t,i,e,r){let n=r.x-e.x,o=r.y-e.y,l=n*n+o*o;if(l===0)return 0;let d=((t-e.x)*n+(i-e.y)*o)/l;return Math.max(0,Math.min(1,d))}function O(t,i){let e=nu(t,r=>r===i);return e||Ko(t,i)}function nu(t,i){var r,n;let e;for(e of(r=t.querySelectorAll("style:not([media])"))!=null?r:[]){let o;try{o=(n=e.sheet)==null?void 0:n.cssRules}catch{continue}for(let l of o!=null?o:[])if(i(l.selectorText))return l}}function Ko(t,i){var n,o;let e=(n=t.querySelectorAll("style:not([media])"))!=null?n:[],r=e==null?void 0:e[e.length-1];return r!=null&&r.sheet?(r==null||r.sheet.insertRule(`${i}{}`,r.sheet.cssRules.length),(o=r.sheet.cssRules)==null?void 0:o[r.sheet.cssRules.length-1]):(console.warn("Media Chrome: No style sheet found on style tag of",t),{style:{setProperty:()=>{},removeProperty:()=>"",getPropertyValue:()=>""}})}function R(t,i,e=Number.NaN){let r=t.getAttribute(i);return r!=null?+r:e}function C(t,i,e){let r=+e;if(e==null||Number.isNaN(r)){t.hasAttribute(i)&&t.removeAttribute(i);return}R(t,i,void 0)!==r&&t.setAttribute(i,`${r}`)}function I(t,i){return t.hasAttribute(i)}function M(t,i,e){if(e==null){t.hasAttribute(i)&&t.removeAttribute(i);return}I(t,i)!=e&&t.toggleAttribute(i,e)}function L(t,i,e=null){var r;return(r=t.getAttribute(i))!=null?r:e}function k(t,i,e){if(e==null){t.hasAttribute(i)&&t.removeAttribute(i);return}let r=`${e}`;L(t,i,void 0)!==r&&t.setAttribute(i,r)}function ou(t){return`
    <style>
      :host {
        display: var(--media-control-display, var(--media-gesture-receiver-display, inline-block));
        box-sizing: border-box;
      }
    </style>
  `}var j,Kt=class extends u.HTMLElement{constructor(){super();c(this,j,void 0);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER,a.MEDIA_PAUSED]}attributeChangedCallback(e,r,n){var o,l,d,m,E;e===_.MEDIA_CONTROLLER&&(r&&((l=(o=s(this,j))==null?void 0:o.unassociateElement)==null||l.call(o,this),h(this,j,null)),n&&this.isConnected&&(h(this,j,(d=this.getRootNode())==null?void 0:d.getElementById(n)),(E=(m=s(this,j))==null?void 0:m.associateElement)==null||E.call(m,this)))}connectedCallback(){var e,r,n,o;this.tabIndex=-1,this.setAttribute("aria-hidden","true"),h(this,j,su(this)),this.getAttribute(_.MEDIA_CONTROLLER)&&((r=(e=s(this,j))==null?void 0:e.associateElement)==null||r.call(e,this)),(n=s(this,j))==null||n.addEventListener("pointerdown",this),(o=s(this,j))==null||o.addEventListener("click",this)}disconnectedCallback(){var e,r,n,o;this.getAttribute(_.MEDIA_CONTROLLER)&&((r=(e=s(this,j))==null?void 0:e.unassociateElement)==null||r.call(e,this)),(n=s(this,j))==null||n.removeEventListener("pointerdown",this),(o=s(this,j))==null||o.removeEventListener("click",this),h(this,j,null)}handleEvent(e){var o;let r=(o=e.composedPath())==null?void 0:o[0];if(["video","media-controller"].includes(r==null?void 0:r.localName)){if(e.type==="pointerdown")this._pointerType=e.pointerType;else if(e.type==="click"){let{clientX:l,clientY:d}=e,{left:m,top:E,width:T,height:y}=this.getBoundingClientRect(),v=l-m,f=d-E;if(v<0||f<0||v>T||f>y||T===0&&y===0)return;let{pointerType:w=this._pointerType}=e;if(this._pointerType=void 0,w===pn.TOUCH){this.handleTap(e);return}else if(w===pn.MOUSE){this.handleMouseClick(e);return}}}}get mediaPaused(){return I(this,a.MEDIA_PAUSED)}set mediaPaused(e){M(this,a.MEDIA_PAUSED,e)}handleTap(e){}handleMouseClick(e){let r=this.mediaPaused?b.MEDIA_PLAY_REQUEST:b.MEDIA_PAUSE_REQUEST;this.dispatchEvent(new u.CustomEvent(r,{composed:!0,bubbles:!0}))}};j=new WeakMap,Kt.shadowRootOptions={mode:"open"},Kt.getTemplateHTML=ou;function su(t){var e;let i=t.getAttribute(_.MEDIA_CONTROLLER);return i?(e=t.getRootNode())==null?void 0:e.getElementById(i):Te(t,"media-controller")}u.customElements.get("media-gesture-receiver")||u.customElements.define("media-gesture-receiver",Kt);var Vi=Kt;var S={AUDIO:"audio",AUTOHIDE:"autohide",BREAKPOINTS:"breakpoints",GESTURES_DISABLED:"gesturesdisabled",KEYBOARD_CONTROL:"keyboardcontrol",NO_AUTOHIDE:"noautohide",USER_INACTIVE:"userinactive",AUTOHIDE_OVER_CONTROLS:"autohideovercontrols"};function au(t){return`
    <style>
      
      :host([${a.MEDIA_IS_FULLSCREEN}]) ::slotted([slot=media]) {
        outline: none;
      }

      :host {
        box-sizing: border-box;
        position: relative;
        display: inline-block;
        line-height: 0;
        background-color: var(--media-background-color, #000);
      }

      :host(:not([${S.AUDIO}])) [part~=layer]:not([part~=media-layer]) {
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

      
      :host([${S.AUDIO}]) slot[name=media] {
        display: var(--media-slot-display, none);
      }

      
      :host([${S.AUDIO}]) [part~=layer][part~=gesture-layer] {
        height: 0;
        display: block;
      }

      
      :host(:not([${S.AUDIO}])[${S.GESTURES_DISABLED}]) ::slotted([slot=gestures-chrome]),
          :host(:not([${S.AUDIO}])[${S.GESTURES_DISABLED}]) media-gesture-receiver[slot=gestures-chrome] {
        display: none;
      }

      
      ::slotted(:not([slot=media]):not([slot=poster]):not(media-loading-indicator):not([role=dialog]):not([hidden])) {
        pointer-events: auto;
      }

      :host(:not([${S.AUDIO}])) *[part~=layer][part~=centered-layer] {
        align-items: center;
        justify-content: center;
      }

      :host(:not([${S.AUDIO}])) ::slotted(media-gesture-receiver[slot=gestures-chrome]),
      :host(:not([${S.AUDIO}])) media-gesture-receiver[slot=gestures-chrome] {
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

      
      :host(:not([${S.AUDIO}])) .spacer {
        flex-grow: 1;
      }

      
      :host(:-webkit-full-screen) {
        
        width: 100% !important;
        height: 100% !important;
      }

      
      ::slotted(:not([slot=media]):not([slot=poster]):not([${S.NO_AUTOHIDE}]):not([hidden]):not([role=dialog])) {
        opacity: 1;
        transition: var(--media-control-transition-in, opacity 0.25s);
      }

      
      :host([${S.USER_INACTIVE}]:not([${a.MEDIA_PAUSED}]):not([${a.MEDIA_IS_AIRPLAYING}]):not([${a.MEDIA_IS_CASTING}]):not([${S.AUDIO}])) ::slotted(:not([slot=media]):not([slot=poster]):not([${S.NO_AUTOHIDE}]):not([role=dialog])) {
        opacity: 0;
        transition: var(--media-control-transition-out, opacity 1s);
      }

      :host([${S.USER_INACTIVE}]:not([${S.NO_AUTOHIDE}]):not([${a.MEDIA_PAUSED}]):not([${a.MEDIA_IS_CASTING}]):not([${S.AUDIO}])) ::slotted([slot=media]) {
        cursor: none;
      }

      :host([${S.USER_INACTIVE}][${S.AUTOHIDE_OVER_CONTROLS}]:not([${S.NO_AUTOHIDE}]):not([${a.MEDIA_PAUSED}]):not([${a.MEDIA_IS_CASTING}]):not([${S.AUDIO}])) * {
        --media-cursor: none;
        cursor: none;
      }


      ::slotted(media-control-bar)  {
        align-self: stretch;
      }

      
      :host(:not([${S.AUDIO}])[${a.MEDIA_HAS_PLAYED}]) slot[name=poster] {
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
        <template shadowrootmode="${Vi.shadowRootOptions.mode}">
          ${Vi.getTemplateHTML({})}
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
  `}var lu=Object.values(a),du="sm:384 md:576 lg:768 xl:960";function uu(t){La(t.target,t.contentRect.width)}function La(t,i){var l;if(!t.isConnected)return;let e=(l=t.getAttribute(S.BREAKPOINTS))!=null?l:du,r=cu(e),n=mu(r,i),o=!1;if(Object.keys(r).forEach(d=>{if(n.includes(d)){t.hasAttribute(`breakpoint${d}`)||(t.setAttribute(`breakpoint${d}`,""),o=!0);return}t.hasAttribute(`breakpoint${d}`)&&(t.removeAttribute(`breakpoint${d}`),o=!0)}),o){let d=new CustomEvent(ve.BREAKPOINTS_CHANGE,{detail:n});t.dispatchEvent(d)}t.breakpointsComputed||(t.breakpointsComputed=!0,t.dispatchEvent(new CustomEvent(ve.BREAKPOINTS_COMPUTED,{bubbles:!0,composed:!0})))}function cu(t){let i=t.split(/\s+/);return Object.fromEntries(i.map(e=>e.split(":")))}function mu(t,i){return Object.keys(t).filter(e=>i>=parseInt(t[e]))}var Gi,it,Gt,rt,qi,kn,ka,qt,Wi,_n,_a,Rn,Ra,Wt,Ln,Yi,Go,nt,Ki,Fe=class extends u.HTMLElement{constructor(){super();c(this,kn);c(this,_n);c(this,Rn);c(this,Wt);c(this,Yi);c(this,nt);c(this,Gi,0);c(this,it,null);c(this,Gt,null);c(this,rt,void 0);this.breakpointsComputed=!1;c(this,qi,new MutationObserver(p(this,kn,ka).bind(this)));c(this,qt,!1);c(this,Wi,e=>{s(this,qt)||(setTimeout(()=>{uu(e),h(this,qt,!1)},0),h(this,qt,!0))});if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let r=$(this.attributes),n=this.constructor.getTemplateHTML(r);this.shadowRoot.setHTMLUnsafe?this.shadowRoot.setHTMLUnsafe(n):this.shadowRoot.innerHTML=n}let e=this.querySelector(":scope > slot[slot=media]");e&&e.addEventListener("slotchange",()=>{if(!e.assignedElements({flatten:!0}).length){s(this,it)&&this.mediaUnsetCallback(s(this,it));return}this.handleMediaUpdated(this.media)})}static get observedAttributes(){return[S.AUTOHIDE,S.GESTURES_DISABLED].concat(lu).filter(e=>![a.MEDIA_RENDITION_LIST,a.MEDIA_AUDIO_TRACK_LIST,a.MEDIA_CHAPTERS_CUES,a.MEDIA_WIDTH,a.MEDIA_HEIGHT,a.MEDIA_ERROR,a.MEDIA_ERROR_MESSAGE].includes(e))}attributeChangedCallback(e,r,n){e.toLowerCase()==S.AUTOHIDE&&(this.autohide=n)}get media(){let e=this.querySelector(":scope > [slot=media]");return(e==null?void 0:e.nodeName)=="SLOT"&&(e=e.assignedElements({flatten:!0})[0]),e}async handleMediaUpdated(e){e&&(h(this,it,e),e.localName.includes("-")&&await u.customElements.whenDefined(e.localName),this.mediaSetCallback(e))}connectedCallback(){var n;s(this,qi).observe(this,{childList:!0,subtree:!0}),Le(this,s(this,Wi));let r=this.getAttribute(S.AUDIO)!=null?g("audio player"):g("video player");this.setAttribute("role","region"),this.setAttribute("aria-label",r),this.handleMediaUpdated(this.media),this.setAttribute(S.USER_INACTIVE,""),La(this,this.getBoundingClientRect().width),this.addEventListener("pointerdown",this),this.addEventListener("pointermove",this),this.addEventListener("pointerup",this),this.addEventListener("mouseleave",this),this.addEventListener("keyup",this),(n=u.window)==null||n.addEventListener("mouseup",this)}disconnectedCallback(){var e;s(this,qi).disconnect(),ke(this,s(this,Wi)),this.media&&this.mediaUnsetCallback(this.media),(e=u.window)==null||e.removeEventListener("mouseup",this)}mediaSetCallback(e){}mediaUnsetCallback(e){h(this,it,null)}handleEvent(e){switch(e.type){case"pointerdown":h(this,Gi,e.timeStamp);break;case"pointermove":p(this,_n,_a).call(this,e);break;case"pointerup":p(this,Rn,Ra).call(this,e);break;case"mouseleave":p(this,Wt,Ln).call(this);break;case"mouseup":this.removeAttribute(S.KEYBOARD_CONTROL);break;case"keyup":p(this,nt,Ki).call(this),this.setAttribute(S.KEYBOARD_CONTROL,"");break}}set autohide(e){let r=Number(e);h(this,rt,isNaN(r)?0:r)}get autohide(){return(s(this,rt)===void 0?2:s(this,rt)).toString()}get breakpoints(){return L(this,S.BREAKPOINTS)}set breakpoints(e){k(this,S.BREAKPOINTS,e)}get audio(){return I(this,S.AUDIO)}set audio(e){M(this,S.AUDIO,e)}get gesturesDisabled(){return I(this,S.GESTURES_DISABLED)}set gesturesDisabled(e){M(this,S.GESTURES_DISABLED,e)}get keyboardControl(){return I(this,S.KEYBOARD_CONTROL)}set keyboardControl(e){M(this,S.KEYBOARD_CONTROL,e)}get noAutohide(){return I(this,S.NO_AUTOHIDE)}set noAutohide(e){M(this,S.NO_AUTOHIDE,e)}get autohideOverControls(){return I(this,S.AUTOHIDE_OVER_CONTROLS)}set autohideOverControls(e){M(this,S.AUTOHIDE_OVER_CONTROLS,e)}get userInteractive(){return I(this,S.USER_INACTIVE)}set userInteractive(e){M(this,S.USER_INACTIVE,e)}};Gi=new WeakMap,it=new WeakMap,Gt=new WeakMap,rt=new WeakMap,qi=new WeakMap,kn=new WeakSet,ka=function(e){let r=this.media;for(let n of e){if(n.type!=="childList")continue;let o=n.removedNodes;for(let l of o){if(l.slot!="media"||n.target!=this)continue;let d=n.previousSibling&&n.previousSibling.previousElementSibling;if(!d||!r)this.mediaUnsetCallback(l);else{let m=d.slot!=="media";for(;(d=d.previousSibling)!==null;)d.slot=="media"&&(m=!1);m&&this.mediaUnsetCallback(l)}}if(r)for(let l of n.addedNodes)l===r&&this.handleMediaUpdated(r)}},qt=new WeakMap,Wi=new WeakMap,_n=new WeakSet,_a=function(e){if(e.pointerType!=="mouse"&&e.timeStamp-s(this,Gi)<250)return;p(this,Yi,Go).call(this),clearTimeout(s(this,Gt));let r=this.hasAttribute(S.AUTOHIDE_OVER_CONTROLS);([this,this.media].includes(e.target)||r)&&p(this,nt,Ki).call(this)},Rn=new WeakSet,Ra=function(e){if(e.pointerType==="touch"){let r=!this.hasAttribute(S.USER_INACTIVE);[this,this.media].includes(e.target)&&r?p(this,Wt,Ln).call(this):p(this,nt,Ki).call(this)}else e.composedPath().some(r=>["media-play-button","media-fullscreen-button"].includes(r==null?void 0:r.localName))&&p(this,nt,Ki).call(this)},Wt=new WeakSet,Ln=function(){if(s(this,rt)<0||this.hasAttribute(S.USER_INACTIVE))return;this.setAttribute(S.USER_INACTIVE,"");let e=new u.CustomEvent(ve.USER_INACTIVE_CHANGE,{composed:!0,bubbles:!0,detail:!0});this.dispatchEvent(e)},Yi=new WeakSet,Go=function(){if(!this.hasAttribute(S.USER_INACTIVE))return;this.removeAttribute(S.USER_INACTIVE);let e=new u.CustomEvent(ve.USER_INACTIVE_CHANGE,{composed:!0,bubbles:!0,detail:!1});this.dispatchEvent(e)},nt=new WeakSet,Ki=function(){p(this,Yi,Go).call(this),clearTimeout(s(this,Gt));let e=parseInt(this.autohide);e<0||h(this,Gt,setTimeout(()=>{p(this,Wt,Ln).call(this)},e*1e3))},Fe.shadowRootOptions={mode:"open"},Fe.getTemplateHTML=au;u.customElements.get("media-container")||u.customElements.define("media-container",Fe);var xa=Fe;var ot,st,ji,Be,Ae,$e,Ve=class{constructor(i,e,{defaultValue:r}={defaultValue:void 0}){c(this,Ae);c(this,ot,void 0);c(this,st,void 0);c(this,ji,void 0);c(this,Be,new Set);h(this,ot,i),h(this,st,e),h(this,ji,new Set(r))}[Symbol.iterator](){return s(this,Ae,$e).values()}get length(){return s(this,Ae,$e).size}get value(){var i;return(i=[...s(this,Ae,$e)].join(" "))!=null?i:""}set value(i){var e;i!==this.value&&(h(this,Be,new Set),this.add(...(e=i==null?void 0:i.split(" "))!=null?e:[]))}toString(){return this.value}item(i){return[...s(this,Ae,$e)][i]}values(){return s(this,Ae,$e).values()}forEach(i,e){s(this,Ae,$e).forEach(i,e)}add(...i){var e,r;i.forEach(n=>s(this,Be).add(n)),!(this.value===""&&!((e=s(this,ot))!=null&&e.hasAttribute(`${s(this,st)}`)))&&((r=s(this,ot))==null||r.setAttribute(`${s(this,st)}`,`${this.value}`))}remove(...i){var e;i.forEach(r=>s(this,Be).delete(r)),(e=s(this,ot))==null||e.setAttribute(`${s(this,st)}`,`${this.value}`)}contains(i){return s(this,Ae,$e).has(i)}toggle(i,e){return typeof e!="undefined"?e?(this.add(i),!0):(this.remove(i),!1):this.contains(i)?(this.remove(i),!1):(this.add(i),!0)}replace(i,e){return this.remove(i),this.add(e),i===e}};ot=new WeakMap,st=new WeakMap,ji=new WeakMap,Be=new WeakMap,Ae=new WeakSet,$e=function(){return s(this,Be).size?s(this,Be):s(this,ji)};var hu=(t="")=>t.split(/\s+/),Ca=(t="")=>{let[i,e,r]=t.split(":"),n=r?decodeURIComponent(r):void 0;return{kind:i==="cc"?W.CAPTIONS:W.SUBTITLES,language:e,label:n}},at=(t="",i={})=>hu(t).map(e=>{let r=Ca(e);return{...i,...r}}),qo=t=>t?Array.isArray(t)?t.map(i=>typeof i=="string"?Ca(i):i):typeof t=="string"?at(t):[t]:[],xn=({kind:t,label:i,language:e}={kind:"subtitles"})=>i?`${t==="captions"?"cc":"sb"}:${e}:${encodeURIComponent(i)}`:e,_e=(t=[])=>Array.prototype.map.call(t,xn).join(" "),pu=(t,i)=>e=>e[t]===i,Da=t=>{let i=Object.entries(t).map(([e,r])=>pu(e,r));return e=>i.every(r=>r(e))},lt=(t,i=[],e=[])=>{let r=qo(e).map(Da),n=o=>r.some(l=>l(o));Array.from(i).filter(n).forEach(o=>{o.mode=t})},dt=(t,i=()=>!0)=>{if(!(t!=null&&t.textTracks))return[];let e=typeof i=="function"?i:Da(i);return Array.from(t.textTracks).filter(e)},Cn=t=>{var e;return!!((e=t.mediaSubtitlesShowing)!=null&&e.length)||t.hasAttribute(a.MEDIA_SUBTITLES_SHOWING)};var Pa=t=>{var r;let{media:i,fullscreenElement:e}=t;try{let n=e&&"requestFullscreen"in e?"requestFullscreen":e&&"webkitRequestFullScreen"in e?"webkitRequestFullScreen":void 0;if(n){let o=(r=e[n])==null?void 0:r.call(e);if(o instanceof Promise)return o.catch(()=>{})}else i!=null&&i.webkitEnterFullscreen?i.webkitEnterFullscreen():i!=null&&i.requestFullscreen&&i.requestFullscreen()}catch(n){console.error(n)}},wa="exitFullscreen"in F?"exitFullscreen":"webkitExitFullscreen"in F?"webkitExitFullscreen":"webkitCancelFullScreen"in F?"webkitCancelFullScreen":void 0,Ua=t=>{var e;let{documentElement:i}=t;if(wa){let r=(e=i==null?void 0:i[wa])==null?void 0:e.call(i);if(r instanceof Promise)return r.catch(()=>{})}},Qi="fullscreenElement"in F?"fullscreenElement":"webkitFullscreenElement"in F?"webkitFullscreenElement":void 0,Eu=t=>{let{documentElement:i,media:e}=t,r=i==null?void 0:i[Qi];return!r&&"webkitDisplayingFullscreen"in e&&"webkitPresentationMode"in e&&e.webkitDisplayingFullscreen&&e.webkitPresentationMode===No.FULLSCREEN?e:r},Oa=t=>{var o;let{media:i,documentElement:e,fullscreenElement:r=i}=t;if(!i||!e)return!1;let n=Eu(t);if(!n)return!1;if(n===r||n===i)return!0;if(n.localName.includes("-")){let l=n.shadowRoot;if(!(Qi in l))return Y(n,r);for(;l!=null&&l[Qi];){if(l[Qi]===r)return!0;l=(o=l[Qi])==null?void 0:o.shadowRoot}}return!1},gu="fullscreenEnabled"in F?"fullscreenEnabled":"webkitFullscreenEnabled"in F?"webkitFullscreenEnabled":void 0,Ha=t=>{let{documentElement:i,media:e}=t;return!!(i!=null&&i[gu])||e&&"webkitSupportsFullscreen"in e};var Dn,Wo=()=>{var t,i;return Dn||(Dn=(i=(t=F)==null?void 0:t.createElement)==null?void 0:i.call(t,"video"),Dn)},Na=async(t=Wo())=>{if(!t)return!1;let i=t.volume;t.volume=i/2+.1;let e=new AbortController,r=await Promise.race([bu(t,e.signal),fu(t,i)]);return e.abort(),r},bu=(t,i)=>new Promise(e=>{t.addEventListener("volumechange",()=>e(!0),{signal:i})}),fu=async(t,i)=>{for(let e=0;e<10;e++){if(t.volume===i)return!1;await bn(10)}return t.volume!==i},vu=/.*Version\/.*Safari\/.*/.test(u.navigator.userAgent),Yo=(t=Wo())=>u.matchMedia("(display-mode: standalone)").matches&&vu?!1:typeof(t==null?void 0:t.requestPictureInPicture)=="function",jo=(t=Wo())=>Ha({documentElement:F,media:t}),Fa=jo(),$a=Yo(),Ba=!!u.WebKitPlaybackTargetAvailabilityEvent,Va=!!u.chrome;var Yt=t=>dt(t.media,i=>[W.SUBTITLES,W.CAPTIONS].includes(i.kind)).sort((i,e)=>i.kind>=e.kind?1:-1),Qo=t=>dt(t.media,i=>i.mode===Me.SHOWING&&[W.SUBTITLES,W.CAPTIONS].includes(i.kind)),wn=(t,i)=>{let e=Yt(t),r=Qo(t),n=!!r.length;if(e.length){if(i===!1||n&&i!==!0)lt(Me.DISABLED,e,r);else if(i===!0||!n&&i!==!1){let o=e[0],{options:l}=t;if(!(l!=null&&l.noSubtitlesLangPref)){let T=globalThis.localStorage.getItem("media-chrome-pref-subtitles-lang"),y=T?[T,...globalThis.navigator.languages]:globalThis.navigator.languages,v=e.filter(f=>y.some(w=>f.language.toLowerCase().startsWith(w.split("-")[0]))).sort((f,w)=>{let x=y.findIndex(U=>f.language.toLowerCase().startsWith(U.split("-")[0])),D=y.findIndex(U=>w.language.toLowerCase().startsWith(U.split("-")[0]));return x-D});v[0]&&(o=v[0])}let{language:d,label:m,kind:E}=o;lt(Me.DISABLED,e,r),lt(Me.SHOWING,e,[{language:d,label:m,kind:E}])}}},Pn=(t,i)=>t===i?!0:t==null||i==null||typeof t!=typeof i?!1:typeof t=="number"&&Number.isNaN(t)&&Number.isNaN(i)?!0:typeof t!="object"?!1:Array.isArray(t)?Tu(t,i):Object.entries(t).every(([e,r])=>e in i&&Pn(r,i[e])),Tu=(t,i)=>{let e=Array.isArray(t),r=Array.isArray(i);return e!==r?!1:e||r?t.length!==i.length?!1:t.every((n,o)=>Pn(n,i[o])):!0};var Au=Object.values(ee),Un,yu=Na().then(t=>(Un=t,Un)),Ka=async(...t)=>{await Promise.all(t.filter(i=>i).map(async i=>{if(!("localName"in i&&i instanceof u.HTMLElement))return;let e=i.localName;if(!e.includes("-"))return;let r=u.customElements.get(e);r&&i instanceof r||(await u.customElements.whenDefined(e),u.customElements.upgrade(i))}))},Su=new u.DOMParser,Iu=t=>t&&(Su.parseFromString(t,"text/html").body.textContent||t),jt={mediaError:{get(t,i){let{media:e}=t;if((i==null?void 0:i.type)!=="playing")return e==null?void 0:e.error},mediaEvents:["emptied","error","playing"]},mediaErrorCode:{get(t,i){var r;let{media:e}=t;if((i==null?void 0:i.type)!=="playing")return(r=e==null?void 0:e.error)==null?void 0:r.code},mediaEvents:["emptied","error","playing"]},mediaErrorMessage:{get(t,i){var r,n;let{media:e}=t;if((i==null?void 0:i.type)!=="playing")return(n=(r=e==null?void 0:e.error)==null?void 0:r.message)!=null?n:""},mediaEvents:["emptied","error","playing"]},mediaWidth:{get(t){var e;let{media:i}=t;return(e=i==null?void 0:i.videoWidth)!=null?e:0},mediaEvents:["resize"]},mediaHeight:{get(t){var e;let{media:i}=t;return(e=i==null?void 0:i.videoHeight)!=null?e:0},mediaEvents:["resize"]},mediaPaused:{get(t){var e;let{media:i}=t;return(e=i==null?void 0:i.paused)!=null?e:!0},set(t,i){var r;let{media:e}=i;e&&(t?e.pause():(r=e.play())==null||r.catch(()=>{}))},mediaEvents:["play","playing","pause","emptied"]},mediaHasPlayed:{get(t,i){let{media:e}=t;return e?i?i.type==="playing":!e.paused:!1},mediaEvents:["playing","emptied"]},mediaEnded:{get(t){var e;let{media:i}=t;return(e=i==null?void 0:i.ended)!=null?e:!1},mediaEvents:["seeked","ended","emptied"]},mediaPlaybackRate:{get(t){var e;let{media:i}=t;return(e=i==null?void 0:i.playbackRate)!=null?e:1},set(t,i){let{media:e}=i;e&&Number.isFinite(+t)&&(e.playbackRate=+t)},mediaEvents:["ratechange","loadstart"]},mediaMuted:{get(t){var e;let{media:i}=t;return(e=i==null?void 0:i.muted)!=null?e:!1},set(t,i){let{media:e}=i;if(e){try{u.localStorage.setItem("media-chrome-pref-muted",t?"true":"false")}catch(r){console.debug("Error setting muted pref",r)}e.muted=t}},mediaEvents:["volumechange"],stateOwnersUpdateHandlers:[(t,i)=>{let{options:{noMutedPref:e}}=i,{media:r}=i;if(!(!r||r.muted||e))try{let n=u.localStorage.getItem("media-chrome-pref-muted")==="true";jt.mediaMuted.set(n,i),t(n)}catch(n){console.debug("Error getting muted pref",n)}}]},mediaVolume:{get(t){var e;let{media:i}=t;return(e=i==null?void 0:i.volume)!=null?e:1},set(t,i){let{media:e}=i;if(e){try{t==null?u.localStorage.removeItem("media-chrome-pref-volume"):u.localStorage.setItem("media-chrome-pref-volume",t.toString())}catch(r){console.debug("Error setting volume pref",r)}Number.isFinite(+t)&&(e.volume=+t)}},mediaEvents:["volumechange"],stateOwnersUpdateHandlers:[(t,i)=>{let{options:{noVolumePref:e}}=i;if(!e)try{let{media:r}=i;if(!r)return;let n=u.localStorage.getItem("media-chrome-pref-volume");if(n==null)return;jt.mediaVolume.set(+n,i),t(+n)}catch(r){console.debug("Error getting volume pref",r)}}]},mediaVolumeLevel:{get(t){let{media:i}=t;return typeof(i==null?void 0:i.volume)=="undefined"?"high":i.muted||i.volume===0?"off":i.volume<.5?"low":i.volume<.75?"medium":"high"},mediaEvents:["volumechange"]},mediaCurrentTime:{get(t){var e;let{media:i}=t;return(e=i==null?void 0:i.currentTime)!=null?e:0},set(t,i){let{media:e}=i;!e||!Vt(t)||(e.currentTime=t)},mediaEvents:["timeupdate","loadedmetadata"]},mediaDuration:{get(t){let{media:i,options:{defaultDuration:e}={}}=t;return e&&(!i||!i.duration||Number.isNaN(i.duration)||!Number.isFinite(i.duration))?e:Number.isFinite(i==null?void 0:i.duration)?i.duration:Number.NaN},mediaEvents:["durationchange","loadedmetadata","emptied"]},mediaLoading:{get(t){let{media:i}=t;return(i==null?void 0:i.readyState)<3},mediaEvents:["waiting","playing","emptied"]},mediaSeekable:{get(t){var n;let{media:i}=t;if(!((n=i==null?void 0:i.seekable)!=null&&n.length))return;let e=i.seekable.start(0),r=i.seekable.end(i.seekable.length-1);if(!(!e&&!r))return[Number(e.toFixed(3)),Number(r.toFixed(3))]},mediaEvents:["loadedmetadata","emptied","progress","seekablechange"]},mediaBuffered:{get(t){var r;let{media:i}=t,e=(r=i==null?void 0:i.buffered)!=null?r:[];return Array.from(e).map((n,o)=>[Number(e.start(o).toFixed(3)),Number(e.end(o).toFixed(3))])},mediaEvents:["progress","emptied"]},mediaStreamType:{get(t){let{media:i,options:{defaultStreamType:e}={}}=t,r=[ee.LIVE,ee.ON_DEMAND].includes(e)?e:void 0;if(!i)return r;let{streamType:n}=i;if(Au.includes(n))return n===ee.UNKNOWN?r:n;let o=i.duration;return o===1/0?ee.LIVE:Number.isFinite(o)?ee.ON_DEMAND:r},mediaEvents:["emptied","durationchange","loadedmetadata","streamtypechange"]},mediaTargetLiveWindow:{get(t){let{media:i}=t;if(!i)return Number.NaN;let{targetLiveWindow:e}=i,r=jt.mediaStreamType.get(t);return(e==null||Number.isNaN(e))&&r===ee.LIVE?0:e},mediaEvents:["emptied","durationchange","loadedmetadata","streamtypechange","targetlivewindowchange"]},mediaTimeIsLive:{get(t){let{media:i,options:{liveEdgeOffset:e=10}={}}=t;if(!i)return!1;if(typeof i.liveEdgeStart=="number")return Number.isNaN(i.liveEdgeStart)?!1:i.currentTime>=i.liveEdgeStart;if(!(jt.mediaStreamType.get(t)===ee.LIVE))return!1;let n=i.seekable;if(!n)return!0;if(!n.length)return!1;let o=n.end(n.length-1)-e;return i.currentTime>=o},mediaEvents:["playing","timeupdate","progress","waiting","emptied"]},mediaSubtitlesList:{get(t){return Yt(t).map(({kind:i,label:e,language:r})=>({kind:i,label:e,language:r}))},mediaEvents:["loadstart"],textTracksEvents:["addtrack","removetrack"]},mediaSubtitlesShowing:{get(t){return Qo(t).map(({kind:i,label:e,language:r})=>({kind:i,label:e,language:r}))},mediaEvents:["loadstart"],textTracksEvents:["addtrack","removetrack","change"],stateOwnersUpdateHandlers:[(t,i)=>{var o,l;let{media:e,options:r}=i;if(!e)return;let n=d=>{var E;!r.defaultSubtitles||d&&![W.CAPTIONS,W.SUBTITLES].includes((E=d==null?void 0:d.track)==null?void 0:E.kind)||wn(i,!0)};return e.addEventListener("loadstart",n),(o=e.textTracks)==null||o.addEventListener("addtrack",n),(l=e.textTracks)==null||l.addEventListener("removetrack",n),()=>{var d,m;e.removeEventListener("loadstart",n),(d=e.textTracks)==null||d.removeEventListener("addtrack",n),(m=e.textTracks)==null||m.removeEventListener("removetrack",n)}}]},mediaChaptersCues:{get(t){var r;let{media:i}=t;if(!i)return[];let[e]=dt(i,{kind:W.CHAPTERS});return Array.from((r=e==null?void 0:e.cues)!=null?r:[]).map(({text:n,startTime:o,endTime:l})=>({text:Iu(n),startTime:o,endTime:l}))},mediaEvents:["loadstart","loadedmetadata"],textTracksEvents:["addtrack","removetrack","change"],stateOwnersUpdateHandlers:[(t,i)=>{var o;let{media:e}=i;if(!e)return;let r=e.querySelector('track[kind="chapters"][default][src]'),n=(o=e.shadowRoot)==null?void 0:o.querySelector(':is(video,audio) > track[kind="chapters"][default][src]');return r==null||r.addEventListener("load",t),n==null||n.addEventListener("load",t),()=>{r==null||r.removeEventListener("load",t),n==null||n.removeEventListener("load",t)}}]},mediaIsPip:{get(t){var r,n;let{media:i,documentElement:e}=t;if(!i||!e||!e.pictureInPictureElement)return!1;if(e.pictureInPictureElement===i)return!0;if(e.pictureInPictureElement instanceof HTMLMediaElement)return(r=i.localName)!=null&&r.includes("-")?Y(i,e.pictureInPictureElement):!1;if(e.pictureInPictureElement.localName.includes("-")){let o=e.pictureInPictureElement.shadowRoot;for(;o!=null&&o.pictureInPictureElement;){if(o.pictureInPictureElement===i)return!0;o=(n=o.pictureInPictureElement)==null?void 0:n.shadowRoot}}return!1},set(t,i){let{media:e}=i;if(e)if(t){if(!F.pictureInPictureEnabled){console.warn("MediaChrome: Picture-in-picture is not enabled");return}if(!e.requestPictureInPicture){console.warn("MediaChrome: The current media does not support picture-in-picture");return}let r=()=>{console.warn("MediaChrome: The media is not ready for picture-in-picture. It must have a readyState > 0.")};e.requestPictureInPicture().catch(n=>{if(n.code===11){if(!e.src){console.warn("MediaChrome: The media is not ready for picture-in-picture. It must have a src set.");return}if(e.readyState===0&&e.preload==="none"){let o=()=>{e.removeEventListener("loadedmetadata",l),e.preload="none"},l=()=>{e.requestPictureInPicture().catch(r),o()};e.addEventListener("loadedmetadata",l),e.preload="metadata",setTimeout(()=>{e.readyState===0&&r(),o()},1e3)}else throw n}else throw n})}else F.pictureInPictureElement&&F.exitPictureInPicture()},mediaEvents:["enterpictureinpicture","leavepictureinpicture"]},mediaRenditionList:{get(t){var e;let{media:i}=t;return[...(e=i==null?void 0:i.videoRenditions)!=null?e:[]].map(r=>({...r}))},mediaEvents:["emptied","loadstart"],videoRenditionsEvents:["addrendition","removerendition"]},mediaRenditionSelected:{get(t){var e,r,n;let{media:i}=t;return(n=(r=i==null?void 0:i.videoRenditions)==null?void 0:r[(e=i.videoRenditions)==null?void 0:e.selectedIndex])==null?void 0:n.id},set(t,i){let{media:e}=i;if(!(e!=null&&e.videoRenditions)){console.warn("MediaController: Rendition selection not supported by this media.");return}let r=t,n=Array.prototype.findIndex.call(e.videoRenditions,o=>o.id==r);e.videoRenditions.selectedIndex!=n&&(e.videoRenditions.selectedIndex=n)},mediaEvents:["emptied"],videoRenditionsEvents:["addrendition","removerendition","change"]},mediaAudioTrackList:{get(t){var e;let{media:i}=t;return[...(e=i==null?void 0:i.audioTracks)!=null?e:[]]},mediaEvents:["emptied","loadstart"],audioTracksEvents:["addtrack","removetrack"]},mediaAudioTrackEnabled:{get(t){var e,r;let{media:i}=t;return(r=[...(e=i==null?void 0:i.audioTracks)!=null?e:[]].find(n=>n.enabled))==null?void 0:r.id},set(t,i){let{media:e}=i;if(!(e!=null&&e.audioTracks)){console.warn("MediaChrome: Audio track selection not supported by this media.");return}let r=t;for(let n of e.audioTracks)n.enabled=r==n.id},mediaEvents:["emptied"],audioTracksEvents:["addtrack","removetrack","change"]},mediaIsFullscreen:{get(t){return Oa(t)},set(t,i){t?Pa(i):Ua(i)},rootEvents:["fullscreenchange","webkitfullscreenchange"],mediaEvents:["webkitbeginfullscreen","webkitendfullscreen","webkitpresentationmodechanged"]},mediaIsCasting:{get(t){var e;let{media:i}=t;return!(i!=null&&i.remote)||((e=i.remote)==null?void 0:e.state)==="disconnected"?!1:!!i.remote.state},set(t,i){var r,n;let{media:e}=i;if(e&&!(t&&((r=e.remote)==null?void 0:r.state)!=="disconnected")&&!(!t&&((n=e.remote)==null?void 0:n.state)!=="connected")){if(typeof e.remote.prompt!="function"){console.warn("MediaChrome: Casting is not supported in this environment");return}e.remote.prompt().catch(()=>{})}},remoteEvents:["connect","connecting","disconnect"]},mediaIsAirplaying:{get(){return!1},set(t,i){let{media:e}=i;if(e){if(!(e.webkitShowPlaybackTargetPicker&&u.WebKitPlaybackTargetAvailabilityEvent)){console.error("MediaChrome: received a request to select AirPlay but AirPlay is not supported in this environment");return}e.webkitShowPlaybackTargetPicker()}},mediaEvents:["webkitcurrentplaybacktargetiswirelesschanged"]},mediaFullscreenUnavailable:{get(t){let{media:i}=t;if(!Fa||!jo(i))return Z.UNSUPPORTED}},mediaPipUnavailable:{get(t){let{media:i}=t;if(!$a||!Yo(i))return Z.UNSUPPORTED}},mediaVolumeUnavailable:{get(t){let{media:i}=t;if(Un===!1||(i==null?void 0:i.volume)==null)return Z.UNSUPPORTED},stateOwnersUpdateHandlers:[t=>{Un==null&&yu.then(i=>t(i?void 0:Z.UNSUPPORTED))}]},mediaCastUnavailable:{get(t,{availability:i="not-available"}={}){var r;let{media:e}=t;if(!Va||!((r=e==null?void 0:e.remote)!=null&&r.state))return Z.UNSUPPORTED;if(!(i==null||i==="available"))return Z.UNAVAILABLE},stateOwnersUpdateHandlers:[(t,i)=>{var n;let{media:e}=i;return e?(e.disableRemotePlayback||e.hasAttribute("disableremoteplayback")||(n=e==null?void 0:e.remote)==null||n.watchAvailability(o=>{t({availability:o?"available":"not-available"})}).catch(o=>{o.name==="NotSupportedError"?t({availability:null}):t({availability:"not-available"})}),()=>{var o;(o=e==null?void 0:e.remote)==null||o.cancelWatchAvailability().catch(()=>{})}):void 0}]},mediaAirplayUnavailable:{get(t,i){if(!Ba)return Z.UNSUPPORTED;if((i==null?void 0:i.availability)==="not-available")return Z.UNAVAILABLE},mediaEvents:["webkitplaybacktargetavailabilitychanged"],stateOwnersUpdateHandlers:[(t,i)=>{var n;let{media:e}=i;return e?(e.disableRemotePlayback||e.hasAttribute("disableremoteplayback")||(n=e==null?void 0:e.remote)==null||n.watchAvailability(o=>{t({availability:o?"available":"not-available"})}).catch(o=>{o.name==="NotSupportedError"?t({availability:null}):t({availability:"not-available"})}),()=>{var o;(o=e==null?void 0:e.remote)==null||o.cancelWatchAvailability().catch(()=>{})}):void 0}]},mediaRenditionUnavailable:{get(t){var e;let{media:i}=t;if(!(i!=null&&i.videoRenditions))return Z.UNSUPPORTED;if(!((e=i.videoRenditions)!=null&&e.length))return Z.UNAVAILABLE},mediaEvents:["emptied","loadstart"],videoRenditionsEvents:["addrendition","removerendition"]},mediaAudioTrackUnavailable:{get(t){var e,r;let{media:i}=t;if(!(i!=null&&i.audioTracks))return Z.UNSUPPORTED;if(((r=(e=i.audioTracks)==null?void 0:e.length)!=null?r:0)<=1)return Z.UNAVAILABLE},mediaEvents:["emptied","loadstart"],audioTracksEvents:["addtrack","removetrack"]}};var Ga={[b.MEDIA_PREVIEW_REQUEST](t,i,{detail:e}){var T,y,v;let{media:r}=i,n=e!=null?e:void 0,o,l;if(r&&n!=null){let[f]=dt(r,{kind:W.METADATA,label:"thumbnails"}),w=Array.prototype.find.call((T=f==null?void 0:f.cues)!=null?T:[],(x,D,U)=>D===0?x.endTime>n:D===U.length-1?x.startTime<=n:x.startTime<=n&&x.endTime>n);if(w){let x=/'^(?:[a-z]+:)?\/\//i.test(w.text)||(y=r==null?void 0:r.querySelector('track[label="thumbnails"]'))==null?void 0:y.src,D=new URL(w.text,x);l=new URLSearchParams(D.hash).get("#xywh").split(",").map(q=>+q),o=D.href}}let d=t.mediaDuration.get(i),E=(v=t.mediaChaptersCues.get(i).find((f,w,x)=>w===x.length-1&&d===f.endTime?f.startTime<=n&&f.endTime>=n:f.startTime<=n&&f.endTime>n))==null?void 0:v.text;return e!=null&&E==null&&(E=""),{mediaPreviewTime:n,mediaPreviewImage:o,mediaPreviewCoords:l,mediaPreviewChapter:E}},[b.MEDIA_PAUSE_REQUEST](t,i){t["mediaPaused"].set(!0,i)},[b.MEDIA_PLAY_REQUEST](t,i){var d,m,E,T;let e="mediaPaused",n=t.mediaStreamType.get(i)===ee.LIVE,o=!((d=i.options)!=null&&d.noAutoSeekToLive),l=t.mediaTargetLiveWindow.get(i)>0;if(n&&o&&!l){let y=(m=t.mediaSeekable.get(i))==null?void 0:m[1];if(y){let v=(T=(E=i.options)==null?void 0:E.seekToLiveOffset)!=null?T:0,f=y-v;t.mediaCurrentTime.set(f,i)}}t[e].set(!1,i)},[b.MEDIA_PLAYBACK_RATE_REQUEST](t,i,{detail:e}){let r="mediaPlaybackRate",n=e;t[r].set(n,i)},[b.MEDIA_MUTE_REQUEST](t,i){t["mediaMuted"].set(!0,i)},[b.MEDIA_UNMUTE_REQUEST](t,i){let e="mediaMuted";t.mediaVolume.get(i)||t.mediaVolume.set(.25,i),t[e].set(!1,i)},[b.MEDIA_VOLUME_REQUEST](t,i,{detail:e}){let r="mediaVolume",n=e;n&&t.mediaMuted.get(i)&&t.mediaMuted.set(!1,i),t[r].set(n,i)},[b.MEDIA_SEEK_REQUEST](t,i,{detail:e}){let r="mediaCurrentTime",n=e;t[r].set(n,i)},[b.MEDIA_SEEK_TO_LIVE_REQUEST](t,i){var l,d,m;let e="mediaCurrentTime",r=(l=t.mediaSeekable.get(i))==null?void 0:l[1];if(Number.isNaN(Number(r)))return;let n=(m=(d=i.options)==null?void 0:d.seekToLiveOffset)!=null?m:0,o=r-n;t[e].set(o,i)},[b.MEDIA_SHOW_SUBTITLES_REQUEST](t,i,{detail:e}){var d;let{options:r}=i,n=Yt(i),o=qo(e),l=(d=o[0])==null?void 0:d.language;l&&!r.noSubtitlesLangPref&&u.localStorage.setItem("media-chrome-pref-subtitles-lang",l),lt(Me.SHOWING,n,o)},[b.MEDIA_DISABLE_SUBTITLES_REQUEST](t,i,{detail:e}){let r=Yt(i),n=e!=null?e:[];lt(Me.DISABLED,r,n)},[b.MEDIA_TOGGLE_SUBTITLES_REQUEST](t,i,{detail:e}){wn(i,e)},[b.MEDIA_RENDITION_REQUEST](t,i,{detail:e}){let r="mediaRenditionSelected",n=e;t[r].set(n,i)},[b.MEDIA_AUDIO_TRACK_REQUEST](t,i,{detail:e}){let r="mediaAudioTrackEnabled",n=e;t[r].set(n,i)},[b.MEDIA_ENTER_PIP_REQUEST](t,i){let e="mediaIsPip";t.mediaIsFullscreen.get(i)&&t.mediaIsFullscreen.set(!1,i),t[e].set(!0,i)},[b.MEDIA_EXIT_PIP_REQUEST](t,i){t["mediaIsPip"].set(!1,i)},[b.MEDIA_ENTER_FULLSCREEN_REQUEST](t,i){let e="mediaIsFullscreen";t.mediaIsPip.get(i)&&t.mediaIsPip.set(!1,i),t[e].set(!0,i)},[b.MEDIA_EXIT_FULLSCREEN_REQUEST](t,i){t["mediaIsFullscreen"].set(!1,i)},[b.MEDIA_ENTER_CAST_REQUEST](t,i){let e="mediaIsCasting";t.mediaIsFullscreen.get(i)&&t.mediaIsFullscreen.set(!1,i),t[e].set(!0,i)},[b.MEDIA_EXIT_CAST_REQUEST](t,i){t["mediaIsCasting"].set(!1,i)},[b.MEDIA_AIRPLAY_REQUEST](t,i){t["mediaIsAirplaying"].set(!0,i)}};var qa=({media:t,fullscreenElement:i,documentElement:e,stateMediator:r=jt,requestMap:n=Ga,options:o={},monitorStateOwnersOnlyWithSubscriptions:l=!0})=>{let d=[],m={options:{...o}},E=Object.freeze({mediaPreviewTime:void 0,mediaPreviewImage:void 0,mediaPreviewCoords:void 0,mediaPreviewChapter:void 0}),T=x=>{x!=null&&(Pn(x,E)||(E=Object.freeze({...E,...x}),d.forEach(D=>D(E))))},y=()=>{let x=Object.entries(r).reduce((D,[U,{get:q}])=>(D[U]=q(m),D),{});T(x)},v={},f,w=async(x,D)=>{var qs,Ws,Ys,js,Qs,zs,Zs,Xs,Js,ea,ta,ia,ra,na,oa,sa;let U=!!f;if(f={...m,...f!=null?f:{},...x},U)return;await Ka(...Object.values(x));let q=d.length>0&&D===0&&l,Ie=m.media!==f.media,He=((qs=m.media)==null?void 0:qs.textTracks)!==((Ws=f.media)==null?void 0:Ws.textTracks),Ni=((Ys=m.media)==null?void 0:Ys.videoRenditions)!==((js=f.media)==null?void 0:js.videoRenditions),$t=((Qs=m.media)==null?void 0:Qs.audioTracks)!==((zs=f.media)==null?void 0:zs.audioTracks),Rs=((Zs=m.media)==null?void 0:Zs.remote)!==((Xs=f.media)==null?void 0:Xs.remote),xs=m.documentElement!==f.documentElement,Cs=!!m.media&&(Ie||q),Ds=!!((Js=m.media)!=null&&Js.textTracks)&&(He||q),ws=!!((ea=m.media)!=null&&ea.videoRenditions)&&(Ni||q),Ps=!!((ta=m.media)!=null&&ta.audioTracks)&&($t||q),Us=!!((ia=m.media)!=null&&ia.remote)&&(Rs||q),Os=!!m.documentElement&&(xs||q),Hs=Cs||Ds||ws||Ps||Us||Os,Bt=d.length===0&&D===1&&l,Ns=!!f.media&&(Ie||Bt),Fs=!!((ra=f.media)!=null&&ra.textTracks)&&(He||Bt),$s=!!((na=f.media)!=null&&na.videoRenditions)&&(Ni||Bt),Bs=!!((oa=f.media)!=null&&oa.audioTracks)&&($t||Bt),Vs=!!((sa=f.media)!=null&&sa.remote)&&(Rs||Bt),Ks=!!f.documentElement&&(xs||Bt),Gs=Ns||Fs||$s||Bs||Vs||Ks;if(!(Hs||Gs)){Object.entries(f).forEach(([N,Fi])=>{m[N]=Fi}),y(),f=void 0;return}Object.entries(r).forEach(([N,{get:Fi,mediaEvents:Pd=[],textTracksEvents:Ud=[],videoRenditionsEvents:Od=[],audioTracksEvents:Hd=[],remoteEvents:Nd=[],rootEvents:Fd=[],stateOwnersUpdateHandlers:$d=[]}])=>{v[N]||(v[N]={});let X=V=>{let J=Fi(m,V);T({[N]:J})},G;G=v[N].mediaEvents,Pd.forEach(V=>{G&&Cs&&(m.media.removeEventListener(V,G),v[N].mediaEvents=void 0),Ns&&(f.media.addEventListener(V,X),v[N].mediaEvents=X)}),G=v[N].textTracksEvents,Ud.forEach(V=>{var J,oe;G&&Ds&&((J=m.media.textTracks)==null||J.removeEventListener(V,G),v[N].textTracksEvents=void 0),Fs&&((oe=f.media.textTracks)==null||oe.addEventListener(V,X),v[N].textTracksEvents=X)}),G=v[N].videoRenditionsEvents,Od.forEach(V=>{var J,oe;G&&ws&&((J=m.media.videoRenditions)==null||J.removeEventListener(V,G),v[N].videoRenditionsEvents=void 0),$s&&((oe=f.media.videoRenditions)==null||oe.addEventListener(V,X),v[N].videoRenditionsEvents=X)}),G=v[N].audioTracksEvents,Hd.forEach(V=>{var J,oe;G&&Ps&&((J=m.media.audioTracks)==null||J.removeEventListener(V,G),v[N].audioTracksEvents=void 0),Bs&&((oe=f.media.audioTracks)==null||oe.addEventListener(V,X),v[N].audioTracksEvents=X)}),G=v[N].remoteEvents,Nd.forEach(V=>{var J,oe;G&&Us&&((J=m.media.remote)==null||J.removeEventListener(V,G),v[N].remoteEvents=void 0),Vs&&((oe=f.media.remote)==null||oe.addEventListener(V,X),v[N].remoteEvents=X)}),G=v[N].rootEvents,Fd.forEach(V=>{G&&Os&&(m.documentElement.removeEventListener(V,G),v[N].rootEvents=void 0),Ks&&(f.documentElement.addEventListener(V,X),v[N].rootEvents=X)});let aa=v[N].stateOwnersUpdateHandlers;$d.forEach(V=>{aa&&Hs&&aa(),Gs&&(v[N].stateOwnersUpdateHandlers=V(X,f))})}),Object.entries(f).forEach(([N,Fi])=>{m[N]=Fi}),y(),f=void 0};return w({media:t,fullscreenElement:i,documentElement:e,options:o}),{dispatch(x){let{type:D,detail:U}=x;if(n[D]&&E.mediaErrorCode==null){T(n[D](r,m,x));return}D==="mediaelementchangerequest"?w({media:U}):D==="fullscreenelementchangerequest"?w({fullscreenElement:U}):D==="documentelementchangerequest"?w({documentElement:U}):D==="optionschangerequest"&&Object.entries(U!=null?U:{}).forEach(([q,Ie])=>{m.options[q]=Ie})},getState(){return E},subscribe(x){return w({},d.length+1),d.push(x),x(E),()=>{let D=d.indexOf(x);D>=0&&(w({},d.length-1),d.splice(D,1))}}}};var Wa=["ArrowLeft","ArrowRight","Enter"," ","f","m","k","c"],Ya=10,A={DEFAULT_SUBTITLES:"defaultsubtitles",DEFAULT_STREAM_TYPE:"defaultstreamtype",DEFAULT_DURATION:"defaultduration",FULLSCREEN_ELEMENT:"fullscreenelement",HOTKEYS:"hotkeys",KEYS_USED:"keysused",LIVE_EDGE_OFFSET:"liveedgeoffset",SEEK_TO_LIVE_OFFSET:"seektoliveoffset",NO_AUTO_SEEK_TO_LIVE:"noautoseektolive",NO_HOTKEYS:"nohotkeys",NO_VOLUME_PREF:"novolumepref",NO_SUBTITLES_LANG_PREF:"nosubtitleslangpref",NO_DEFAULT_STORE:"nodefaultstore",KEYBOARD_FORWARD_SEEK_OFFSET:"keyboardforwardseekoffset",KEYBOARD_BACKWARD_SEEK_OFFSET:"keyboardbackwardseekoffset",LANG:"lang"},Ke,Qt,H,zt,se,Zi,Xi,Zo,ct,zi,Ji,Xo,On=class extends Fe{constructor(){super();c(this,Xi);c(this,ct);c(this,Ji);this.mediaStateReceivers=[];this.associatedElementSubscriptions=new Map;c(this,Ke,new Ve(this,A.HOTKEYS));c(this,Qt,void 0);c(this,H,void 0);c(this,zt,void 0);c(this,se,void 0);c(this,Zi,e=>{var r;(r=s(this,H))==null||r.dispatch(e)});this.associateElement(this);let e={};h(this,zt,r=>{Object.entries(r).forEach(([n,o])=>{if(n in e&&e[n]===o)return;this.propagateMediaState(n,o);let l=n.toLowerCase(),d=new u.CustomEvent(Ho[l],{composed:!0,detail:o});this.dispatchEvent(d)}),e=r}),this.enableHotkeys()}static get observedAttributes(){return super.observedAttributes.concat(A.NO_HOTKEYS,A.HOTKEYS,A.DEFAULT_STREAM_TYPE,A.DEFAULT_SUBTITLES,A.DEFAULT_DURATION,A.LANG)}get mediaStore(){return s(this,H)}set mediaStore(e){var r,n;if(s(this,H)&&((r=s(this,se))==null||r.call(this),h(this,se,void 0)),h(this,H,e),!s(this,H)&&!this.hasAttribute(A.NO_DEFAULT_STORE)){p(this,Xi,Zo).call(this);return}h(this,se,(n=s(this,H))==null?void 0:n.subscribe(s(this,zt)))}get fullscreenElement(){var e;return(e=s(this,Qt))!=null?e:this}set fullscreenElement(e){var r;this.hasAttribute(A.FULLSCREEN_ELEMENT)&&this.removeAttribute(A.FULLSCREEN_ELEMENT),h(this,Qt,e),(r=s(this,H))==null||r.dispatch({type:"fullscreenelementchangerequest",detail:this.fullscreenElement})}get defaultSubtitles(){return I(this,A.DEFAULT_SUBTITLES)}set defaultSubtitles(e){M(this,A.DEFAULT_SUBTITLES,e)}get defaultStreamType(){return L(this,A.DEFAULT_STREAM_TYPE)}set defaultStreamType(e){k(this,A.DEFAULT_STREAM_TYPE,e)}get defaultDuration(){return R(this,A.DEFAULT_DURATION)}set defaultDuration(e){C(this,A.DEFAULT_DURATION,e)}get noHotkeys(){return I(this,A.NO_HOTKEYS)}set noHotkeys(e){M(this,A.NO_HOTKEYS,e)}get keysUsed(){return L(this,A.KEYS_USED)}set keysUsed(e){k(this,A.KEYS_USED,e)}get liveEdgeOffset(){return R(this,A.LIVE_EDGE_OFFSET)}set liveEdgeOffset(e){C(this,A.LIVE_EDGE_OFFSET,e)}get noAutoSeekToLive(){return I(this,A.NO_AUTO_SEEK_TO_LIVE)}set noAutoSeekToLive(e){M(this,A.NO_AUTO_SEEK_TO_LIVE,e)}get noVolumePref(){return I(this,A.NO_VOLUME_PREF)}set noVolumePref(e){M(this,A.NO_VOLUME_PREF,e)}get noSubtitlesLangPref(){return I(this,A.NO_SUBTITLES_LANG_PREF)}set noSubtitlesLangPref(e){M(this,A.NO_SUBTITLES_LANG_PREF,e)}get noDefaultStore(){return I(this,A.NO_DEFAULT_STORE)}set noDefaultStore(e){M(this,A.NO_DEFAULT_STORE,e)}attributeChangedCallback(e,r,n){var o,l,d,m,E,T,y,v;if(super.attributeChangedCallback(e,r,n),e===A.NO_HOTKEYS)n!==r&&n===""?(this.hasAttribute(A.HOTKEYS)&&console.warn("Media Chrome: Both `hotkeys` and `nohotkeys` have been set. All hotkeys will be disabled."),this.disableHotkeys()):n!==r&&n===null&&this.enableHotkeys();else if(e===A.HOTKEYS)s(this,Ke).value=n;else if(e===A.DEFAULT_SUBTITLES&&n!==r)(o=s(this,H))==null||o.dispatch({type:"optionschangerequest",detail:{defaultSubtitles:this.hasAttribute(A.DEFAULT_SUBTITLES)}});else if(e===A.DEFAULT_STREAM_TYPE)(d=s(this,H))==null||d.dispatch({type:"optionschangerequest",detail:{defaultStreamType:(l=this.getAttribute(A.DEFAULT_STREAM_TYPE))!=null?l:void 0}});else if(e===A.LIVE_EDGE_OFFSET)(m=s(this,H))==null||m.dispatch({type:"optionschangerequest",detail:{liveEdgeOffset:this.hasAttribute(A.LIVE_EDGE_OFFSET)?+this.getAttribute(A.LIVE_EDGE_OFFSET):void 0,seekToLiveOffset:this.hasAttribute(A.SEEK_TO_LIVE_OFFSET)?void 0:+this.getAttribute(A.LIVE_EDGE_OFFSET)}});else if(e===A.SEEK_TO_LIVE_OFFSET)(E=s(this,H))==null||E.dispatch({type:"optionschangerequest",detail:{seekToLiveOffset:this.hasAttribute(A.SEEK_TO_LIVE_OFFSET)?+this.getAttribute(A.SEEK_TO_LIVE_OFFSET):void 0}});else if(e===A.NO_AUTO_SEEK_TO_LIVE)(T=s(this,H))==null||T.dispatch({type:"optionschangerequest",detail:{noAutoSeekToLive:this.hasAttribute(A.NO_AUTO_SEEK_TO_LIVE)}});else if(e===A.FULLSCREEN_ELEMENT){let f=n?(y=this.getRootNode())==null?void 0:y.getElementById(n):void 0;h(this,Qt,f),(v=s(this,H))==null||v.dispatch({type:"fullscreenelementchangerequest",detail:this.fullscreenElement})}else e===A.LANG&&n!==r&&va(n)}connectedCallback(){var e,r;!s(this,H)&&!this.hasAttribute(A.NO_DEFAULT_STORE)&&p(this,Xi,Zo).call(this),(e=s(this,H))==null||e.dispatch({type:"documentelementchangerequest",detail:F}),super.connectedCallback(),s(this,H)&&!s(this,se)&&h(this,se,(r=s(this,H))==null?void 0:r.subscribe(s(this,zt))),this.enableHotkeys()}disconnectedCallback(){var e,r,n,o;(e=super.disconnectedCallback)==null||e.call(this),s(this,H)&&((r=s(this,H))==null||r.dispatch({type:"documentelementchangerequest",detail:void 0}),(n=s(this,H))==null||n.dispatch({type:b.MEDIA_TOGGLE_SUBTITLES_REQUEST,detail:!1})),s(this,se)&&((o=s(this,se))==null||o.call(this),h(this,se,void 0))}mediaSetCallback(e){var r;super.mediaSetCallback(e),(r=s(this,H))==null||r.dispatch({type:"mediaelementchangerequest",detail:e}),e.hasAttribute("tabindex")||(e.tabIndex=-1)}mediaUnsetCallback(e){var r;super.mediaUnsetCallback(e),(r=s(this,H))==null||r.dispatch({type:"mediaelementchangerequest",detail:void 0})}propagateMediaState(e,r){Qa(this.mediaStateReceivers,e,r)}associateElement(e){if(!e)return;let{associatedElementSubscriptions:r}=this;if(r.has(e))return;let n=this.registerMediaStateReceiver.bind(this),o=this.unregisterMediaStateReceiver.bind(this),l=xu(e,n,o);Object.values(b).forEach(d=>{e.addEventListener(d,s(this,Zi))}),r.set(e,l)}unassociateElement(e){if(!e)return;let{associatedElementSubscriptions:r}=this;if(!r.has(e))return;r.get(e)(),r.delete(e),Object.values(b).forEach(o=>{e.removeEventListener(o,s(this,Zi))})}registerMediaStateReceiver(e){if(!e)return;let r=this.mediaStateReceivers;r.indexOf(e)>-1||(r.push(e),s(this,H)&&Object.entries(s(this,H).getState()).forEach(([o,l])=>{Qa([e],o,l)}))}unregisterMediaStateReceiver(e){let r=this.mediaStateReceivers,n=r.indexOf(e);n<0||r.splice(n,1)}enableHotkeys(){this.addEventListener("keydown",p(this,Ji,Xo))}disableHotkeys(){this.removeEventListener("keydown",p(this,Ji,Xo)),this.removeEventListener("keyup",p(this,ct,zi))}get hotkeys(){return L(this,A.HOTKEYS)}set hotkeys(e){k(this,A.HOTKEYS,e)}keyboardShortcutHandler(e){var m,E,T,y,v;let r=e.target;if(((T=(E=(m=r.getAttribute(A.KEYS_USED))==null?void 0:m.split(" "))!=null?E:r==null?void 0:r.keysUsed)!=null?T:[]).map(f=>f==="Space"?" ":f).filter(Boolean).includes(e.key))return;let o,l,d;if(!s(this,Ke).contains(`no${e.key.toLowerCase()}`)&&!(e.key===" "&&s(this,Ke).contains("nospace")))switch(e.key){case" ":case"k":o=s(this,H).getState().mediaPaused?b.MEDIA_PLAY_REQUEST:b.MEDIA_PAUSE_REQUEST,this.dispatchEvent(new u.CustomEvent(o,{composed:!0,bubbles:!0}));break;case"m":o=this.mediaStore.getState().mediaVolumeLevel==="off"?b.MEDIA_UNMUTE_REQUEST:b.MEDIA_MUTE_REQUEST,this.dispatchEvent(new u.CustomEvent(o,{composed:!0,bubbles:!0}));break;case"f":o=this.mediaStore.getState().mediaIsFullscreen?b.MEDIA_EXIT_FULLSCREEN_REQUEST:b.MEDIA_ENTER_FULLSCREEN_REQUEST,this.dispatchEvent(new u.CustomEvent(o,{composed:!0,bubbles:!0}));break;case"c":this.dispatchEvent(new u.CustomEvent(b.MEDIA_TOGGLE_SUBTITLES_REQUEST,{composed:!0,bubbles:!0}));break;case"ArrowLeft":{let f=this.hasAttribute(A.KEYBOARD_BACKWARD_SEEK_OFFSET)?+this.getAttribute(A.KEYBOARD_BACKWARD_SEEK_OFFSET):Ya;l=Math.max(((y=this.mediaStore.getState().mediaCurrentTime)!=null?y:0)-f,0),d=new u.CustomEvent(b.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:l}),this.dispatchEvent(d);break}case"ArrowRight":{let f=this.hasAttribute(A.KEYBOARD_FORWARD_SEEK_OFFSET)?+this.getAttribute(A.KEYBOARD_FORWARD_SEEK_OFFSET):Ya;l=Math.max(((v=this.mediaStore.getState().mediaCurrentTime)!=null?v:0)+f,0),d=new u.CustomEvent(b.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:l}),this.dispatchEvent(d);break}default:break}}};Ke=new WeakMap,Qt=new WeakMap,H=new WeakMap,zt=new WeakMap,se=new WeakMap,Zi=new WeakMap,Xi=new WeakSet,Zo=function(){var e;this.mediaStore=qa({media:this.media,fullscreenElement:this.fullscreenElement,options:{defaultSubtitles:this.hasAttribute(A.DEFAULT_SUBTITLES),defaultDuration:this.hasAttribute(A.DEFAULT_DURATION)?+this.getAttribute(A.DEFAULT_DURATION):void 0,defaultStreamType:(e=this.getAttribute(A.DEFAULT_STREAM_TYPE))!=null?e:void 0,liveEdgeOffset:this.hasAttribute(A.LIVE_EDGE_OFFSET)?+this.getAttribute(A.LIVE_EDGE_OFFSET):void 0,seekToLiveOffset:this.hasAttribute(A.SEEK_TO_LIVE_OFFSET)?+this.getAttribute(A.SEEK_TO_LIVE_OFFSET):this.hasAttribute(A.LIVE_EDGE_OFFSET)?+this.getAttribute(A.LIVE_EDGE_OFFSET):void 0,noAutoSeekToLive:this.hasAttribute(A.NO_AUTO_SEEK_TO_LIVE),noVolumePref:this.hasAttribute(A.NO_VOLUME_PREF),noSubtitlesLangPref:this.hasAttribute(A.NO_SUBTITLES_LANG_PREF)}})},ct=new WeakSet,zi=function(e){let{key:r}=e;if(!Wa.includes(r)){this.removeEventListener("keyup",p(this,ct,zi));return}this.keyboardShortcutHandler(e)},Ji=new WeakSet,Xo=function(e){let{metaKey:r,altKey:n,key:o}=e;if(r||n||!Wa.includes(o)){this.removeEventListener("keyup",p(this,ct,zi));return}[" ","ArrowLeft","ArrowRight"].includes(o)&&!(s(this,Ke).contains(`no${o.toLowerCase()}`)||o===" "&&s(this,Ke).contains("nospace"))&&e.preventDefault(),this.addEventListener("keyup",p(this,ct,zi),{once:!0})};var Mu=Object.values(a),Lu=Object.values(hn),za=t=>{var r,n,o,l;let{observedAttributes:i}=t.constructor;!i&&((r=t.nodeName)!=null&&r.includes("-"))&&(u.customElements.upgrade(t),{observedAttributes:i}=t.constructor);let e=(l=(o=(n=t==null?void 0:t.getAttribute)==null?void 0:n.call(t,_.MEDIA_CHROME_ATTRIBUTES))==null?void 0:o.split)==null?void 0:l.call(o,/\s+/);return Array.isArray(i||e)?(i||e).filter(d=>Mu.includes(d)):[]},ku=t=>{var i,e;return(i=t.nodeName)!=null&&i.includes("-")&&u.customElements.get((e=t.nodeName)==null?void 0:e.toLowerCase())&&!(t instanceof u.customElements.get(t.nodeName.toLowerCase()))&&u.customElements.upgrade(t),Lu.some(r=>r in t)},Jo=t=>ku(t)||!!za(t).length,ja=t=>{var i;return(i=t==null?void 0:t.join)==null?void 0:i.call(t,":")},zo={[a.MEDIA_SUBTITLES_LIST]:_e,[a.MEDIA_SUBTITLES_SHOWING]:_e,[a.MEDIA_SEEKABLE]:ja,[a.MEDIA_BUFFERED]:t=>t==null?void 0:t.map(ja).join(" "),[a.MEDIA_PREVIEW_COORDS]:t=>t==null?void 0:t.join(" "),[a.MEDIA_RENDITION_LIST]:ua,[a.MEDIA_AUDIO_TRACK_LIST]:ma},_u=async(t,i,e)=>{var n,o;if(t.isConnected||await bn(0),typeof e=="boolean"||e==null)return M(t,i,e);if(typeof e=="number")return C(t,i,e);if(typeof e=="string")return k(t,i,e);if(Array.isArray(e)&&!e.length)return t.removeAttribute(i);let r=(o=(n=zo[i])==null?void 0:n.call(zo,e))!=null?o:e;return t.setAttribute(i,r)},Ru=t=>{var i;return!!((i=t.closest)!=null&&i.call(t,'*[slot="media"]'))},ut=(t,i)=>{if(Ru(t))return;let e=(n,o)=>{var E,T;Jo(n)&&o(n);let{children:l=[]}=n!=null?n:{},d=(T=(E=n==null?void 0:n.shadowRoot)==null?void 0:E.children)!=null?T:[];[...l,...d].forEach(y=>ut(y,o))},r=t==null?void 0:t.nodeName.toLowerCase();if(r.includes("-")&&!Jo(t)){u.customElements.whenDefined(r).then(()=>{e(t,i)});return}e(t,i)},Qa=(t,i,e)=>{t.forEach(r=>{if(i in r){r[i]=e;return}let n=za(r),o=i.toLowerCase();n.includes(o)&&_u(r,o,e)})},xu=(t,i,e)=>{ut(t,i);let r=T=>{var v;let y=(v=T==null?void 0:T.composedPath()[0])!=null?v:T.target;i(y)},n=T=>{var v;let y=(v=T==null?void 0:T.composedPath()[0])!=null?v:T.target;e(y)};t.addEventListener(b.REGISTER_MEDIA_STATE_RECEIVER,r),t.addEventListener(b.UNREGISTER_MEDIA_STATE_RECEIVER,n);let o=T=>{T.forEach(y=>{let{addedNodes:v=[],removedNodes:f=[],type:w,target:x,attributeName:D}=y;w==="childList"?(Array.prototype.forEach.call(v,U=>ut(U,i)),Array.prototype.forEach.call(f,U=>ut(U,e))):w==="attributes"&&D===_.MEDIA_CHROME_ATTRIBUTES&&(Jo(x)?i(x):e(x))})},l=[],d=T=>{let y=T.target;y.name!=="media"&&(l.forEach(v=>ut(v,e)),l=[...y.assignedElements({flatten:!0})],l.forEach(v=>ut(v,i)))};t.addEventListener("slotchange",d);let m=new MutationObserver(o);return m.observe(t,{childList:!0,attributes:!0,subtree:!0}),()=>{ut(t,e),t.removeEventListener("slotchange",d),m.disconnect(),t.removeEventListener(b.REGISTER_MEDIA_STATE_RECEIVER,r),t.removeEventListener(b.UNREGISTER_MEDIA_STATE_RECEIVER,n)}};u.customElements.get("media-controller")||u.customElements.define("media-controller",On);var Za=On;var Zt={PLACEMENT:"placement",BOUNDS:"bounds"};function Cu(t){return`
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
  `}var Xt=class extends u.HTMLElement{constructor(){super();this.updateXOffset=()=>{var U;if(!Mn(this,{checkOpacity:!1,checkVisibilityCSS:!1}))return;let e=this.placement;if(e==="left"||e==="right"){this.style.removeProperty("--media-tooltip-offset-x");return}let r=getComputedStyle(this),n=(U=Te(this,"#"+this.bounds))!=null?U:B(this);if(!n)return;let{x:o,width:l}=n.getBoundingClientRect(),{x:d,width:m}=this.getBoundingClientRect(),E=d+m,T=o+l,y=r.getPropertyValue("--media-tooltip-offset-x"),v=y?parseFloat(y.replace("px","")):0,f=r.getPropertyValue("--media-tooltip-container-margin"),w=f?parseFloat(f.replace("px","")):0,x=d-o+v-w,D=E-T+v+w;if(x<0){this.style.setProperty("--media-tooltip-offset-x",`${x}px`);return}if(D>0){this.style.setProperty("--media-tooltip-offset-x",`${D}px`);return}this.style.removeProperty("--media-tooltip-offset-x")};if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}if(this.arrowEl=this.shadowRoot.querySelector("#arrow"),Object.prototype.hasOwnProperty.call(this,"placement")){let e=this.placement;delete this.placement,this.placement=e}}static get observedAttributes(){return[Zt.PLACEMENT,Zt.BOUNDS]}get placement(){return L(this,Zt.PLACEMENT)}set placement(e){k(this,Zt.PLACEMENT,e)}get bounds(){return L(this,Zt.BOUNDS)}set bounds(e){k(this,Zt.BOUNDS,e)}};Xt.shadowRootOptions={mode:"open"},Xt.getTemplateHTML=Cu;u.customElements.get("media-tooltip")||u.customElements.define("media-tooltip",Xt);var er=Xt;var Ge={TOOLTIP_PLACEMENT:"tooltipplacement",DISABLED:"disabled",NO_TOOLTIP:"notooltip"};function Du(t,i={}){return`
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

    ${this.getSlotTemplateHTML(t,i)}

    <slot name="tooltip">
      <media-tooltip part="tooltip" aria-hidden="true">
        <template shadowrootmode="${er.shadowRootOptions.mode}">
          ${er.getTemplateHTML({})}
        </template>
        <slot name="tooltip-content">
          ${this.getTooltipContentHTML(t)}
        </slot>
      </media-tooltip>
    </slot>
  `}function wu(t,i){return`
    <slot></slot>
  `}function Pu(){return""}var ae,mt,Re,ht,tr,Hn,Xa,P=class extends u.HTMLElement{constructor(){super();c(this,Hn);c(this,ae,void 0);this.preventClick=!1;this.tooltipEl=null;c(this,mt,e=>{this.preventClick||this.handleClick(e),setTimeout(s(this,Re),0)});c(this,Re,()=>{var e,r;(r=(e=this.tooltipEl)==null?void 0:e.updateXOffset)==null||r.call(e)});c(this,ht,e=>{let{key:r}=e;if(!this.keysUsed.includes(r)){this.removeEventListener("keyup",s(this,ht));return}this.preventClick||this.handleClick(e)});c(this,tr,e=>{let{metaKey:r,altKey:n,key:o}=e;if(r||n||!this.keysUsed.includes(o)){this.removeEventListener("keyup",s(this,ht));return}this.addEventListener("keyup",s(this,ht),{once:!0})});if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes),r=this.constructor.getTemplateHTML(e);this.shadowRoot.setHTMLUnsafe?this.shadowRoot.setHTMLUnsafe(r):this.shadowRoot.innerHTML=r}this.tooltipEl=this.shadowRoot.querySelector("media-tooltip")}static get observedAttributes(){return["disabled",Ge.TOOLTIP_PLACEMENT,_.MEDIA_CONTROLLER]}enable(){this.addEventListener("click",s(this,mt)),this.addEventListener("keydown",s(this,tr)),this.tabIndex=0}disable(){this.removeEventListener("click",s(this,mt)),this.removeEventListener("keydown",s(this,tr)),this.removeEventListener("keyup",s(this,ht)),this.tabIndex=-1}attributeChangedCallback(e,r,n){var o,l,d,m,E;e===_.MEDIA_CONTROLLER?(r&&((l=(o=s(this,ae))==null?void 0:o.unassociateElement)==null||l.call(o,this),h(this,ae,null)),n&&this.isConnected&&(h(this,ae,(d=this.getRootNode())==null?void 0:d.getElementById(n)),(E=(m=s(this,ae))==null?void 0:m.associateElement)==null||E.call(m,this))):e==="disabled"&&n!==r?n==null?this.enable():this.disable():e===Ge.TOOLTIP_PLACEMENT&&this.tooltipEl&&n!==r&&(this.tooltipEl.placement=n),s(this,Re).call(this)}connectedCallback(){var n,o,l;let{style:e}=O(this.shadowRoot,":host");e.setProperty("display",`var(--media-control-display, var(--${this.localName}-display, inline-flex))`),this.hasAttribute("disabled")?this.disable():this.enable(),this.setAttribute("role","button");let r=this.getAttribute(_.MEDIA_CONTROLLER);r&&(h(this,ae,(n=this.getRootNode())==null?void 0:n.getElementById(r)),(l=(o=s(this,ae))==null?void 0:o.associateElement)==null||l.call(o,this)),u.customElements.whenDefined("media-tooltip").then(()=>p(this,Hn,Xa).call(this))}disconnectedCallback(){var e,r;this.disable(),(r=(e=s(this,ae))==null?void 0:e.unassociateElement)==null||r.call(e,this),h(this,ae,null),this.removeEventListener("mouseenter",s(this,Re)),this.removeEventListener("focus",s(this,Re)),this.removeEventListener("click",s(this,mt))}get keysUsed(){return["Enter"," "]}get tooltipPlacement(){return L(this,Ge.TOOLTIP_PLACEMENT)}set tooltipPlacement(e){k(this,Ge.TOOLTIP_PLACEMENT,e)}get mediaController(){return L(this,_.MEDIA_CONTROLLER)}set mediaController(e){k(this,_.MEDIA_CONTROLLER,e)}get disabled(){return I(this,Ge.DISABLED)}set disabled(e){M(this,Ge.DISABLED,e)}get noTooltip(){return I(this,Ge.NO_TOOLTIP)}set noTooltip(e){M(this,Ge.NO_TOOLTIP,e)}handleClick(e){}};ae=new WeakMap,mt=new WeakMap,Re=new WeakMap,ht=new WeakMap,tr=new WeakMap,Hn=new WeakSet,Xa=function(){this.addEventListener("mouseenter",s(this,Re)),this.addEventListener("focus",s(this,Re)),this.addEventListener("click",s(this,mt));let e=this.tooltipPlacement;e&&this.tooltipEl&&(this.tooltipEl.placement=e)},P.shadowRootOptions={mode:"open"},P.getTemplateHTML=Du,P.getSlotTemplateHTML=wu,P.getTooltipContentHTML=Pu;u.customElements.get("media-chrome-button")||u.customElements.define("media-chrome-button",P);var Ja=P;var el=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M22.13 3H3.87a.87.87 0 0 0-.87.87v13.26a.87.87 0 0 0 .87.87h3.4L9 16H5V5h16v11h-4l1.72 2h3.4a.87.87 0 0 0 .87-.87V3.87a.87.87 0 0 0-.86-.87Zm-8.75 11.44a.5.5 0 0 0-.76 0l-4.91 5.73a.5.5 0 0 0 .38.83h9.82a.501.501 0 0 0 .38-.83l-4.91-5.73Z"/>
</svg>
`;function Uu(t){return`
    <style>
      :host([${a.MEDIA_IS_AIRPLAYING}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      
      :host(:not([${a.MEDIA_IS_AIRPLAYING}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${a.MEDIA_IS_AIRPLAYING}]) slot[name=tooltip-enter],
      :host(:not([${a.MEDIA_IS_AIRPLAYING}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${el}</slot>
      <slot name="exit">${el}</slot>
    </slot>
  `}function Ou(){return`
    <slot name="tooltip-enter">${g("start airplay")}</slot>
    <slot name="tooltip-exit">${g("stop airplay")}</slot>
  `}var tl=t=>{let i=t.mediaIsAirplaying?g("stop airplay"):g("start airplay");t.setAttribute("aria-label",i)},Jt=class extends P{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_IS_AIRPLAYING,a.MEDIA_AIRPLAY_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),tl(this)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===a.MEDIA_IS_AIRPLAYING&&tl(this)}get mediaIsAirplaying(){return I(this,a.MEDIA_IS_AIRPLAYING)}set mediaIsAirplaying(i){M(this,a.MEDIA_IS_AIRPLAYING,i)}get mediaAirplayUnavailable(){return L(this,a.MEDIA_AIRPLAY_UNAVAILABLE)}set mediaAirplayUnavailable(i){k(this,a.MEDIA_AIRPLAY_UNAVAILABLE,i)}handleClick(){let i=new u.CustomEvent(b.MEDIA_AIRPLAY_REQUEST,{composed:!0,bubbles:!0});this.dispatchEvent(i)}};Jt.getSlotTemplateHTML=Uu,Jt.getTooltipContentHTML=Ou;u.customElements.get("media-airplay-button")||u.customElements.define("media-airplay-button",Jt);var il=Jt;var Hu=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M22.83 5.68a2.58 2.58 0 0 0-2.3-2.5c-3.62-.24-11.44-.24-15.06 0a2.58 2.58 0 0 0-2.3 2.5c-.23 4.21-.23 8.43 0 12.64a2.58 2.58 0 0 0 2.3 2.5c3.62.24 11.44.24 15.06 0a2.58 2.58 0 0 0 2.3-2.5c.23-4.21.23-8.43 0-12.64Zm-11.39 9.45a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.92 3.92 0 0 1 .92-2.77 3.18 3.18 0 0 1 2.43-1 2.94 2.94 0 0 1 2.13.78c.364.359.62.813.74 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.17 1.61 1.61 0 0 0-1.29.58 2.79 2.79 0 0 0-.5 1.89 3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.48 1.48 0 0 0 1-.37 2.1 2.1 0 0 0 .59-1.14l1.4.44a3.23 3.23 0 0 1-1.07 1.69Zm7.22 0a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.88 3.88 0 0 1 .93-2.77 3.14 3.14 0 0 1 2.42-1 3 3 0 0 1 2.16.82 2.8 2.8 0 0 1 .73 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.21 1.61 1.61 0 0 0-1.29.58A2.79 2.79 0 0 0 15 12a3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.44 1.44 0 0 0 1-.37 2.1 2.1 0 0 0 .6-1.15l1.4.44a3.17 3.17 0 0 1-1.1 1.7Z"/>
</svg>`,Nu=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M17.73 14.09a1.4 1.4 0 0 1-1 .37 1.579 1.579 0 0 1-1.27-.58A3 3 0 0 1 15 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34A2.89 2.89 0 0 0 19 9.07a3 3 0 0 0-2.14-.78 3.14 3.14 0 0 0-2.42 1 3.91 3.91 0 0 0-.93 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.17 3.17 0 0 0 1.07-1.74l-1.4-.45c-.083.43-.3.822-.62 1.12Zm-7.22 0a1.43 1.43 0 0 1-1 .37 1.58 1.58 0 0 1-1.27-.58A3 3 0 0 1 7.76 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34a2.81 2.81 0 0 0-.74-1.32 2.94 2.94 0 0 0-2.13-.78 3.18 3.18 0 0 0-2.43 1 4 4 0 0 0-.92 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.23 3.23 0 0 0 1.07-1.74l-1.4-.45a2.06 2.06 0 0 1-.6 1.07Zm12.32-8.41a2.59 2.59 0 0 0-2.3-2.51C18.72 3.05 15.86 3 13 3c-2.86 0-5.72.05-7.53.17a2.59 2.59 0 0 0-2.3 2.51c-.23 4.207-.23 8.423 0 12.63a2.57 2.57 0 0 0 2.3 2.5c1.81.13 4.67.19 7.53.19 2.86 0 5.72-.06 7.53-.19a2.57 2.57 0 0 0 2.3-2.5c.23-4.207.23-8.423 0-12.63Zm-1.49 12.53a1.11 1.11 0 0 1-.91 1.11c-1.67.11-4.45.18-7.43.18-2.98 0-5.76-.07-7.43-.18a1.11 1.11 0 0 1-.91-1.11c-.21-4.14-.21-8.29 0-12.43a1.11 1.11 0 0 1 .91-1.11C7.24 4.56 10 4.49 13 4.49s5.76.07 7.43.18a1.11 1.11 0 0 1 .91 1.11c.21 4.14.21 8.29 0 12.43Z"/>
</svg>`;function Fu(t){return`
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
      <slot name="on">${Hu}</slot>
      <slot name="off">${Nu}</slot>
    </slot>
  `}function $u(){return`
    <slot name="tooltip-enable">${g("Enable captions")}</slot>
    <slot name="tooltip-disable">${g("Disable captions")}</slot>
  `}var rl=t=>{t.setAttribute("aria-checked",Cn(t).toString())},ei=class extends P{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_SUBTITLES_LIST,a.MEDIA_SUBTITLES_SHOWING]}connectedCallback(){super.connectedCallback(),this.setAttribute("role","switch"),this.setAttribute("aria-label",g("closed captions")),rl(this)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===a.MEDIA_SUBTITLES_SHOWING&&rl(this)}get mediaSubtitlesList(){return nl(this,a.MEDIA_SUBTITLES_LIST)}set mediaSubtitlesList(i){ol(this,a.MEDIA_SUBTITLES_LIST,i)}get mediaSubtitlesShowing(){return nl(this,a.MEDIA_SUBTITLES_SHOWING)}set mediaSubtitlesShowing(i){ol(this,a.MEDIA_SUBTITLES_SHOWING,i)}handleClick(){this.dispatchEvent(new u.CustomEvent(b.MEDIA_TOGGLE_SUBTITLES_REQUEST,{composed:!0,bubbles:!0}))}};ei.getSlotTemplateHTML=Fu,ei.getTooltipContentHTML=$u;var nl=(t,i)=>{let e=t.getAttribute(i);return e?at(e):[]},ol=(t,i,e)=>{if(!(e!=null&&e.length)){t.removeAttribute(i);return}let r=_e(e);t.getAttribute(i)!==r&&t.setAttribute(i,r)};u.customElements.get("media-captions-button")||u.customElements.define("media-captions-button",ei);var sl=ei;var Bu='<svg aria-hidden="true" viewBox="0 0 24 24"><g><path class="cast_caf_icon_arch0" d="M1,18 L1,21 L4,21 C4,19.3 2.66,18 1,18 L1,18 Z"/><path class="cast_caf_icon_arch1" d="M1,14 L1,16 C3.76,16 6,18.2 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z"/><path class="cast_caf_icon_arch2" d="M1,10 L1,12 C5.97,12 10,16.0 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z"/><path class="cast_caf_icon_box" d="M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"/></g></svg>',Vu='<svg aria-hidden="true" viewBox="0 0 24 24"><g><path class="cast_caf_icon_arch0" d="M1,18 L1,21 L4,21 C4,19.3 2.66,18 1,18 L1,18 Z"/><path class="cast_caf_icon_arch1" d="M1,14 L1,16 C3.76,16 6,18.2 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z"/><path class="cast_caf_icon_arch2" d="M1,10 L1,12 C5.97,12 10,16.0 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z"/><path class="cast_caf_icon_box" d="M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"/><path class="cast_caf_icon_boxfill" d="M5,7 L5,8.63 C8,8.6 13.37,14 13.37,17 L19,17 L19,7 Z"/></g></svg>';function Ku(t){return`
    <style>
      :host([${a.MEDIA_IS_CASTING}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      
      :host(:not([${a.MEDIA_IS_CASTING}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${a.MEDIA_IS_CASTING}]) slot[name=tooltip-enter],
      :host(:not([${a.MEDIA_IS_CASTING}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${Bu}</slot>
      <slot name="exit">${Vu}</slot>
    </slot>
  `}function Gu(){return`
    <slot name="tooltip-enter">${g("Start casting")}</slot>
    <slot name="tooltip-exit">${g("Stop casting")}</slot>
  `}var al=t=>{let i=t.mediaIsCasting?g("stop casting"):g("start casting");t.setAttribute("aria-label",i)},ti=class extends P{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_IS_CASTING,a.MEDIA_CAST_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),al(this)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===a.MEDIA_IS_CASTING&&al(this)}get mediaIsCasting(){return I(this,a.MEDIA_IS_CASTING)}set mediaIsCasting(i){M(this,a.MEDIA_IS_CASTING,i)}get mediaCastUnavailable(){return L(this,a.MEDIA_CAST_UNAVAILABLE)}set mediaCastUnavailable(i){k(this,a.MEDIA_CAST_UNAVAILABLE,i)}handleClick(){let i=this.mediaIsCasting?b.MEDIA_EXIT_CAST_REQUEST:b.MEDIA_ENTER_CAST_REQUEST;this.dispatchEvent(new u.CustomEvent(i,{composed:!0,bubbles:!0}))}};ti.getSlotTemplateHTML=Ku,ti.getTooltipContentHTML=Gu;u.customElements.get("media-cast-button")||u.customElements.define("media-cast-button",ti);var ll=ti;function qu(t){return`
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
    ${this.getSlotTemplateHTML(t)}
  `}function Wu(t){return`
    <slot id="content"></slot>
  `}var ir={OPEN:"open",ANCHOR:"anchor"},rr,ii,qe,nr,es,Nn,dl,Fn,ul,$n,cl,Bn,ml,Vn,hl,xe=class extends u.HTMLElement{constructor(){super();c(this,nr);c(this,Nn);c(this,Fn);c(this,$n);c(this,Bn);c(this,Vn);c(this,rr,!1);c(this,ii,null);c(this,qe,null);this.addEventListener("invoke",this),this.addEventListener("focusout",this),this.addEventListener("keydown",this)}static get observedAttributes(){return[ir.OPEN,ir.ANCHOR]}get open(){return I(this,ir.OPEN)}set open(e){M(this,ir.OPEN,e)}handleEvent(e){switch(e.type){case"invoke":p(this,$n,cl).call(this,e);break;case"focusout":p(this,Bn,ml).call(this,e);break;case"keydown":p(this,Vn,hl).call(this,e);break}}connectedCallback(){p(this,nr,es).call(this),this.role||(this.role="dialog")}attributeChangedCallback(e,r,n){p(this,nr,es).call(this),e===ir.OPEN&&n!==r&&(this.open?p(this,Nn,dl).call(this):p(this,Fn,ul).call(this))}focus(){h(this,ii,Bi());let e=!this.dispatchEvent(new Event("focus",{composed:!0,cancelable:!0})),r=!this.dispatchEvent(new Event("focusin",{composed:!0,bubbles:!0,cancelable:!0}));if(e||r)return;let n=this.querySelector('[autofocus], [tabindex]:not([tabindex="-1"]), [role="menu"]');n==null||n.focus()}get keysUsed(){return["Escape","Tab"]}};rr=new WeakMap,ii=new WeakMap,qe=new WeakMap,nr=new WeakSet,es=function(){if(!s(this,rr)&&(h(this,rr,!0),!this.shadowRoot)){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e),queueMicrotask(()=>{let{style:r}=O(this.shadowRoot,":host");r.setProperty("transition","display .15s, visibility .15s, opacity .15s ease-in, transform .15s ease-in")})}},Nn=new WeakSet,dl=function(){var e;(e=s(this,qe))==null||e.setAttribute("aria-expanded","true"),this.dispatchEvent(new Event("open",{composed:!0,bubbles:!0})),this.addEventListener("transitionend",()=>this.focus(),{once:!0})},Fn=new WeakSet,ul=function(){var e;(e=s(this,qe))==null||e.setAttribute("aria-expanded","false"),this.dispatchEvent(new Event("close",{composed:!0,bubbles:!0}))},$n=new WeakSet,cl=function(e){h(this,qe,e.relatedTarget),Y(this,e.relatedTarget)||(this.open=!this.open)},Bn=new WeakSet,ml=function(e){var r;Y(this,e.relatedTarget)||((r=s(this,ii))==null||r.focus(),s(this,qe)&&s(this,qe)!==e.relatedTarget&&this.open&&(this.open=!1))},Vn=new WeakSet,hl=function(e){var d,m,E,T,y;let{key:r,ctrlKey:n,altKey:o,metaKey:l}=e;n||o||l||this.keysUsed.includes(r)&&(e.preventDefault(),e.stopPropagation(),r==="Tab"?(e.shiftKey?(m=(d=this.previousElementSibling)==null?void 0:d.focus)==null||m.call(d):(T=(E=this.nextElementSibling)==null?void 0:E.focus)==null||T.call(E),this.blur()):r==="Escape"&&((y=s(this,ii))==null||y.focus(),this.open=!1))},xe.shadowRootOptions={mode:"open"},xe.getTemplateHTML=qu,xe.getSlotTemplateHTML=Wu;u.customElements.get("media-chrome-dialog")||u.customElements.define("media-chrome-dialog",xe);var pl=xe;function Yu(t){return`
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
  `}var le,or,sr,ar,ie,lr,dr,ur,cr,Kn,El,mr,ts,hr,is,pr,rs,Gn,gl,qn,bl,Wn,fl,Yn,vl,de=class extends u.HTMLElement{constructor(){super();c(this,Kn);c(this,mr);c(this,hr);c(this,pr);c(this,Gn);c(this,qn);c(this,Wn);c(this,Yn);c(this,le,void 0);c(this,or,void 0);c(this,sr,void 0);c(this,ar,void 0);c(this,ie,{});c(this,lr,[]);c(this,dr,()=>{if(this.range.matches(":focus-visible")){let{style:e}=O(this.shadowRoot,":host");e.setProperty("--_focus-visible-box-shadow","var(--_focus-box-shadow)")}});c(this,ur,()=>{let{style:e}=O(this.shadowRoot,":host");e.removeProperty("--_focus-visible-box-shadow")});c(this,cr,()=>{let e=this.shadowRoot.querySelector("#segments-clipping");e&&e.parentNode.append(e)});if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes),r=this.constructor.getTemplateHTML(e);this.shadowRoot.setHTMLUnsafe?this.shadowRoot.setHTMLUnsafe(r):this.shadowRoot.innerHTML=r}this.container=this.shadowRoot.querySelector("#container"),h(this,sr,this.shadowRoot.querySelector("#startpoint")),h(this,ar,this.shadowRoot.querySelector("#endpoint")),this.range=this.shadowRoot.querySelector("#range"),this.appearance=this.shadowRoot.querySelector("#appearance")}static get observedAttributes(){return["disabled","aria-disabled",_.MEDIA_CONTROLLER]}attributeChangedCallback(e,r,n){var o,l,d,m,E;e===_.MEDIA_CONTROLLER?(r&&((l=(o=s(this,le))==null?void 0:o.unassociateElement)==null||l.call(o,this),h(this,le,null)),n&&this.isConnected&&(h(this,le,(d=this.getRootNode())==null?void 0:d.getElementById(n)),(E=(m=s(this,le))==null?void 0:m.associateElement)==null||E.call(m,this))):(e==="disabled"||e==="aria-disabled"&&r!==n)&&(n==null?(this.range.removeAttribute(e),p(this,mr,ts).call(this)):(this.range.setAttribute(e,n),p(this,hr,is).call(this)))}connectedCallback(){var n,o,l;let{style:e}=O(this.shadowRoot,":host");e.setProperty("display",`var(--media-control-display, var(--${this.localName}-display, inline-flex))`),s(this,ie).pointer=O(this.shadowRoot,"#pointer"),s(this,ie).progress=O(this.shadowRoot,"#progress"),s(this,ie).thumb=O(this.shadowRoot,'#thumb, ::slotted([slot="thumb"])'),s(this,ie).activeSegment=O(this.shadowRoot,"#segments-clipping rect:nth-child(0)");let r=this.getAttribute(_.MEDIA_CONTROLLER);r&&(h(this,le,(n=this.getRootNode())==null?void 0:n.getElementById(r)),(l=(o=s(this,le))==null?void 0:o.associateElement)==null||l.call(o,this)),this.updateBar(),this.shadowRoot.addEventListener("focusin",s(this,dr)),this.shadowRoot.addEventListener("focusout",s(this,ur)),p(this,mr,ts).call(this),Le(this.container,s(this,cr))}disconnectedCallback(){var e,r;p(this,hr,is).call(this),(r=(e=s(this,le))==null?void 0:e.unassociateElement)==null||r.call(e,this),h(this,le,null),this.shadowRoot.removeEventListener("focusin",s(this,dr)),this.shadowRoot.removeEventListener("focusout",s(this,ur)),ke(this.container,s(this,cr))}updatePointerBar(e){var r;(r=s(this,ie).pointer)==null||r.style.setProperty("width",`${this.getPointerRatio(e)*100}%`)}updateBar(){var r,n;let e=this.range.valueAsNumber*100;(r=s(this,ie).progress)==null||r.style.setProperty("width",`${e}%`),(n=s(this,ie).thumb)==null||n.style.setProperty("left",`${e}%`)}updateSegments(e){let r=this.shadowRoot.querySelector("#segments-clipping");if(r.textContent="",this.container.classList.toggle("segments",!!(e!=null&&e.length)),!(e!=null&&e.length))return;let n=[...new Set([+this.range.min,...e.flatMap(l=>[l.start,l.end]),+this.range.max])];h(this,lr,[...n]);let o=n.pop();for(let[l,d]of n.entries()){let[m,E]=[l===0,l===n.length-1],T=m?"calc(var(--segments-gap) / -1)":`${d*100}%`,v=`calc(${((E?o:n[l+1])-d)*100}%${m||E?"":" - var(--segments-gap)"})`,f=F.createElementNS("http://www.w3.org/2000/svg","rect"),w=O(this.shadowRoot,`#segments-clipping rect:nth-child(${l+1})`);w.style.setProperty("x",T),w.style.setProperty("width",v),r.append(f)}}getPointerRatio(e){return Ma(e.clientX,e.clientY,s(this,sr).getBoundingClientRect(),s(this,ar).getBoundingClientRect())}get dragging(){return this.hasAttribute("dragging")}handleEvent(e){switch(e.type){case"pointermove":p(this,Yn,vl).call(this,e);break;case"input":this.updateBar();break;case"pointerenter":p(this,Gn,gl).call(this,e);break;case"pointerdown":p(this,pr,rs).call(this,e);break;case"pointerup":p(this,qn,bl).call(this);break;case"pointerleave":p(this,Wn,fl).call(this);break}}get keysUsed(){return["ArrowUp","ArrowRight","ArrowDown","ArrowLeft"]}};le=new WeakMap,or=new WeakMap,sr=new WeakMap,ar=new WeakMap,ie=new WeakMap,lr=new WeakMap,dr=new WeakMap,ur=new WeakMap,cr=new WeakMap,Kn=new WeakSet,El=function(e){let r=s(this,ie).activeSegment;if(!r)return;let n=this.getPointerRatio(e),l=`#segments-clipping rect:nth-child(${s(this,lr).findIndex((d,m,E)=>{let T=E[m+1];return T!=null&&n>=d&&n<=T})+1})`;(r.selectorText!=l||!r.style.transform)&&(r.selectorText=l,r.style.setProperty("transform","var(--media-range-segment-hover-transform, scaleY(2))"))},mr=new WeakSet,ts=function(){this.hasAttribute("disabled")||(this.addEventListener("input",this),this.addEventListener("pointerdown",this),this.addEventListener("pointerenter",this))},hr=new WeakSet,is=function(){var e,r;this.removeEventListener("input",this),this.removeEventListener("pointerdown",this),this.removeEventListener("pointerenter",this),(e=u.window)==null||e.removeEventListener("pointerup",this),(r=u.window)==null||r.removeEventListener("pointermove",this)},pr=new WeakSet,rs=function(e){var r;h(this,or,e.composedPath().includes(this.range)),(r=u.window)==null||r.addEventListener("pointerup",this)},Gn=new WeakSet,gl=function(e){var r;e.pointerType!=="mouse"&&p(this,pr,rs).call(this,e),this.addEventListener("pointerleave",this),(r=u.window)==null||r.addEventListener("pointermove",this)},qn=new WeakSet,bl=function(){var e;(e=u.window)==null||e.removeEventListener("pointerup",this),this.toggleAttribute("dragging",!1),this.range.disabled=this.hasAttribute("disabled")},Wn=new WeakSet,fl=function(){var e,r;this.removeEventListener("pointerleave",this),(e=u.window)==null||e.removeEventListener("pointermove",this),this.toggleAttribute("dragging",!1),this.range.disabled=this.hasAttribute("disabled"),(r=s(this,ie).activeSegment)==null||r.style.removeProperty("transform")},Yn=new WeakSet,vl=function(e){this.toggleAttribute("dragging",e.buttons===1||e.pointerType!=="mouse"),this.updatePointerBar(e),p(this,Kn,El).call(this,e),this.dragging&&(e.pointerType!=="mouse"||!s(this,or))&&(this.range.disabled=!0,this.range.valueAsNumber=this.getPointerRatio(e),this.range.dispatchEvent(new Event("input",{bubbles:!0,composed:!0})))},de.shadowRootOptions={mode:"open"},de.getTemplateHTML=Yu;u.customElements.get("media-chrome-range")||u.customElements.define("media-chrome-range",de);var Tl=de;function ju(t){return`
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
  `}var ue,ri=class extends u.HTMLElement{constructor(){super();c(this,ue,void 0);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER]}attributeChangedCallback(e,r,n){var o,l,d,m,E;e===_.MEDIA_CONTROLLER&&(r&&((l=(o=s(this,ue))==null?void 0:o.unassociateElement)==null||l.call(o,this),h(this,ue,null)),n&&this.isConnected&&(h(this,ue,(d=this.getRootNode())==null?void 0:d.getElementById(n)),(E=(m=s(this,ue))==null?void 0:m.associateElement)==null||E.call(m,this)))}connectedCallback(){var r,n,o;let e=this.getAttribute(_.MEDIA_CONTROLLER);e&&(h(this,ue,(r=this.getRootNode())==null?void 0:r.getElementById(e)),(o=(n=s(this,ue))==null?void 0:n.associateElement)==null||o.call(n,this))}disconnectedCallback(){var e,r;(r=(e=s(this,ue))==null?void 0:e.unassociateElement)==null||r.call(e,this),h(this,ue,null)}};ue=new WeakMap,ri.shadowRootOptions={mode:"open"},ri.getTemplateHTML=ju;u.customElements.get("media-control-bar")||u.customElements.define("media-control-bar",ri);var Al=ri;function Qu(t,i={}){return`
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

    ${this.getSlotTemplateHTML(t,i)}
  `}function zu(t,i){return`
    <slot></slot>
  `}var ce,Q=class extends u.HTMLElement{constructor(){super();c(this,ce,void 0);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER]}attributeChangedCallback(e,r,n){var o,l,d,m,E;e===_.MEDIA_CONTROLLER&&(r&&((l=(o=s(this,ce))==null?void 0:o.unassociateElement)==null||l.call(o,this),h(this,ce,null)),n&&this.isConnected&&(h(this,ce,(d=this.getRootNode())==null?void 0:d.getElementById(n)),(E=(m=s(this,ce))==null?void 0:m.associateElement)==null||E.call(m,this)))}connectedCallback(){var n,o,l;let{style:e}=O(this.shadowRoot,":host");e.setProperty("display",`var(--media-control-display, var(--${this.localName}-display, inline-flex))`);let r=this.getAttribute(_.MEDIA_CONTROLLER);r&&(h(this,ce,(n=this.getRootNode())==null?void 0:n.getElementById(r)),(l=(o=s(this,ce))==null?void 0:o.associateElement)==null||l.call(o,this))}disconnectedCallback(){var e,r;(r=(e=s(this,ce))==null?void 0:e.unassociateElement)==null||r.call(e,this),h(this,ce,null)}};ce=new WeakMap,Q.shadowRootOptions={mode:"open"},Q.getTemplateHTML=Qu,Q.getSlotTemplateHTML=zu;u.customElements.get("media-text-display")||u.customElements.define("media-text-display",Q);var yl=Q;function Zu(t,i){return`
    <slot>${te(i.mediaDuration)}</slot>
  `}var ni,Er=class extends Q{constructor(){var e;super();c(this,ni,void 0);h(this,ni,this.shadowRoot.querySelector("slot")),s(this,ni).textContent=te((e=this.mediaDuration)!=null?e:0)}static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_DURATION]}attributeChangedCallback(e,r,n){e===a.MEDIA_DURATION&&(s(this,ni).textContent=te(+n)),super.attributeChangedCallback(e,r,n)}get mediaDuration(){return R(this,a.MEDIA_DURATION)}set mediaDuration(e){C(this,a.MEDIA_DURATION,e)}};ni=new WeakMap,Er.getSlotTemplateHTML=Zu;u.customElements.get("media-duration-display")||u.customElements.define("media-duration-display",Er);var Sl=Er;var Xu={2:g("Network Error"),3:g("Decode Error"),4:g("Source Not Supported"),5:g("Encryption Error")},Ju={2:g("A network error caused the media download to fail."),3:g("A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format."),4:g("An unsupported error occurred. The server or network failed, or your browser does not support this format."),5:g("The media is encrypted and there are no keys to decrypt it.")},ns=t=>{var i,e;return t.code===1?null:{title:(i=Xu[t.code])!=null?i:`Error ${t.code}`,message:(e=Ju[t.code])!=null?e:t.message}};function ec(t){return`
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
    <slot name="error-${t.mediaerrorcode}" id="content">
      ${Ml({code:+t.mediaerrorcode,message:t.mediaerrormessage})}
    </slot>
  `}function tc(t){return t.code&&ns(t)!==null}function Ml(t){var n;let{title:i,message:e}=(n=ns(t))!=null?n:{},r="";return i&&(r+=`<slot name="error-${t.code}-title"><h3>${i}</h3></slot>`),e&&(r+=`<slot name="error-${t.code}-message"><p>${e}</p></slot>`),r}var Il=[a.MEDIA_ERROR_CODE,a.MEDIA_ERROR_MESSAGE],gr,oi=class extends xe{constructor(){super(...arguments);c(this,gr,null)}static get observedAttributes(){return[...super.observedAttributes,...Il]}formatErrorMessage(e){return this.constructor.formatErrorMessage(e)}attributeChangedCallback(e,r,n){var l;if(super.attributeChangedCallback(e,r,n),!Il.includes(e))return;let o=(l=this.mediaError)!=null?l:{code:this.mediaErrorCode,message:this.mediaErrorMessage};this.open=tc(o),this.open&&(this.shadowRoot.querySelector("slot").name=`error-${this.mediaErrorCode}`,this.shadowRoot.querySelector("#content").innerHTML=this.formatErrorMessage(o))}get mediaError(){return s(this,gr)}set mediaError(e){h(this,gr,e)}get mediaErrorCode(){return R(this,"mediaerrorcode")}set mediaErrorCode(e){C(this,"mediaerrorcode",e)}get mediaErrorMessage(){return L(this,"mediaerrormessage")}set mediaErrorMessage(e){k(this,"mediaerrormessage",e)}};gr=new WeakMap,oi.getSlotTemplateHTML=ec,oi.formatErrorMessage=Ml;u.customElements.get("media-error-dialog")||u.customElements.define("media-error-dialog",oi);var Ll=oi;var ic=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M16 3v2.5h3.5V9H22V3h-6ZM4 9h2.5V5.5H10V3H4v6Zm15.5 9.5H16V21h6v-6h-2.5v3.5ZM6.5 15H4v6h6v-2.5H6.5V15Z"/>
</svg>`,rc=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M18.5 6.5V3H16v6h6V6.5h-3.5ZM16 21h2.5v-3.5H22V15h-6v6ZM4 17.5h3.5V21H10v-6H4v2.5Zm3.5-11H4V9h6V3H7.5v3.5Z"/>
</svg>`;function nc(t){return`
    <style>
      :host([${a.MEDIA_IS_FULLSCREEN}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      
      :host(:not([${a.MEDIA_IS_FULLSCREEN}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${a.MEDIA_IS_FULLSCREEN}]) slot[name=tooltip-enter],
      :host(:not([${a.MEDIA_IS_FULLSCREEN}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${ic}</slot>
      <slot name="exit">${rc}</slot>
    </slot>
  `}function oc(){return`
    <slot name="tooltip-enter">${g("Enter fullscreen mode")}</slot>
    <slot name="tooltip-exit">${g("Exit fullscreen mode")}</slot>
  `}var kl=t=>{let i=t.mediaIsFullscreen?g("exit fullscreen mode"):g("enter fullscreen mode");t.setAttribute("aria-label",i)},si=class extends P{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_IS_FULLSCREEN,a.MEDIA_FULLSCREEN_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),kl(this)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===a.MEDIA_IS_FULLSCREEN&&kl(this)}get mediaFullscreenUnavailable(){return L(this,a.MEDIA_FULLSCREEN_UNAVAILABLE)}set mediaFullscreenUnavailable(i){k(this,a.MEDIA_FULLSCREEN_UNAVAILABLE,i)}get mediaIsFullscreen(){return I(this,a.MEDIA_IS_FULLSCREEN)}set mediaIsFullscreen(i){M(this,a.MEDIA_IS_FULLSCREEN,i)}handleClick(){let i=this.mediaIsFullscreen?b.MEDIA_EXIT_FULLSCREEN_REQUEST:b.MEDIA_ENTER_FULLSCREEN_REQUEST;this.dispatchEvent(new u.CustomEvent(i,{composed:!0,bubbles:!0}))}};si.getSlotTemplateHTML=nc,si.getTooltipContentHTML=oc;u.customElements.get("media-fullscreen-button")||u.customElements.define("media-fullscreen-button",si);var _l=si;var{MEDIA_TIME_IS_LIVE:jn,MEDIA_PAUSED:br}=a,{MEDIA_SEEK_TO_LIVE_REQUEST:sc,MEDIA_PLAY_REQUEST:ac}=b,lc='<svg viewBox="0 0 6 12"><circle cx="3" cy="6" r="2"></circle></svg>';function dc(t){return`
    <style>
      :host { --media-tooltip-display: none; }
      
      slot[name=indicator] > *,
      :host ::slotted([slot=indicator]) {
        
        min-width: auto;
        fill: var(--media-live-button-icon-color, rgb(140, 140, 140));
        color: var(--media-live-button-icon-color, rgb(140, 140, 140));
      }

      :host([${jn}]:not([${br}])) slot[name=indicator] > *,
      :host([${jn}]:not([${br}])) ::slotted([slot=indicator]) {
        fill: var(--media-live-button-indicator-color, rgb(255, 0, 0));
        color: var(--media-live-button-indicator-color, rgb(255, 0, 0));
      }

      :host([${jn}]:not([${br}])) {
        cursor: var(--media-cursor, not-allowed);
      }

      slot[name=text]{
        text-transform: uppercase;
      }

    </style>

    <slot name="indicator">${lc}</slot>
    
    <slot name="spacer">&nbsp;</slot><slot name="text">${g("live")}</slot>
  `}var Rl=t=>{let i=t.mediaPaused||!t.mediaTimeIsLive,e=i?g("seek to live"):g("playing live");t.setAttribute("aria-label",e),i?t.removeAttribute("aria-disabled"):t.setAttribute("aria-disabled","true")},fr=class extends P{static get observedAttributes(){return[...super.observedAttributes,jn,br]}connectedCallback(){super.connectedCallback(),Rl(this)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),Rl(this)}get mediaPaused(){return I(this,a.MEDIA_PAUSED)}set mediaPaused(i){M(this,a.MEDIA_PAUSED,i)}get mediaTimeIsLive(){return I(this,a.MEDIA_TIME_IS_LIVE)}set mediaTimeIsLive(i){M(this,a.MEDIA_TIME_IS_LIVE,i)}handleClick(){!this.mediaPaused&&this.mediaTimeIsLive||(this.dispatchEvent(new u.CustomEvent(sc,{composed:!0,bubbles:!0})),this.hasAttribute(br)&&this.dispatchEvent(new u.CustomEvent(ac,{composed:!0,bubbles:!0})))}};fr.getSlotTemplateHTML=dc;u.customElements.get("media-live-button")||u.customElements.define("media-live-button",fr);var xl=fr;var Qn={LOADING_DELAY:"loadingdelay",NO_AUTOHIDE:"noautohide"},Cl=500,uc=`
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
`;function cc(t){return`
    <style>
      :host {
        display: var(--media-control-display, var(--media-loading-indicator-display, inline-block));
        vertical-align: middle;
        box-sizing: border-box;
        --_loading-indicator-delay: var(--media-loading-indicator-transition-delay, ${Cl}ms);
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

      :host([${a.MEDIA_LOADING}]:not([${a.MEDIA_PAUSED}])) slot[name=icon] > *,
      :host([${a.MEDIA_LOADING}]:not([${a.MEDIA_PAUSED}])) ::slotted([slot=icon]) {
        opacity: var(--media-loading-indicator-opacity, 1);
        transition: opacity 0.15s var(--_loading-indicator-delay);
      }

      :host #status {
        visibility: var(--media-loading-indicator-opacity, hidden);
        transition: visibility 0.15s;
      }

      :host([${a.MEDIA_LOADING}]:not([${a.MEDIA_PAUSED}])) #status {
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

    <slot name="icon">${uc}</slot>
    <div id="status" role="status" aria-live="polite">${g("media loading")}</div>
  `}var me,vr,ai=class extends u.HTMLElement{constructor(){super();c(this,me,void 0);c(this,vr,Cl);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER,a.MEDIA_PAUSED,a.MEDIA_LOADING,Qn.LOADING_DELAY]}attributeChangedCallback(e,r,n){var o,l,d,m,E;e===Qn.LOADING_DELAY&&r!==n?this.loadingDelay=Number(n):e===_.MEDIA_CONTROLLER&&(r&&((l=(o=s(this,me))==null?void 0:o.unassociateElement)==null||l.call(o,this),h(this,me,null)),n&&this.isConnected&&(h(this,me,(d=this.getRootNode())==null?void 0:d.getElementById(n)),(E=(m=s(this,me))==null?void 0:m.associateElement)==null||E.call(m,this)))}connectedCallback(){var r,n,o;let e=this.getAttribute(_.MEDIA_CONTROLLER);e&&(h(this,me,(r=this.getRootNode())==null?void 0:r.getElementById(e)),(o=(n=s(this,me))==null?void 0:n.associateElement)==null||o.call(n,this))}disconnectedCallback(){var e,r;(r=(e=s(this,me))==null?void 0:e.unassociateElement)==null||r.call(e,this),h(this,me,null)}get loadingDelay(){return s(this,vr)}set loadingDelay(e){h(this,vr,e);let{style:r}=O(this.shadowRoot,":host");r.setProperty("--_loading-indicator-delay",`var(--media-loading-indicator-transition-delay, ${e}ms)`)}get mediaPaused(){return I(this,a.MEDIA_PAUSED)}set mediaPaused(e){M(this,a.MEDIA_PAUSED,e)}get mediaLoading(){return I(this,a.MEDIA_LOADING)}set mediaLoading(e){M(this,a.MEDIA_LOADING,e)}get mediaController(){return L(this,_.MEDIA_CONTROLLER)}set mediaController(e){k(this,_.MEDIA_CONTROLLER,e)}get noAutohide(){return I(this,Qn.NO_AUTOHIDE)}set noAutohide(e){M(this,Qn.NO_AUTOHIDE,e)}};me=new WeakMap,vr=new WeakMap,ai.shadowRootOptions={mode:"open"},ai.getTemplateHTML=cc;u.customElements.get("media-loading-indicator")||u.customElements.define("media-loading-indicator",ai);var Dl=ai;var mc=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M16.5 12A4.5 4.5 0 0 0 14 8v2.18l2.45 2.45a4.22 4.22 0 0 0 .05-.63Zm2.5 0a6.84 6.84 0 0 1-.54 2.64L20 16.15A8.8 8.8 0 0 0 21 12a9 9 0 0 0-7-8.77v2.06A7 7 0 0 1 19 12ZM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25A6.92 6.92 0 0 1 14 18.7v2.06A9 9 0 0 0 17.69 19l2 2.05L21 19.73l-9-9L4.27 3ZM12 4 9.91 6.09 12 8.18V4Z"/>
</svg>`,wl=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M3 9v6h4l5 5V4L7 9H3Zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4Z"/>
</svg>`,hc=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M3 9v6h4l5 5V4L7 9H3Zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4ZM14 3.23v2.06a7 7 0 0 1 0 13.42v2.06a9 9 0 0 0 0-17.54Z"/>
</svg>`;function pc(t){return`
    <style>
      :host(:not([${a.MEDIA_VOLUME_LEVEL}])) slot[name=icon] slot:not([name=high]),
      :host([${a.MEDIA_VOLUME_LEVEL}=high]) slot[name=icon] slot:not([name=high]) {
        display: none !important;
      }

      :host([${a.MEDIA_VOLUME_LEVEL}=off]) slot[name=icon] slot:not([name=off]) {
        display: none !important;
      }

      :host([${a.MEDIA_VOLUME_LEVEL}=low]) slot[name=icon] slot:not([name=low]) {
        display: none !important;
      }

      :host([${a.MEDIA_VOLUME_LEVEL}=medium]) slot[name=icon] slot:not([name=medium]) {
        display: none !important;
      }

      :host(:not([${a.MEDIA_VOLUME_LEVEL}=off])) slot[name=tooltip-unmute],
      :host([${a.MEDIA_VOLUME_LEVEL}=off]) slot[name=tooltip-mute] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="off">${mc}</slot>
      <slot name="low">${wl}</slot>
      <slot name="medium">${wl}</slot>
      <slot name="high">${hc}</slot>
    </slot>
  `}function Ec(){return`
    <slot name="tooltip-mute">${g("Mute")}</slot>
    <slot name="tooltip-unmute">${g("Unmute")}</slot>
  `}var Pl=t=>{let e=t.mediaVolumeLevel==="off"?g("unmute"):g("mute");t.setAttribute("aria-label",e)},li=class extends P{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_VOLUME_LEVEL]}connectedCallback(){super.connectedCallback(),Pl(this)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===a.MEDIA_VOLUME_LEVEL&&Pl(this)}get mediaVolumeLevel(){return L(this,a.MEDIA_VOLUME_LEVEL)}set mediaVolumeLevel(i){k(this,a.MEDIA_VOLUME_LEVEL,i)}handleClick(){let i=this.mediaVolumeLevel==="off"?b.MEDIA_UNMUTE_REQUEST:b.MEDIA_MUTE_REQUEST;this.dispatchEvent(new u.CustomEvent(i,{composed:!0,bubbles:!0}))}};li.getSlotTemplateHTML=pc,li.getTooltipContentHTML=Ec;u.customElements.get("media-mute-button")||u.customElements.define("media-mute-button",li);var Ul=li;var Ol=`<svg aria-hidden="true" viewBox="0 0 28 24">
  <path d="M24 3H4a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h20a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1Zm-1 16H5V5h18v14Zm-3-8h-7v5h7v-5Z"/>
</svg>`;function gc(t){return`
    <style>
      :host([${a.MEDIA_IS_PIP}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      :host(:not([${a.MEDIA_IS_PIP}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${a.MEDIA_IS_PIP}]) slot[name=tooltip-enter],
      :host(:not([${a.MEDIA_IS_PIP}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${Ol}</slot>
      <slot name="exit">${Ol}</slot>
    </slot>
  `}function bc(){return`
    <slot name="tooltip-enter">${g("Enter picture in picture mode")}</slot>
    <slot name="tooltip-exit">${g("Exit picture in picture mode")}</slot>
  `}var Hl=t=>{let i=t.mediaIsPip?g("exit picture in picture mode"):g("enter picture in picture mode");t.setAttribute("aria-label",i)},di=class extends P{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_IS_PIP,a.MEDIA_PIP_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),Hl(this)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===a.MEDIA_IS_PIP&&Hl(this)}get mediaPipUnavailable(){return L(this,a.MEDIA_PIP_UNAVAILABLE)}set mediaPipUnavailable(i){k(this,a.MEDIA_PIP_UNAVAILABLE,i)}get mediaIsPip(){return I(this,a.MEDIA_IS_PIP)}set mediaIsPip(i){M(this,a.MEDIA_IS_PIP,i)}handleClick(){let i=this.mediaIsPip?b.MEDIA_EXIT_PIP_REQUEST:b.MEDIA_ENTER_PIP_REQUEST;this.dispatchEvent(new u.CustomEvent(i,{composed:!0,bubbles:!0}))}};di.getSlotTemplateHTML=gc,di.getTooltipContentHTML=bc;u.customElements.get("media-pip-button")||u.customElements.define("media-pip-button",di);var Nl=di;var os={RATES:"rates"},ss=[1,1.2,1.5,1.7,2],pt=1;function fc(t){return`
    <style>
      :host {
        min-width: 5ch;
        padding: var(--media-button-padding, var(--media-control-padding, 10px 5px));
      }
    </style>
    <slot name="icon">${t.mediaplaybackrate||pt}x</slot>
  `}function vc(){return g("Playback rate")}var Ce,ui=class extends P{constructor(){var e;super();c(this,Ce,new Ve(this,os.RATES,{defaultValue:ss}));this.container=this.shadowRoot.querySelector('slot[name="icon"]'),this.container.innerHTML=`${(e=this.mediaPlaybackRate)!=null?e:pt}x`}static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_PLAYBACK_RATE,os.RATES]}attributeChangedCallback(e,r,n){if(super.attributeChangedCallback(e,r,n),e===os.RATES&&(s(this,Ce).value=n),e===a.MEDIA_PLAYBACK_RATE){let o=n?+n:Number.NaN,l=Number.isNaN(o)?pt:o;this.container.innerHTML=`${l}x`,this.setAttribute("aria-label",g("Playback rate {playbackRate}",{playbackRate:l}))}}get rates(){return s(this,Ce)}set rates(e){e?Array.isArray(e)?s(this,Ce).value=e.join(" "):typeof e=="string"&&(s(this,Ce).value=e):s(this,Ce).value=""}get mediaPlaybackRate(){return R(this,a.MEDIA_PLAYBACK_RATE,pt)}set mediaPlaybackRate(e){C(this,a.MEDIA_PLAYBACK_RATE,e)}handleClick(){var o,l;let e=Array.from(s(this,Ce).values(),d=>+d).sort((d,m)=>d-m),r=(l=(o=e.find(d=>d>this.mediaPlaybackRate))!=null?o:e[0])!=null?l:pt,n=new u.CustomEvent(b.MEDIA_PLAYBACK_RATE_REQUEST,{composed:!0,bubbles:!0,detail:r});this.dispatchEvent(n)}};Ce=new WeakMap,ui.getSlotTemplateHTML=fc,ui.getTooltipContentHTML=vc;u.customElements.get("media-playback-rate-button")||u.customElements.define("media-playback-rate-button",ui);var Fl=ui;var Tc=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="m6 21 15-9L6 3v18Z"/>
</svg>`,Ac=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M6 20h4V4H6v16Zm8-16v16h4V4h-4Z"/>
</svg>`;function yc(t){return`
    <style>
      :host([${a.MEDIA_PAUSED}]) slot[name=pause],
      :host(:not([${a.MEDIA_PAUSED}])) slot[name=play] {
        display: none !important;
      }

      :host([${a.MEDIA_PAUSED}]) slot[name=tooltip-pause],
      :host(:not([${a.MEDIA_PAUSED}])) slot[name=tooltip-play] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="play">${Tc}</slot>
      <slot name="pause">${Ac}</slot>
    </slot>
  `}function Sc(){return`
    <slot name="tooltip-play">${g("Play")}</slot>
    <slot name="tooltip-pause">${g("Pause")}</slot>
  `}var $l=t=>{let i=t.mediaPaused?g("play"):g("pause");t.setAttribute("aria-label",i)},ci=class extends P{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_PAUSED,a.MEDIA_ENDED]}connectedCallback(){super.connectedCallback(),$l(this)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===a.MEDIA_PAUSED&&$l(this)}get mediaPaused(){return I(this,a.MEDIA_PAUSED)}set mediaPaused(i){M(this,a.MEDIA_PAUSED,i)}handleClick(){let i=this.mediaPaused?b.MEDIA_PLAY_REQUEST:b.MEDIA_PAUSE_REQUEST;this.dispatchEvent(new u.CustomEvent(i,{composed:!0,bubbles:!0}))}};ci.getSlotTemplateHTML=yc,ci.getTooltipContentHTML=Sc;u.customElements.get("media-play-button")||u.customElements.define("media-play-button",ci);var Bl=ci;var ye={PLACEHOLDER_SRC:"placeholdersrc",SRC:"src"};function Ic(t){return`
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
  `}var Mc=t=>{t.style.removeProperty("background-image")},Lc=(t,i)=>{t.style["background-image"]=`url('${i}')`},mi=class extends u.HTMLElement{static get observedAttributes(){return[ye.PLACEHOLDER_SRC,ye.SRC]}constructor(){if(super(),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let i=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(i)}this.image=this.shadowRoot.querySelector("#image")}attributeChangedCallback(i,e,r){i===ye.SRC&&(r==null?this.image.removeAttribute(ye.SRC):this.image.setAttribute(ye.SRC,r)),i===ye.PLACEHOLDER_SRC&&(r==null?Mc(this.image):Lc(this.image,r))}get placeholderSrc(){return L(this,ye.PLACEHOLDER_SRC)}set placeholderSrc(i){k(this,ye.SRC,i)}get src(){return L(this,ye.SRC)}set src(i){k(this,ye.SRC,i)}};mi.shadowRootOptions={mode:"open"},mi.getTemplateHTML=Ic;u.customElements.get("media-poster-image")||u.customElements.define("media-poster-image",mi);var Vl=mi;var Tr,zn=class extends Q{constructor(){super();c(this,Tr,void 0);h(this,Tr,this.shadowRoot.querySelector("slot"))}static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_PREVIEW_CHAPTER]}attributeChangedCallback(e,r,n){super.attributeChangedCallback(e,r,n),e===a.MEDIA_PREVIEW_CHAPTER&&n!==r&&n!=null&&(s(this,Tr).textContent=n,n!==""?this.setAttribute("aria-valuetext",`chapter: ${n}`):this.removeAttribute("aria-valuetext"))}get mediaPreviewChapter(){return L(this,a.MEDIA_PREVIEW_CHAPTER)}set mediaPreviewChapter(e){k(this,a.MEDIA_PREVIEW_CHAPTER,e)}};Tr=new WeakMap;u.customElements.get("media-preview-chapter-display")||u.customElements.define("media-preview-chapter-display",zn);var Kl=zn;function kc(t){return`
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
  `}var he,hi=class extends u.HTMLElement{constructor(){super();c(this,he,void 0);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER,a.MEDIA_PREVIEW_IMAGE,a.MEDIA_PREVIEW_COORDS]}connectedCallback(){var r,n,o;let e=this.getAttribute(_.MEDIA_CONTROLLER);e&&(h(this,he,(r=this.getRootNode())==null?void 0:r.getElementById(e)),(o=(n=s(this,he))==null?void 0:n.associateElement)==null||o.call(n,this))}disconnectedCallback(){var e,r;(r=(e=s(this,he))==null?void 0:e.unassociateElement)==null||r.call(e,this),h(this,he,null)}attributeChangedCallback(e,r,n){var o,l,d,m,E;[a.MEDIA_PREVIEW_IMAGE,a.MEDIA_PREVIEW_COORDS].includes(e)&&this.update(),e===_.MEDIA_CONTROLLER&&(r&&((l=(o=s(this,he))==null?void 0:o.unassociateElement)==null||l.call(o,this),h(this,he,null)),n&&this.isConnected&&(h(this,he,(d=this.getRootNode())==null?void 0:d.getElementById(n)),(E=(m=s(this,he))==null?void 0:m.associateElement)==null||E.call(m,this)))}get mediaPreviewImage(){return L(this,a.MEDIA_PREVIEW_IMAGE)}set mediaPreviewImage(e){k(this,a.MEDIA_PREVIEW_IMAGE,e)}get mediaPreviewCoords(){let e=this.getAttribute(a.MEDIA_PREVIEW_COORDS);if(e)return e.split(/\s+/).map(r=>+r)}set mediaPreviewCoords(e){if(!e){this.removeAttribute(a.MEDIA_PREVIEW_COORDS);return}this.setAttribute(a.MEDIA_PREVIEW_COORDS,e.join(" "))}update(){let e=this.mediaPreviewCoords,r=this.mediaPreviewImage;if(!(e&&r))return;let[n,o,l,d]=e,m=r.split("#")[0],E=getComputedStyle(this),{maxWidth:T,maxHeight:y,minWidth:v,minHeight:f}=E,w=Math.min(parseInt(T)/l,parseInt(y)/d),x=Math.max(parseInt(v)/l,parseInt(f)/d),D=w<1,U=D?w:x>1?x:1,{style:q}=O(this.shadowRoot,":host"),Ie=O(this.shadowRoot,"img").style,He=this.shadowRoot.querySelector("img"),Ni=D?"min":"max";q.setProperty(`${Ni}-width`,"initial","important"),q.setProperty(`${Ni}-height`,"initial","important"),q.width=`${l*U}px`,q.height=`${d*U}px`;let $t=()=>{Ie.width=`${this.imgWidth*U}px`,Ie.height=`${this.imgHeight*U}px`,Ie.display="block"};He.src!==m&&(He.onload=()=>{this.imgWidth=He.naturalWidth,this.imgHeight=He.naturalHeight,$t()},He.src=m,$t()),$t(),Ie.transform=`translate(-${n*U}px, -${o*U}px)`}};he=new WeakMap,hi.shadowRootOptions={mode:"open"},hi.getTemplateHTML=kc;u.customElements.get("media-preview-thumbnail")||u.customElements.define("media-preview-thumbnail",hi);var Ar=hi;var pi,Zn=class extends Q{constructor(){super();c(this,pi,void 0);h(this,pi,this.shadowRoot.querySelector("slot")),s(this,pi).textContent=te(0)}static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_PREVIEW_TIME]}attributeChangedCallback(e,r,n){super.attributeChangedCallback(e,r,n),e===a.MEDIA_PREVIEW_TIME&&n!=null&&(s(this,pi).textContent=te(parseFloat(n)))}get mediaPreviewTime(){return R(this,a.MEDIA_PREVIEW_TIME)}set mediaPreviewTime(e){C(this,a.MEDIA_PREVIEW_TIME,e)}};pi=new WeakMap;u.customElements.get("media-preview-time-display")||u.customElements.define("media-preview-time-display",Zn);var Gl=Zn;var Ei={SEEK_OFFSET:"seekoffset"},as=30,_c=t=>`
  <svg aria-hidden="true" viewBox="0 0 20 24">
    <defs>
      <style>.text{font-size:8px;font-family:Arial-BoldMT, Arial;font-weight:700;}</style>
    </defs>
    <text class="text value" transform="translate(2.18 19.87)">${t}</text>
    <path d="M10 6V3L4.37 7 10 10.94V8a5.54 5.54 0 0 1 1.9 10.48v2.12A7.5 7.5 0 0 0 10 6Z"/>
  </svg>`;function Rc(t,i){return`
    <slot name="icon">${_c(i.seekOffset)}</slot>
  `}function xc(){return g("Seek backward")}var Cc=0,gi=class extends P{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_CURRENT_TIME,Ei.SEEK_OFFSET]}connectedCallback(){super.connectedCallback(),this.seekOffset=R(this,Ei.SEEK_OFFSET,as)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===Ei.SEEK_OFFSET&&(this.seekOffset=R(this,Ei.SEEK_OFFSET,as))}get seekOffset(){return R(this,Ei.SEEK_OFFSET,as)}set seekOffset(i){C(this,Ei.SEEK_OFFSET,i),this.setAttribute("aria-label",g("seek back {seekOffset} seconds",{seekOffset:this.seekOffset})),Sn(In(this,"icon"),this.seekOffset)}get mediaCurrentTime(){return R(this,a.MEDIA_CURRENT_TIME,Cc)}set mediaCurrentTime(i){C(this,a.MEDIA_CURRENT_TIME,i)}handleClick(){let i=Math.max(this.mediaCurrentTime-this.seekOffset,0),e=new u.CustomEvent(b.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:i});this.dispatchEvent(e)}};gi.getSlotTemplateHTML=Rc,gi.getTooltipContentHTML=xc;u.customElements.get("media-seek-backward-button")||u.customElements.define("media-seek-backward-button",gi);var ql=gi;var bi={SEEK_OFFSET:"seekoffset"},ls=30,Dc=t=>`
  <svg aria-hidden="true" viewBox="0 0 20 24">
    <defs>
      <style>.text{font-size:8px;font-family:Arial-BoldMT, Arial;font-weight:700;}</style>
    </defs>
    <text class="text value" transform="translate(8.9 19.87)">${t}</text>
    <path d="M10 6V3l5.61 4L10 10.94V8a5.54 5.54 0 0 0-1.9 10.48v2.12A7.5 7.5 0 0 1 10 6Z"/>
  </svg>`;function wc(t,i){return`
    <slot name="icon">${Dc(i.seekOffset)}</slot>
  `}function Pc(){return g("Seek forward")}var Uc=0,fi=class extends P{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_CURRENT_TIME,bi.SEEK_OFFSET]}connectedCallback(){super.connectedCallback(),this.seekOffset=R(this,bi.SEEK_OFFSET,ls)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===bi.SEEK_OFFSET&&(this.seekOffset=R(this,bi.SEEK_OFFSET,ls))}get seekOffset(){return R(this,bi.SEEK_OFFSET,ls)}set seekOffset(i){C(this,bi.SEEK_OFFSET,i),this.setAttribute("aria-label",g("seek forward {seekOffset} seconds",{seekOffset:this.seekOffset})),Sn(In(this,"icon"),this.seekOffset)}get mediaCurrentTime(){return R(this,a.MEDIA_CURRENT_TIME,Uc)}set mediaCurrentTime(i){C(this,a.MEDIA_CURRENT_TIME,i)}handleClick(){let i=this.mediaCurrentTime+this.seekOffset,e=new u.CustomEvent(b.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:i});this.dispatchEvent(e)}};fi.getSlotTemplateHTML=wc,fi.getTooltipContentHTML=Pc;u.customElements.get("media-seek-forward-button")||u.customElements.define("media-seek-forward-button",fi);var Wl=fi;var Et={REMAINING:"remaining",SHOW_DURATION:"showduration",NO_TOGGLE:"notoggle"},Yl=[...Object.values(Et),a.MEDIA_CURRENT_TIME,a.MEDIA_DURATION,a.MEDIA_SEEKABLE],jl=["Enter"," "],Oc="&nbsp;/&nbsp;",ds=(t,{timesSep:i=Oc}={})=>{var l,d;let e=(l=t.mediaCurrentTime)!=null?l:0,[,r]=(d=t.mediaSeekable)!=null?d:[],n=0;Number.isFinite(t.mediaDuration)?n=t.mediaDuration:Number.isFinite(r)&&(n=r);let o=t.remaining?te(0-(n-e)):te(e);return t.showDuration?`${o}${i}${te(n)}`:o},Hc="video not loaded, unknown time.",Nc=t=>{var d;let i=t.mediaCurrentTime,[,e]=(d=t.mediaSeekable)!=null?d:[],r=null;if(Number.isFinite(t.mediaDuration)?r=t.mediaDuration:Number.isFinite(e)&&(r=e),i==null||r===null){t.setAttribute("aria-valuetext",Hc);return}let n=t.remaining?Ne(0-(r-i)):Ne(i);if(!t.showDuration){t.setAttribute("aria-valuetext",n);return}let o=Ne(r),l=`${n} of ${o}`;t.setAttribute("aria-valuetext",l)};function Fc(t,i){return`
    <slot>${ds(i)}</slot>
  `}var gt,yr=class extends Q{constructor(){super();c(this,gt,void 0);h(this,gt,this.shadowRoot.querySelector("slot")),s(this,gt).innerHTML=`${ds(this)}`}static get observedAttributes(){return[...super.observedAttributes,...Yl,"disabled"]}connectedCallback(){let{style:e}=O(this.shadowRoot,":host(:hover:not([notoggle]))");e.setProperty("cursor","var(--media-cursor, pointer)"),e.setProperty("background","var(--media-control-hover-background, rgba(50 50 70 / .7))"),this.hasAttribute("disabled")||this.enable(),this.setAttribute("role","progressbar"),this.setAttribute("aria-label",g("playback time"));let r=n=>{let{key:o}=n;if(!jl.includes(o)){this.removeEventListener("keyup",r);return}this.toggleTimeDisplay()};this.addEventListener("keydown",n=>{let{metaKey:o,altKey:l,key:d}=n;if(o||l||!jl.includes(d)){this.removeEventListener("keyup",r);return}this.addEventListener("keyup",r)}),this.addEventListener("click",this.toggleTimeDisplay),super.connectedCallback()}toggleTimeDisplay(){this.noToggle||(this.hasAttribute("remaining")?this.removeAttribute("remaining"):this.setAttribute("remaining",""))}disconnectedCallback(){this.disable(),super.disconnectedCallback()}attributeChangedCallback(e,r,n){Yl.includes(e)?this.update():e==="disabled"&&n!==r&&(n==null?this.enable():this.disable()),super.attributeChangedCallback(e,r,n)}enable(){this.tabIndex=0}disable(){this.tabIndex=-1}get remaining(){return I(this,Et.REMAINING)}set remaining(e){M(this,Et.REMAINING,e)}get showDuration(){return I(this,Et.SHOW_DURATION)}set showDuration(e){M(this,Et.SHOW_DURATION,e)}get noToggle(){return I(this,Et.NO_TOGGLE)}set noToggle(e){M(this,Et.NO_TOGGLE,e)}get mediaDuration(){return R(this,a.MEDIA_DURATION)}set mediaDuration(e){C(this,a.MEDIA_DURATION,e)}get mediaCurrentTime(){return R(this,a.MEDIA_CURRENT_TIME)}set mediaCurrentTime(e){C(this,a.MEDIA_CURRENT_TIME,e)}get mediaSeekable(){let e=this.getAttribute(a.MEDIA_SEEKABLE);if(e)return e.split(":").map(r=>+r)}set mediaSeekable(e){if(e==null){this.removeAttribute(a.MEDIA_SEEKABLE);return}this.setAttribute(a.MEDIA_SEEKABLE,e.join(":"))}update(){let e=ds(this);Nc(this),e!==s(this,gt).innerHTML&&(s(this,gt).innerHTML=e)}};gt=new WeakMap,yr.getSlotTemplateHTML=Fc;u.customElements.get("media-time-display")||u.customElements.define("media-time-display",yr);var Ql=yr;var bt,Sr,ft,vi,Ir,Mr,Lr,vt,We,kr,Xn=class{constructor(i,e,r){c(this,bt,void 0);c(this,Sr,void 0);c(this,ft,void 0);c(this,vi,void 0);c(this,Ir,void 0);c(this,Mr,void 0);c(this,Lr,void 0);c(this,vt,void 0);c(this,We,0);c(this,kr,(i=performance.now())=>{h(this,We,requestAnimationFrame(s(this,kr))),h(this,vi,performance.now()-s(this,ft));let e=1e3/this.fps;if(s(this,vi)>e){h(this,ft,i-s(this,vi)%e);let r=1e3/((i-s(this,Sr))/++la(this,Ir)._),n=(i-s(this,Mr))/1e3/this.duration,o=s(this,Lr)+n*this.playbackRate;o-s(this,bt).valueAsNumber>0?h(this,vt,this.playbackRate/this.duration/r):(h(this,vt,.995*s(this,vt)),o=s(this,bt).valueAsNumber+s(this,vt)),this.callback(o)}});h(this,bt,i),this.callback=e,this.fps=r}start(){s(this,We)===0&&(h(this,ft,performance.now()),h(this,Sr,s(this,ft)),h(this,Ir,0),s(this,kr).call(this))}stop(){s(this,We)!==0&&(cancelAnimationFrame(s(this,We)),h(this,We,0))}update({start:i,duration:e,playbackRate:r}){let n=i-s(this,bt).valueAsNumber,o=Math.abs(e-this.duration);(n>0||n<-.03||o>=.5)&&this.callback(i),h(this,Lr,i),h(this,Mr,performance.now()),this.duration=e,this.playbackRate=r}};bt=new WeakMap,Sr=new WeakMap,ft=new WeakMap,vi=new WeakMap,Ir=new WeakMap,Mr=new WeakMap,Lr=new WeakMap,vt=new WeakMap,We=new WeakMap,kr=new WeakMap;var $c="video not loaded, unknown time.",Bc=t=>{let i=t.range,e=Ne(+zl(t)),r=Ne(+t.mediaSeekableEnd),n=e&&r?`${e} of ${r}`:$c;i.setAttribute("aria-valuetext",n)};function Vc(t){return`
    ${de.getTemplateHTML(t)}
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

      :host(:is([${a.MEDIA_PREVIEW_IMAGE}], [${a.MEDIA_PREVIEW_TIME}])[dragging]) [part~="preview-box"] {
        transition-duration: var(--media-preview-transition-duration-in, .5s);
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        visibility: visible;
        opacity: 1;
      }

      @media (hover: hover) {
        :host(:is([${a.MEDIA_PREVIEW_IMAGE}], [${a.MEDIA_PREVIEW_TIME}]):hover) [part~="preview-box"] {
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

      :host([${a.MEDIA_PREVIEW_IMAGE}][dragging]) media-preview-thumbnail,
      :host([${a.MEDIA_PREVIEW_IMAGE}][dragging]) ::slotted(media-preview-thumbnail) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        visibility: visible;
      }

      @media (hover: hover) {
        :host([${a.MEDIA_PREVIEW_IMAGE}]:hover) media-preview-thumbnail,
        :host([${a.MEDIA_PREVIEW_IMAGE}]:hover) ::slotted(media-preview-thumbnail) {
          transition-delay: var(--media-preview-transition-delay-in, .25s);
          visibility: visible;
        }

        :host([${a.MEDIA_PREVIEW_TIME}]:hover) {
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

      :host([${a.MEDIA_PREVIEW_IMAGE}]) media-preview-chapter-display,
      :host([${a.MEDIA_PREVIEW_IMAGE}]) ::slotted(media-preview-chapter-display) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        border-radius: var(--media-preview-chapter-border-radius, 0);
        padding: var(--media-preview-chapter-padding, 3.5px 9px 0);
        margin: var(--media-preview-chapter-margin, 0);
        min-width: 100%;
      }

      media-preview-chapter-display[${a.MEDIA_PREVIEW_CHAPTER}],
      ::slotted(media-preview-chapter-display[${a.MEDIA_PREVIEW_CHAPTER}]) {
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

      :host([${a.MEDIA_PREVIEW_IMAGE}]) media-preview-time-display,
      :host([${a.MEDIA_PREVIEW_IMAGE}]) ::slotted(media-preview-time-display) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        border-radius: var(--media-preview-time-border-radius,
          0 0 var(--media-preview-border-radius) var(--media-preview-border-radius));
        min-width: 100%;
      }

      :host([${a.MEDIA_PREVIEW_TIME}]:hover) {
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
          <template shadowrootmode="${Ar.shadowRootOptions.mode}">
            ${Ar.getTemplateHTML({})}
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
  `}var Jn=(t,i=t.mediaCurrentTime)=>{let e=Number.isFinite(t.mediaSeekableStart)?t.mediaSeekableStart:0,r=Number.isFinite(t.mediaDuration)?t.mediaDuration:t.mediaSeekableEnd;if(Number.isNaN(r))return 0;let n=(i-e)/(r-e);return Math.max(0,Math.min(n,1))},zl=(t,i=t.range.valueAsNumber)=>{let e=Number.isFinite(t.mediaSeekableStart)?t.mediaSeekableStart:0,r=Number.isFinite(t.mediaDuration)?t.mediaDuration:t.mediaSeekableEnd;return Number.isNaN(r)?0:i*(r-e)+e},Tt,Ye,Rr,Ai,xr,Cr,yi,Si,At,yt,_r,to,Zl,io,Dr,us,wr,cs,Pr,ms,ro,Xl,Ii,eo,no,Jl,Ti=class extends de{constructor(){super();c(this,yt);c(this,to);c(this,Dr);c(this,wr);c(this,Pr);c(this,ro);c(this,Ii);c(this,no);c(this,Tt,void 0);c(this,Ye,void 0);c(this,Rr,void 0);c(this,Ai,void 0);c(this,xr,void 0);c(this,Cr,void 0);c(this,yi,void 0);c(this,Si,void 0);c(this,At,void 0);c(this,io,e=>{this.dragging||(Vt(e)&&(this.range.valueAsNumber=e),this.updateBar())});this.shadowRoot.querySelector("#track").insertAdjacentHTML("afterbegin",'<div id="buffered" part="buffered"></div>'),h(this,Rr,this.shadowRoot.querySelectorAll('[part~="box"]')),h(this,xr,this.shadowRoot.querySelector('[part~="preview-box"]')),h(this,Cr,this.shadowRoot.querySelector('[part~="current-box"]'));let r=getComputedStyle(this);h(this,yi,parseInt(r.getPropertyValue("--media-box-padding-left"))),h(this,Si,parseInt(r.getPropertyValue("--media-box-padding-right"))),h(this,Ye,new Xn(this.range,s(this,io),60))}static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_PAUSED,a.MEDIA_DURATION,a.MEDIA_SEEKABLE,a.MEDIA_CURRENT_TIME,a.MEDIA_PREVIEW_IMAGE,a.MEDIA_PREVIEW_TIME,a.MEDIA_PREVIEW_CHAPTER,a.MEDIA_BUFFERED,a.MEDIA_PLAYBACK_RATE,a.MEDIA_LOADING,a.MEDIA_ENDED]}connectedCallback(){var e;super.connectedCallback(),this.range.setAttribute("aria-label",g("seek")),p(this,yt,_r).call(this),h(this,Tt,this.getRootNode()),(e=s(this,Tt))==null||e.addEventListener("transitionstart",this)}disconnectedCallback(){var e;super.disconnectedCallback(),p(this,yt,_r).call(this),(e=s(this,Tt))==null||e.removeEventListener("transitionstart",this),h(this,Tt,null)}attributeChangedCallback(e,r,n){super.attributeChangedCallback(e,r,n),r!=n&&(e===a.MEDIA_CURRENT_TIME||e===a.MEDIA_PAUSED||e===a.MEDIA_ENDED||e===a.MEDIA_LOADING||e===a.MEDIA_DURATION||e===a.MEDIA_SEEKABLE?(s(this,Ye).update({start:Jn(this),duration:this.mediaSeekableEnd-this.mediaSeekableStart,playbackRate:this.mediaPlaybackRate}),p(this,yt,_r).call(this),Bc(this)):e===a.MEDIA_BUFFERED&&this.updateBufferedBar(),(e===a.MEDIA_DURATION||e===a.MEDIA_SEEKABLE)&&(this.mediaChaptersCues=s(this,At),this.updateBar()))}get mediaChaptersCues(){return s(this,At)}set mediaChaptersCues(e){var r;h(this,At,e),this.updateSegments((r=s(this,At))==null?void 0:r.map(n=>({start:Jn(this,n.startTime),end:Jn(this,n.endTime)})))}get mediaPaused(){return I(this,a.MEDIA_PAUSED)}set mediaPaused(e){M(this,a.MEDIA_PAUSED,e)}get mediaLoading(){return I(this,a.MEDIA_LOADING)}set mediaLoading(e){M(this,a.MEDIA_LOADING,e)}get mediaDuration(){return R(this,a.MEDIA_DURATION)}set mediaDuration(e){C(this,a.MEDIA_DURATION,e)}get mediaCurrentTime(){return R(this,a.MEDIA_CURRENT_TIME)}set mediaCurrentTime(e){C(this,a.MEDIA_CURRENT_TIME,e)}get mediaPlaybackRate(){return R(this,a.MEDIA_PLAYBACK_RATE,1)}set mediaPlaybackRate(e){C(this,a.MEDIA_PLAYBACK_RATE,e)}get mediaBuffered(){let e=this.getAttribute(a.MEDIA_BUFFERED);return e?e.split(" ").map(r=>r.split(":").map(n=>+n)):[]}set mediaBuffered(e){if(!e){this.removeAttribute(a.MEDIA_BUFFERED);return}let r=e.map(n=>n.join(":")).join(" ");this.setAttribute(a.MEDIA_BUFFERED,r)}get mediaSeekable(){let e=this.getAttribute(a.MEDIA_SEEKABLE);if(e)return e.split(":").map(r=>+r)}set mediaSeekable(e){if(e==null){this.removeAttribute(a.MEDIA_SEEKABLE);return}this.setAttribute(a.MEDIA_SEEKABLE,e.join(":"))}get mediaSeekableEnd(){var r;let[,e=this.mediaDuration]=(r=this.mediaSeekable)!=null?r:[];return e}get mediaSeekableStart(){var r;let[e=0]=(r=this.mediaSeekable)!=null?r:[];return e}get mediaPreviewImage(){return L(this,a.MEDIA_PREVIEW_IMAGE)}set mediaPreviewImage(e){k(this,a.MEDIA_PREVIEW_IMAGE,e)}get mediaPreviewTime(){return R(this,a.MEDIA_PREVIEW_TIME)}set mediaPreviewTime(e){C(this,a.MEDIA_PREVIEW_TIME,e)}get mediaEnded(){return I(this,a.MEDIA_ENDED)}set mediaEnded(e){M(this,a.MEDIA_ENDED,e)}updateBar(){super.updateBar(),this.updateBufferedBar(),this.updateCurrentBox()}updateBufferedBar(){var o;let e=this.mediaBuffered;if(!e.length)return;let r;if(this.mediaEnded)r=1;else{let l=this.mediaCurrentTime,[,d=this.mediaSeekableStart]=(o=e.find(([m,E])=>m<=l&&l<=E))!=null?o:[];r=Jn(this,d)}let{style:n}=O(this.shadowRoot,"#buffered");n.setProperty("width",`${r*100}%`)}updateCurrentBox(){if(!this.shadowRoot.querySelector('slot[name="current"]').assignedElements().length)return;let r=O(this.shadowRoot,"#current-rail"),n=O(this.shadowRoot,'[part~="current-box"]'),o=p(this,Dr,us).call(this,s(this,Cr)),l=p(this,wr,cs).call(this,o,this.range.valueAsNumber),d=p(this,Pr,ms).call(this,o,this.range.valueAsNumber);r.style.transform=`translateX(${l})`,r.style.setProperty("--_range-width",`${o.range.width}`),n.style.setProperty("--_box-shift",`${d}`),n.style.setProperty("--_box-width",`${o.box.width}px`),n.style.setProperty("visibility","initial")}handleEvent(e){switch(super.handleEvent(e),e.type){case"input":p(this,no,Jl).call(this);break;case"pointermove":p(this,ro,Xl).call(this,e);break;case"pointerup":case"pointerleave":p(this,Ii,eo).call(this,null);break;case"transitionstart":Y(e.target,this)&&setTimeout(()=>p(this,yt,_r).call(this),0);break}}};Tt=new WeakMap,Ye=new WeakMap,Rr=new WeakMap,Ai=new WeakMap,xr=new WeakMap,Cr=new WeakMap,yi=new WeakMap,Si=new WeakMap,At=new WeakMap,yt=new WeakSet,_r=function(){p(this,to,Zl).call(this)?s(this,Ye).start():s(this,Ye).stop()},to=new WeakSet,Zl=function(){return this.isConnected&&!this.mediaPaused&&!this.mediaLoading&&!this.mediaEnded&&this.mediaSeekableEnd>0&&Mn(this)},io=new WeakMap,Dr=new WeakSet,us=function(e){var E;let n=((E=this.getAttribute("bounds")?Te(this,`#${this.getAttribute("bounds")}`):this.parentElement)!=null?E:this).getBoundingClientRect(),o=this.range.getBoundingClientRect(),l=e.offsetWidth,d=-(o.left-n.left-l/2),m=n.right-o.left-l/2;return{box:{width:l,min:d,max:m},bounds:n,range:o}},wr=new WeakSet,cs=function(e,r){let n=`${r*100}%`,{width:o,min:l,max:d}=e.box;if(!o)return n;if(Number.isNaN(l)||(n=`max(${`calc(1 / var(--_range-width) * 100 * ${l}% + var(--media-box-padding-left))`}, ${n})`),!Number.isNaN(d)){let E=`calc(1 / var(--_range-width) * 100 * ${d}% - var(--media-box-padding-right))`;n=`min(${n}, ${E})`}return n},Pr=new WeakSet,ms=function(e,r){let{width:n,min:o,max:l}=e.box,d=r*e.range.width;if(d<o+s(this,yi)){let m=e.range.left-e.bounds.left-s(this,yi);return`${d-n/2+m}px`}if(d>l-s(this,Si)){let m=e.bounds.right-e.range.right-s(this,Si);return`${d+n/2-m-e.range.width}px`}return 0},ro=new WeakSet,Xl=function(e){let r=[...s(this,Rr)].some(v=>e.composedPath().includes(v));if(!this.dragging&&(r||!e.composedPath().includes(this))){p(this,Ii,eo).call(this,null);return}let n=this.mediaSeekableEnd;if(!n)return;let o=O(this.shadowRoot,"#preview-rail"),l=O(this.shadowRoot,'[part~="preview-box"]'),d=p(this,Dr,us).call(this,s(this,xr)),m=(e.clientX-d.range.left)/d.range.width;m=Math.max(0,Math.min(1,m));let E=p(this,wr,cs).call(this,d,m),T=p(this,Pr,ms).call(this,d,m);o.style.transform=`translateX(${E})`,o.style.setProperty("--_range-width",`${d.range.width}`),l.style.setProperty("--_box-shift",`${T}`),l.style.setProperty("--_box-width",`${d.box.width}px`);let y=Math.round(s(this,Ai))-Math.round(m*n);Math.abs(y)<1&&m>.01&&m<.99||(h(this,Ai,m*n),p(this,Ii,eo).call(this,s(this,Ai)))},Ii=new WeakSet,eo=function(e){this.dispatchEvent(new u.CustomEvent(b.MEDIA_PREVIEW_REQUEST,{composed:!0,bubbles:!0,detail:e}))},no=new WeakSet,Jl=function(){s(this,Ye).stop();let e=zl(this);this.dispatchEvent(new u.CustomEvent(b.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:e}))},Ti.shadowRootOptions={mode:"open"},Ti.getTemplateHTML=Vc;u.customElements.get("media-time-range")||u.customElements.define("media-time-range",Ti);var ed=Ti;var Kc=1,Gc=t=>t.mediaMuted?0:t.mediaVolume,qc=t=>`${Math.round(t*100)}%`,oo=class extends de{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_VOLUME,a.MEDIA_MUTED,a.MEDIA_VOLUME_UNAVAILABLE]}constructor(){super(),this.range.addEventListener("input",()=>{let i=this.range.value,e=new u.CustomEvent(b.MEDIA_VOLUME_REQUEST,{composed:!0,bubbles:!0,detail:i});this.dispatchEvent(e)})}connectedCallback(){super.connectedCallback(),this.range.setAttribute("aria-label",g("volume"))}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),(i===a.MEDIA_VOLUME||i===a.MEDIA_MUTED)&&(this.range.valueAsNumber=Gc(this),this.range.setAttribute("aria-valuetext",qc(this.range.valueAsNumber)),this.updateBar())}get mediaVolume(){return R(this,a.MEDIA_VOLUME,Kc)}set mediaVolume(i){C(this,a.MEDIA_VOLUME,i)}get mediaMuted(){return I(this,a.MEDIA_MUTED)}set mediaMuted(i){M(this,a.MEDIA_MUTED,i)}get mediaVolumeUnavailable(){return L(this,a.MEDIA_VOLUME_UNAVAILABLE)}set mediaVolumeUnavailable(i){k(this,a.MEDIA_VOLUME_UNAVAILABLE,i)}};u.customElements.get("media-volume-range")||u.customElements.define("media-volume-range",oo);var td=oo;function id({anchor:t,floating:i,placement:e}){let r=Wc({anchor:t,floating:i}),{x:n,y:o}=jc(r,e);return{x:n,y:o}}function Wc({anchor:t,floating:i}){return{anchor:Yc(t,i.offsetParent),floating:{x:0,y:0,width:i.offsetWidth,height:i.offsetHeight}}}function Yc(t,i){var n;let e=t.getBoundingClientRect(),r=(n=i==null?void 0:i.getBoundingClientRect())!=null?n:{x:0,y:0};return{x:e.x-r.x,y:e.y-r.y,width:e.width,height:e.height}}function jc({anchor:t,floating:i},e){let r=Qc(e)==="x"?"y":"x",n=r==="y"?"height":"width",o=rd(e),l=t.x+t.width/2-i.width/2,d=t.y+t.height/2-i.height/2,m=t[n]/2-i[n]/2,E;switch(o){case"top":E={x:l,y:t.y-i.height};break;case"bottom":E={x:l,y:t.y+t.height};break;case"right":E={x:t.x+t.width,y:d};break;case"left":E={x:t.x-i.width,y:d};break;default:E={x:t.x,y:t.y}}switch(e.split("-")[1]){case"start":E[r]-=m;break;case"end":E[r]+=m;break}return E}function rd(t){return t.split("-")[0]}function Qc(t){return["top","bottom"].includes(rd(t))?"y":"x"}var je=class extends Event{constructor({action:i="auto",relatedTarget:e,...r}){super("invoke",r),this.action=i,this.relatedTarget=e}},so=class extends Event{constructor({newState:i,oldState:e,...r}){super("toggle",r),this.newState=i,this.oldState=e}};function Se({type:t,text:i,value:e,checked:r}){let n=F.createElement("media-chrome-menu-item");n.type=t!=null?t:"",n.part.add("menu-item"),t&&n.part.add(t),n.value=e,n.checked=r;let o=F.createElement("span");return o.textContent=i,n.append(o),n}function Ee(t,i){let e=t.querySelector(`:scope > [slot="${i}"]`);if((e==null?void 0:e.nodeName)=="SLOT"&&(e=e.assignedElements({flatten:!0})[0]),e)return e=e.cloneNode(!0),e;let r=t.shadowRoot.querySelector(`[name="${i}"] > svg`);return r?r.cloneNode(!0):""}function zc(t){return`
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
  `}var St={STYLE:"style",HIDDEN:"hidden",DISABLED:"disabled",ANCHOR:"anchor"},pe,Qe,De,Hr,Nr,ze,Mi,uo,nd,Fr,$r,hs,co,od,mo,sd,ho,ad,It,Mt,Lt,Or,Br,ps,po,ld,Eo,dd,go,ud,bo,cd,fo,md,vo,hd,Li,ao,To,pd,ki,lo,Vr,Es,K=class extends u.HTMLElement{constructor(){super();c(this,uo);c(this,$r);c(this,co);c(this,mo);c(this,ho);c(this,Lt);c(this,Br);c(this,po);c(this,Eo);c(this,go);c(this,bo);c(this,fo);c(this,vo);c(this,Li);c(this,To);c(this,ki);c(this,Vr);c(this,pe,null);c(this,Qe,null);c(this,De,null);c(this,Hr,new Set);c(this,Nr,void 0);c(this,ze,!1);c(this,Mi,null);c(this,Fr,()=>{let e=s(this,Hr),r=new Set(this.items);for(let n of e)r.has(n)||this.dispatchEvent(new CustomEvent("removemenuitem",{detail:n}));for(let n of r)e.has(n)||this.dispatchEvent(new CustomEvent("addmenuitem",{detail:n}));h(this,Hr,r)});c(this,It,()=>{p(this,Lt,Or).call(this),p(this,Br,ps).call(this,!1)});c(this,Mt,()=>{p(this,Lt,Or).call(this)});if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}this.container=this.shadowRoot.querySelector("#container"),this.defaultSlot=this.shadowRoot.querySelector("slot:not([name])"),this.shadowRoot.addEventListener("slotchange",this),h(this,Nr,new MutationObserver(s(this,Fr))),s(this,Nr).observe(this.defaultSlot,{childList:!0})}static get observedAttributes(){return[St.DISABLED,St.HIDDEN,St.STYLE,St.ANCHOR,_.MEDIA_CONTROLLER]}static formatMenuItemText(e,r){return e}enable(){this.addEventListener("click",this),this.addEventListener("focusout",this),this.addEventListener("keydown",this),this.addEventListener("invoke",this),this.addEventListener("toggle",this)}disable(){this.removeEventListener("click",this),this.removeEventListener("focusout",this),this.removeEventListener("keyup",this),this.removeEventListener("invoke",this),this.removeEventListener("toggle",this)}handleEvent(e){switch(e.type){case"slotchange":p(this,uo,nd).call(this,e);break;case"invoke":p(this,co,od).call(this,e);break;case"click":p(this,po,ld).call(this,e);break;case"toggle":p(this,go,ud).call(this,e);break;case"focusout":p(this,fo,md).call(this,e);break;case"keydown":p(this,vo,hd).call(this,e);break}}connectedCallback(){var e,r;h(this,Mi,Ko(this.shadowRoot,":host")),p(this,$r,hs).call(this),this.hasAttribute("disabled")||this.enable(),this.role||(this.role="menu"),h(this,pe,yn(this)),(r=(e=s(this,pe))==null?void 0:e.associateElement)==null||r.call(e,this),this.hidden||(Le(Ur(this),s(this,It)),Le(this,s(this,Mt)))}disconnectedCallback(){var e,r;ke(Ur(this),s(this,It)),ke(this,s(this,Mt)),this.disable(),(r=(e=s(this,pe))==null?void 0:e.unassociateElement)==null||r.call(e,this),h(this,pe,null)}attributeChangedCallback(e,r,n){var o,l,d,m;e===St.HIDDEN&&n!==r?(s(this,ze)||h(this,ze,!0),this.hidden?p(this,ho,ad).call(this):p(this,mo,sd).call(this),this.dispatchEvent(new so({oldState:this.hidden?"open":"closed",newState:this.hidden?"closed":"open",bubbles:!0}))):e===_.MEDIA_CONTROLLER?(r&&((l=(o=s(this,pe))==null?void 0:o.unassociateElement)==null||l.call(o,this),h(this,pe,null)),n&&this.isConnected&&(h(this,pe,yn(this)),(m=(d=s(this,pe))==null?void 0:d.associateElement)==null||m.call(d,this))):e===St.DISABLED&&n!==r?n==null?this.enable():this.disable():e===St.STYLE&&n!==r&&p(this,$r,hs).call(this)}formatMenuItemText(e,r){return this.constructor.formatMenuItemText(e,r)}get anchor(){return this.getAttribute("anchor")}set anchor(e){this.setAttribute("anchor",`${e}`)}get anchorElement(){var e;return this.anchor?(e=tt(this))==null?void 0:e.querySelector(`#${this.anchor}`):null}get items(){return this.defaultSlot.assignedElements({flatten:!0}).filter(Zc)}get radioGroupItems(){return this.items.filter(e=>e.role==="menuitemradio")}get checkedItems(){return this.items.filter(e=>e.checked)}get value(){var e,r;return(r=(e=this.checkedItems[0])==null?void 0:e.value)!=null?r:""}set value(e){let r=this.items.find(n=>n.value===e);r&&p(this,Vr,Es).call(this,r)}focus(){if(h(this,Qe,Bi()),this.items.length){p(this,ki,lo).call(this,this.items[0]),this.items[0].focus();return}let e=this.querySelector('[autofocus], [tabindex]:not([tabindex="-1"]), [role="menu"]');e==null||e.focus()}handleSelect(e){var n;let r=p(this,Li,ao).call(this,e);r&&(p(this,Vr,Es).call(this,r,r.type==="checkbox"),s(this,De)&&!this.hidden&&((n=s(this,Qe))==null||n.focus(),this.hidden=!0))}get keysUsed(){return["Enter","Escape","Tab"," ","ArrowDown","ArrowUp","Home","End"]}handleMove(e){var m,E;let{key:r}=e,n=this.items,o=(E=(m=p(this,Li,ao).call(this,e))!=null?m:p(this,To,pd).call(this))!=null?E:n[0],l=n.indexOf(o),d=Math.max(0,l);r==="ArrowDown"?d++:r==="ArrowUp"?d--:e.key==="Home"?d=0:e.key==="End"&&(d=n.length-1),d<0&&(d=n.length-1),d>n.length-1&&(d=0),p(this,ki,lo).call(this,n[d]),n[d].focus()}};pe=new WeakMap,Qe=new WeakMap,De=new WeakMap,Hr=new WeakMap,Nr=new WeakMap,ze=new WeakMap,Mi=new WeakMap,uo=new WeakSet,nd=function(e){let r=e.target;for(let n of r.assignedNodes({flatten:!0}))n.nodeType===3&&n.textContent.trim()===""&&n.remove();if(["header","title"].includes(r.name)){let n=this.shadowRoot.querySelector('slot[name="header"]');n.hidden=r.assignedNodes().length===0}r.name||s(this,Fr).call(this)},Fr=new WeakMap,$r=new WeakSet,hs=function(){var n;let e=this.shadowRoot.querySelector("#layout-row"),r=(n=getComputedStyle(this).getPropertyValue("--media-menu-layout"))==null?void 0:n.trim();e.setAttribute("media",r==="row"?"":"width:0")},co=new WeakSet,od=function(e){h(this,De,e.relatedTarget),Y(this,e.relatedTarget)||(this.hidden=!this.hidden)},mo=new WeakSet,sd=function(){var e;(e=s(this,De))==null||e.setAttribute("aria-expanded","true"),this.addEventListener("transitionend",()=>this.focus(),{once:!0}),Le(Ur(this),s(this,It)),Le(this,s(this,Mt))},ho=new WeakSet,ad=function(){var e;(e=s(this,De))==null||e.setAttribute("aria-expanded","false"),ke(Ur(this),s(this,It)),ke(this,s(this,Mt))},It=new WeakMap,Mt=new WeakMap,Lt=new WeakSet,Or=function(e){if(this.hasAttribute("mediacontroller")&&!this.anchor||this.hidden||!this.anchorElement)return;let{x:r,y:n}=id({anchor:this.anchorElement,floating:this,placement:"top-start"});e!=null||(e=this.offsetWidth);let l=Ur(this).getBoundingClientRect(),d=l.width-r-e,m=l.height-n-this.offsetHeight,{style:E}=s(this,Mi);E.setProperty("position","absolute"),E.setProperty("right",`${Math.max(0,d)}px`),E.setProperty("--_menu-bottom",`${m}px`);let T=getComputedStyle(this),v=E.getPropertyValue("--_menu-bottom")===T.bottom?m:parseFloat(T.bottom),f=l.height-v-parseFloat(T.marginBottom);this.style.setProperty("--_menu-max-height",`${f}px`)},Br=new WeakSet,ps=function(e){let r=this.querySelector('[role="menuitem"][aria-haspopup][aria-expanded="true"]'),n=r==null?void 0:r.querySelector('[role="menu"]'),{style:o}=s(this,Mi);if(e||o.setProperty("--media-menu-transition-in","none"),n){let l=n.offsetHeight,d=Math.max(n.offsetWidth,r.offsetWidth);this.style.setProperty("min-width",`${d}px`),this.style.setProperty("min-height",`${l}px`),p(this,Lt,Or).call(this,d)}else this.style.removeProperty("min-width"),this.style.removeProperty("min-height"),p(this,Lt,Or).call(this);o.removeProperty("--media-menu-transition-in")},po=new WeakSet,ld=function(e){var n;if(e.stopPropagation(),e.composedPath().includes(s(this,Eo,dd))){(n=s(this,Qe))==null||n.focus(),this.hidden=!0;return}let r=p(this,Li,ao).call(this,e);!r||r.hasAttribute("disabled")||(p(this,ki,lo).call(this,r),this.handleSelect(e))},Eo=new WeakSet,dd=function(){var r;return(r=this.shadowRoot.querySelector('slot[name="header"]').assignedElements({flatten:!0}))==null?void 0:r.find(n=>n.matches('button[part~="back"]'))},go=new WeakSet,ud=function(e){if(e.target===this)return;p(this,bo,cd).call(this);let r=Array.from(this.querySelectorAll('[role="menuitem"][aria-haspopup]'));for(let n of r)n.invokeTargetElement!=e.target&&e.newState=="open"&&n.getAttribute("aria-expanded")=="true"&&!n.invokeTargetElement.hidden&&n.invokeTargetElement.dispatchEvent(new je({relatedTarget:n}));for(let n of r)n.setAttribute("aria-expanded",`${!n.submenuElement.hidden}`);p(this,Br,ps).call(this,!0)},bo=new WeakSet,cd=function(){let r=this.querySelector('[role="menuitem"] > [role="menu"]:not([hidden])');this.container.classList.toggle("has-expanded",!!r)},fo=new WeakSet,md=function(e){var r;Y(this,e.relatedTarget)||(s(this,ze)&&((r=s(this,Qe))==null||r.focus()),s(this,De)&&s(this,De)!==e.relatedTarget&&!this.hidden&&(this.hidden=!0))},vo=new WeakSet,hd=function(e){var d,m,E,T,y;let{key:r,ctrlKey:n,altKey:o,metaKey:l}=e;if(!(n||o||l)&&this.keysUsed.includes(r))if(e.preventDefault(),e.stopPropagation(),r==="Tab"){if(s(this,ze)){this.hidden=!0;return}e.shiftKey?(m=(d=this.previousElementSibling)==null?void 0:d.focus)==null||m.call(d):(T=(E=this.nextElementSibling)==null?void 0:E.focus)==null||T.call(E),this.blur()}else r==="Escape"?((y=s(this,Qe))==null||y.focus(),s(this,ze)&&(this.hidden=!0)):r==="Enter"||r===" "?this.handleSelect(e):this.handleMove(e)},Li=new WeakSet,ao=function(e){return e.composedPath().find(r=>["menuitemradio","menuitemcheckbox"].includes(r.role))},To=new WeakSet,pd=function(){return this.items.find(e=>e.tabIndex===0)},ki=new WeakSet,lo=function(e){for(let r of this.items)r.tabIndex=r===e?0:-1},Vr=new WeakSet,Es=function(e,r){let n=[...this.checkedItems];e.type==="radio"&&this.radioGroupItems.forEach(o=>o.checked=!1),r?e.checked=!e.checked:e.checked=!0,this.checkedItems.some((o,l)=>o!=n[l])&&this.dispatchEvent(new Event("change",{bubbles:!0,composed:!0}))},K.shadowRootOptions={mode:"open"},K.getTemplateHTML=zc;function Zc(t){return["menuitem","menuitemradio","menuitemcheckbox"].includes(t==null?void 0:t.role)}function Ur(t){var i;return(i=t.getAttribute("bounds")?Te(t,`#${t.getAttribute("bounds")}`):B(t)||t.parentElement)!=null?i:t}u.customElements.get("media-chrome-menu")||u.customElements.define("media-chrome-menu",K);function Xc(t){return`
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
      ${this.getSuffixSlotInnerHTML(t)}
    </slot>
    <slot name="submenu"></slot>
  `}function Jc(t){return""}var re={TYPE:"type",VALUE:"value",CHECKED:"checked",DISABLED:"disabled"},qr,_i,Ao,Ed,yo,gd,So,bd,ge,kt,Gr,Io,fd,Wr,gs,be=class extends u.HTMLElement{constructor(){super();c(this,Ao);c(this,yo);c(this,So);c(this,kt);c(this,Io);c(this,Wr);c(this,qr,!1);c(this,_i,void 0);c(this,ge,()=>{var l,d;this.setAttribute("submenusize",`${this.submenuElement.items.length}`);let e=this.shadowRoot.querySelector('slot[name="description"]'),r=(l=this.submenuElement.checkedItems)==null?void 0:l[0],n=(d=r==null?void 0:r.dataset.description)!=null?d:r==null?void 0:r.text,o=F.createElement("span");o.textContent=n!=null?n:"",e.replaceChildren(o)});if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=$(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}this.shadowRoot.addEventListener("slotchange",this)}static get observedAttributes(){return[re.TYPE,re.DISABLED,re.CHECKED,re.VALUE]}enable(){this.hasAttribute("tabindex")||this.setAttribute("tabindex","-1"),Kr(this)&&!this.hasAttribute("aria-checked")&&this.setAttribute("aria-checked","false"),this.addEventListener("click",this),this.addEventListener("keydown",this)}disable(){this.removeAttribute("tabindex"),this.removeEventListener("click",this),this.removeEventListener("keydown",this),this.removeEventListener("keyup",this)}handleEvent(e){switch(e.type){case"slotchange":p(this,Ao,Ed).call(this,e);break;case"click":this.handleClick(e);break;case"keydown":p(this,Io,fd).call(this,e);break;case"keyup":p(this,kt,Gr).call(this,e);break}}attributeChangedCallback(e,r,n){e===re.CHECKED&&Kr(this)&&!s(this,qr)?this.setAttribute("aria-checked",n!=null?"true":"false"):e===re.TYPE&&n!==r?this.role="menuitem"+n:e===re.DISABLED&&n!==r&&(n==null?this.enable():this.disable())}connectedCallback(){this.hasAttribute(re.DISABLED)||this.enable(),this.role="menuitem"+this.type,h(this,_i,bs(this,this.parentNode)),p(this,Wr,gs).call(this)}disconnectedCallback(){this.disable(),p(this,Wr,gs).call(this),h(this,_i,null)}get invokeTarget(){return this.getAttribute("invoketarget")}set invokeTarget(e){this.setAttribute("invoketarget",`${e}`)}get invokeTargetElement(){var e;return this.invokeTarget?(e=tt(this))==null?void 0:e.querySelector(`#${this.invokeTarget}`):this.submenuElement}get submenuElement(){return this.shadowRoot.querySelector('slot[name="submenu"]').assignedElements({flatten:!0})[0]}get type(){var e;return(e=this.getAttribute(re.TYPE))!=null?e:""}set type(e){this.setAttribute(re.TYPE,`${e}`)}get value(){var e;return(e=this.getAttribute(re.VALUE))!=null?e:this.text}set value(e){this.setAttribute(re.VALUE,e)}get text(){var e;return((e=this.textContent)!=null?e:"").trim()}get checked(){if(Kr(this))return this.getAttribute("aria-checked")==="true"}set checked(e){Kr(this)&&(h(this,qr,!0),this.setAttribute("aria-checked",e?"true":"false"),e?this.part.add("checked"):this.part.remove("checked"))}handleClick(e){Kr(this)||this.invokeTargetElement&&Y(this,e.target)&&this.invokeTargetElement.dispatchEvent(new je({relatedTarget:this}))}get keysUsed(){return["Enter"," "]}};qr=new WeakMap,_i=new WeakMap,Ao=new WeakSet,Ed=function(e){let r=e.target;if(!(r!=null&&r.name))for(let o of r.assignedNodes({flatten:!0}))o instanceof Text&&o.textContent.trim()===""&&o.remove();r.name==="submenu"&&(this.submenuElement?p(this,yo,gd).call(this):p(this,So,bd).call(this))},yo=new WeakSet,gd=async function(){this.setAttribute("aria-haspopup","menu"),this.setAttribute("aria-expanded",`${!this.submenuElement.hidden}`),this.submenuElement.addEventListener("change",s(this,ge)),this.submenuElement.addEventListener("addmenuitem",s(this,ge)),this.submenuElement.addEventListener("removemenuitem",s(this,ge)),s(this,ge).call(this)},So=new WeakSet,bd=function(){this.removeAttribute("aria-haspopup"),this.removeAttribute("aria-expanded"),this.submenuElement.removeEventListener("change",s(this,ge)),this.submenuElement.removeEventListener("addmenuitem",s(this,ge)),this.submenuElement.removeEventListener("removemenuitem",s(this,ge)),s(this,ge).call(this)},ge=new WeakMap,kt=new WeakSet,Gr=function(e){let{key:r}=e;if(!this.keysUsed.includes(r)){this.removeEventListener("keyup",p(this,kt,Gr));return}this.handleClick(e)},Io=new WeakSet,fd=function(e){let{metaKey:r,altKey:n,key:o}=e;if(r||n||!this.keysUsed.includes(o)){this.removeEventListener("keyup",p(this,kt,Gr));return}this.addEventListener("keyup",p(this,kt,Gr),{once:!0})},Wr=new WeakSet,gs=function(){var n;let e=(n=s(this,_i))==null?void 0:n.radioGroupItems;if(!e)return;let r=e.filter(o=>o.getAttribute("aria-checked")==="true").pop();r||(r=e[0]);for(let o of e)o.setAttribute("aria-checked","false");r==null||r.setAttribute("aria-checked","true")},be.shadowRootOptions={mode:"open"},be.getTemplateHTML=Xc,be.getSuffixSlotInnerHTML=Jc;function Kr(t){return t.type==="radio"||t.type==="checkbox"}function bs(t,i){if(!t)return null;let{host:e}=t.getRootNode();return!i&&e?bs(t,e):i!=null&&i.items?i:bs(i,i==null?void 0:i.parentNode)}u.customElements.get("media-chrome-menu-item")||u.customElements.define("media-chrome-menu-item",be);function em(t){return`
    ${K.getTemplateHTML(t)}
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
  `}var Ri=class extends K{get anchorElement(){return this.anchor!=="auto"?super.anchorElement:B(this).querySelector("media-settings-menu-button")}};Ri.getTemplateHTML=em;u.customElements.get("media-settings-menu")||u.customElements.define("media-settings-menu",Ri);function tm(t){return`
    ${be.getTemplateHTML.call(this,t)}
    <style>
      slot:not([name="submenu"]) {
        opacity: var(--media-settings-menu-item-opacity, var(--media-menu-item-opacity));
      }

      :host([aria-expanded="true"]:hover) {
        background: transparent;
      }
    </style>
  `}function im(t){return`
    <svg aria-hidden="true" viewBox="0 0 20 24">
      <path d="m8.12 17.585-.742-.669 4.2-4.665-4.2-4.666.743-.669 4.803 5.335-4.803 5.334Z"/>
    </svg>
  `}var Ze=class extends be{};Ze.shadowRootOptions={mode:"open"},Ze.getTemplateHTML=tm,Ze.getSuffixSlotInnerHTML=im;u.customElements.get("media-settings-menu-item")||u.customElements.define("media-settings-menu-item",Ze);var z=class extends P{connectedCallback(){super.connectedCallback(),this.invokeTargetElement&&this.setAttribute("aria-haspopup","menu")}get invokeTarget(){return this.getAttribute("invoketarget")}set invokeTarget(i){this.setAttribute("invoketarget",`${i}`)}get invokeTargetElement(){var i;return this.invokeTarget?(i=tt(this))==null?void 0:i.querySelector(`#${this.invokeTarget}`):null}handleClick(){var i;(i=this.invokeTargetElement)==null||i.dispatchEvent(new je({relatedTarget:this}))}};u.customElements.get("media-chrome-menu-button")||u.customElements.define("media-chrome-menu-button",z);function rm(){return`
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
  `}function nm(){return g("Settings")}var _t=class extends z{static get observedAttributes(){return[...super.observedAttributes,"target"]}connectedCallback(){super.connectedCallback(),this.setAttribute("aria-label",g("settings"))}get invokeTargetElement(){return this.invokeTarget!=null?super.invokeTargetElement:B(this).querySelector("media-settings-menu")}};_t.getSlotTemplateHTML=rm,_t.getTooltipContentHTML=nm;u.customElements.get("media-settings-menu-button")||u.customElements.define("media-settings-menu-button",_t);var xi,jr,Qr,fs,zr,vs,Yr=class extends K{constructor(){super(...arguments);c(this,Qr);c(this,zr);c(this,xi,[]);c(this,jr,void 0)}static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_AUDIO_TRACK_LIST,a.MEDIA_AUDIO_TRACK_ENABLED,a.MEDIA_AUDIO_TRACK_UNAVAILABLE]}attributeChangedCallback(e,r,n){super.attributeChangedCallback(e,r,n),e===a.MEDIA_AUDIO_TRACK_ENABLED&&r!==n?this.value=n:e===a.MEDIA_AUDIO_TRACK_LIST&&r!==n&&(h(this,xi,ha(n!=null?n:"")),p(this,Qr,fs).call(this))}connectedCallback(){super.connectedCallback(),this.addEventListener("change",p(this,zr,vs))}disconnectedCallback(){super.disconnectedCallback(),this.removeEventListener("change",p(this,zr,vs))}get anchorElement(){var e;return this.anchor!=="auto"?super.anchorElement:(e=B(this))==null?void 0:e.querySelector("media-audio-track-menu-button")}get mediaAudioTrackList(){return s(this,xi)}set mediaAudioTrackList(e){h(this,xi,e),p(this,Qr,fs).call(this)}get mediaAudioTrackEnabled(){var e;return(e=L(this,a.MEDIA_AUDIO_TRACK_ENABLED))!=null?e:""}set mediaAudioTrackEnabled(e){k(this,a.MEDIA_AUDIO_TRACK_ENABLED,e)}};xi=new WeakMap,jr=new WeakMap,Qr=new WeakSet,fs=function(){if(s(this,jr)===JSON.stringify(this.mediaAudioTrackList))return;h(this,jr,JSON.stringify(this.mediaAudioTrackList));let e=this.mediaAudioTrackList;this.defaultSlot.textContent="";for(let r of e){let n=this.formatMenuItemText(r.label,r),o=Se({type:"radio",text:n,value:`${r.id}`,checked:r.enabled});o.prepend(Ee(this,"checked-indicator")),this.defaultSlot.append(o)}},zr=new WeakSet,vs=function(){if(this.value==null)return;let e=new u.CustomEvent(b.MEDIA_AUDIO_TRACK_REQUEST,{composed:!0,bubbles:!0,detail:this.value});this.dispatchEvent(e)};u.customElements.get("media-audio-track-menu")||u.customElements.define("media-audio-track-menu",Yr);var om=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M11 17H9.5V7H11v10Zm-3-3H6.5v-4H8v4Zm6-5h-1.5v6H14V9Zm3 7h-1.5V8H17v8Z"/>
  <path d="M22 12c0 5.523-4.477 10-10 10S2 17.523 2 12 6.477 2 12 2s10 4.477 10 10Zm-2 0a8 8 0 1 0-16 0 8 8 0 0 0 16 0Z"/>
</svg>`;function sm(){return`
    <style>
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">${om}</slot>
  `}function am(){return g("Audio")}var Rt=class extends z{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_AUDIO_TRACK_ENABLED,a.MEDIA_AUDIO_TRACK_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),this.setAttribute("aria-label",g("Audio"))}get invokeTargetElement(){var i;return this.invokeTarget!=null?super.invokeTargetElement:(i=B(this))==null?void 0:i.querySelector("media-audio-track-menu")}get mediaAudioTrackEnabled(){var i;return(i=L(this,a.MEDIA_AUDIO_TRACK_ENABLED))!=null?i:""}set mediaAudioTrackEnabled(i){k(this,a.MEDIA_AUDIO_TRACK_ENABLED,i)}};Rt.getSlotTemplateHTML=sm,Rt.getTooltipContentHTML=am;u.customElements.get("media-audio-track-menu-button")||u.customElements.define("media-audio-track-menu-button",Rt);var lm=`
  <svg aria-hidden="true" viewBox="0 0 26 24" part="captions-indicator indicator">
    <path d="M22.83 5.68a2.58 2.58 0 0 0-2.3-2.5c-3.62-.24-11.44-.24-15.06 0a2.58 2.58 0 0 0-2.3 2.5c-.23 4.21-.23 8.43 0 12.64a2.58 2.58 0 0 0 2.3 2.5c3.62.24 11.44.24 15.06 0a2.58 2.58 0 0 0 2.3-2.5c.23-4.21.23-8.43 0-12.64Zm-11.39 9.45a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.92 3.92 0 0 1 .92-2.77 3.18 3.18 0 0 1 2.43-1 2.94 2.94 0 0 1 2.13.78c.364.359.62.813.74 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.17 1.61 1.61 0 0 0-1.29.58 2.79 2.79 0 0 0-.5 1.89 3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.48 1.48 0 0 0 1-.37 2.1 2.1 0 0 0 .59-1.14l1.4.44a3.23 3.23 0 0 1-1.07 1.69Zm7.22 0a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.88 3.88 0 0 1 .93-2.77 3.14 3.14 0 0 1 2.42-1 3 3 0 0 1 2.16.82 2.8 2.8 0 0 1 .73 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.21 1.61 1.61 0 0 0-1.29.58A2.79 2.79 0 0 0 15 12a3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.44 1.44 0 0 0 1-.37 2.1 2.1 0 0 0 .6-1.15l1.4.44a3.17 3.17 0 0 1-1.1 1.7Z"/>
  </svg>`;function dm(t){return`
    ${K.getTemplateHTML(t)}
    <slot name="captions-indicator" hidden>${lm}</slot>
  `}var Zr,Mo,Ad,Xr,Ts,Ci=class extends K{constructor(){super(...arguments);c(this,Mo);c(this,Xr);c(this,Zr,void 0)}static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_SUBTITLES_LIST,a.MEDIA_SUBTITLES_SHOWING]}attributeChangedCallback(e,r,n){super.attributeChangedCallback(e,r,n),e===a.MEDIA_SUBTITLES_LIST&&r!==n?p(this,Mo,Ad).call(this):e===a.MEDIA_SUBTITLES_SHOWING&&r!==n&&(this.value=n)}connectedCallback(){super.connectedCallback(),this.addEventListener("change",p(this,Xr,Ts))}disconnectedCallback(){super.disconnectedCallback(),this.removeEventListener("change",p(this,Xr,Ts))}get anchorElement(){return this.anchor!=="auto"?super.anchorElement:B(this).querySelector("media-captions-menu-button")}get mediaSubtitlesList(){return vd(this,a.MEDIA_SUBTITLES_LIST)}set mediaSubtitlesList(e){Td(this,a.MEDIA_SUBTITLES_LIST,e)}get mediaSubtitlesShowing(){return vd(this,a.MEDIA_SUBTITLES_SHOWING)}set mediaSubtitlesShowing(e){Td(this,a.MEDIA_SUBTITLES_SHOWING,e)}};Zr=new WeakMap,Mo=new WeakSet,Ad=function(){var o;if(s(this,Zr)===JSON.stringify(this.mediaSubtitlesList))return;h(this,Zr,JSON.stringify(this.mediaSubtitlesList)),this.defaultSlot.textContent="";let e=!this.value,r=Se({type:"radio",text:this.formatMenuItemText(g("Off")),value:"off",checked:e});r.prepend(Ee(this,"checked-indicator")),this.defaultSlot.append(r);let n=this.mediaSubtitlesList;for(let l of n){let d=Se({type:"radio",text:this.formatMenuItemText(l.label,l),value:xn(l),checked:this.value==xn(l)});d.prepend(Ee(this,"checked-indicator")),((o=l.kind)!=null?o:"subs")==="captions"&&d.append(Ee(this,"captions-indicator")),this.defaultSlot.append(d)}},Xr=new WeakSet,Ts=function(){let e=this.mediaSubtitlesShowing,r=this.getAttribute(a.MEDIA_SUBTITLES_SHOWING),n=this.value!==r;if(e!=null&&e.length&&n&&this.dispatchEvent(new u.CustomEvent(b.MEDIA_DISABLE_SUBTITLES_REQUEST,{composed:!0,bubbles:!0,detail:e})),!this.value||!n)return;let o=new u.CustomEvent(b.MEDIA_SHOW_SUBTITLES_REQUEST,{composed:!0,bubbles:!0,detail:this.value});this.dispatchEvent(o)},Ci.getTemplateHTML=dm;var vd=(t,i)=>{let e=t.getAttribute(i);return e?at(e):[]},Td=(t,i,e)=>{if(!(e!=null&&e.length)){t.removeAttribute(i);return}let r=_e(e);t.getAttribute(i)!==r&&t.setAttribute(i,r)};u.customElements.get("media-captions-menu")||u.customElements.define("media-captions-menu",Ci);var um=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M22.83 5.68a2.58 2.58 0 0 0-2.3-2.5c-3.62-.24-11.44-.24-15.06 0a2.58 2.58 0 0 0-2.3 2.5c-.23 4.21-.23 8.43 0 12.64a2.58 2.58 0 0 0 2.3 2.5c3.62.24 11.44.24 15.06 0a2.58 2.58 0 0 0 2.3-2.5c.23-4.21.23-8.43 0-12.64Zm-11.39 9.45a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.92 3.92 0 0 1 .92-2.77 3.18 3.18 0 0 1 2.43-1 2.94 2.94 0 0 1 2.13.78c.364.359.62.813.74 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.17 1.61 1.61 0 0 0-1.29.58 2.79 2.79 0 0 0-.5 1.89 3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.48 1.48 0 0 0 1-.37 2.1 2.1 0 0 0 .59-1.14l1.4.44a3.23 3.23 0 0 1-1.07 1.69Zm7.22 0a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.88 3.88 0 0 1 .93-2.77 3.14 3.14 0 0 1 2.42-1 3 3 0 0 1 2.16.82 2.8 2.8 0 0 1 .73 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.21 1.61 1.61 0 0 0-1.29.58A2.79 2.79 0 0 0 15 12a3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.44 1.44 0 0 0 1-.37 2.1 2.1 0 0 0 .6-1.15l1.4.44a3.17 3.17 0 0 1-1.1 1.7Z"/>
</svg>`,cm=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M17.73 14.09a1.4 1.4 0 0 1-1 .37 1.579 1.579 0 0 1-1.27-.58A3 3 0 0 1 15 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34A2.89 2.89 0 0 0 19 9.07a3 3 0 0 0-2.14-.78 3.14 3.14 0 0 0-2.42 1 3.91 3.91 0 0 0-.93 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.17 3.17 0 0 0 1.07-1.74l-1.4-.45c-.083.43-.3.822-.62 1.12Zm-7.22 0a1.43 1.43 0 0 1-1 .37 1.58 1.58 0 0 1-1.27-.58A3 3 0 0 1 7.76 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34a2.81 2.81 0 0 0-.74-1.32 2.94 2.94 0 0 0-2.13-.78 3.18 3.18 0 0 0-2.43 1 4 4 0 0 0-.92 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.23 3.23 0 0 0 1.07-1.74l-1.4-.45a2.06 2.06 0 0 1-.6 1.07Zm12.32-8.41a2.59 2.59 0 0 0-2.3-2.51C18.72 3.05 15.86 3 13 3c-2.86 0-5.72.05-7.53.17a2.59 2.59 0 0 0-2.3 2.51c-.23 4.207-.23 8.423 0 12.63a2.57 2.57 0 0 0 2.3 2.5c1.81.13 4.67.19 7.53.19 2.86 0 5.72-.06 7.53-.19a2.57 2.57 0 0 0 2.3-2.5c.23-4.207.23-8.423 0-12.63Zm-1.49 12.53a1.11 1.11 0 0 1-.91 1.11c-1.67.11-4.45.18-7.43.18-2.98 0-5.76-.07-7.43-.18a1.11 1.11 0 0 1-.91-1.11c-.21-4.14-.21-8.29 0-12.43a1.11 1.11 0 0 1 .91-1.11C7.24 4.56 10 4.49 13 4.49s5.76.07 7.43.18a1.11 1.11 0 0 1 .91 1.11c.21 4.14.21 8.29 0 12.43Z"/>
</svg>`;function mm(){return`
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
      <slot name="on">${um}</slot>
      <slot name="off">${cm}</slot>
    </slot>
  `}function hm(){return g("Captions")}var yd=t=>{t.setAttribute("aria-checked",Cn(t).toString())},xt=class extends z{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_SUBTITLES_LIST,a.MEDIA_SUBTITLES_SHOWING]}connectedCallback(){super.connectedCallback(),this.setAttribute("aria-label",g("closed captions")),yd(this)}attributeChangedCallback(i,e,r){super.attributeChangedCallback(i,e,r),i===a.MEDIA_SUBTITLES_SHOWING&&yd(this)}get invokeTargetElement(){var i;return this.invokeTarget!=null?super.invokeTargetElement:(i=B(this))==null?void 0:i.querySelector("media-captions-menu")}get mediaSubtitlesList(){return Sd(this,a.MEDIA_SUBTITLES_LIST)}set mediaSubtitlesList(i){Id(this,a.MEDIA_SUBTITLES_LIST,i)}get mediaSubtitlesShowing(){return Sd(this,a.MEDIA_SUBTITLES_SHOWING)}set mediaSubtitlesShowing(i){Id(this,a.MEDIA_SUBTITLES_SHOWING,i)}};xt.getSlotTemplateHTML=mm,xt.getTooltipContentHTML=hm;var Sd=(t,i)=>{let e=t.getAttribute(i);return e?at(e):[]},Id=(t,i,e)=>{if(!(e!=null&&e.length)){t.removeAttribute(i);return}let r=_e(e);t.getAttribute(i)!==r&&t.setAttribute(i,r)};u.customElements.get("media-captions-menu-button")||u.customElements.define("media-captions-menu-button",xt);var As={RATES:"rates"},we,Di,Lo,en,ys,Jr=class extends K{constructor(){super();c(this,Di);c(this,en);c(this,we,new Ve(this,As.RATES,{defaultValue:ss}));p(this,Di,Lo).call(this)}static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_PLAYBACK_RATE,As.RATES]}attributeChangedCallback(e,r,n){super.attributeChangedCallback(e,r,n),e===a.MEDIA_PLAYBACK_RATE&&r!=n?this.value=n:e===As.RATES&&r!=n&&(s(this,we).value=n,p(this,Di,Lo).call(this))}connectedCallback(){super.connectedCallback(),this.addEventListener("change",p(this,en,ys))}disconnectedCallback(){super.disconnectedCallback(),this.removeEventListener("change",p(this,en,ys))}get anchorElement(){return this.anchor!=="auto"?super.anchorElement:B(this).querySelector("media-playback-rate-menu-button")}get rates(){return s(this,we)}set rates(e){e?Array.isArray(e)?s(this,we).value=e.join(" "):typeof e=="string"&&(s(this,we).value=e):s(this,we).value="",p(this,Di,Lo).call(this)}get mediaPlaybackRate(){return R(this,a.MEDIA_PLAYBACK_RATE,pt)}set mediaPlaybackRate(e){C(this,a.MEDIA_PLAYBACK_RATE,e)}};we=new WeakMap,Di=new WeakSet,Lo=function(){this.defaultSlot.textContent="";for(let e of s(this,we)){let r=Se({type:"radio",text:this.formatMenuItemText(`${e}x`,e),value:e,checked:this.mediaPlaybackRate===Number(e)});r.prepend(Ee(this,"checked-indicator")),this.defaultSlot.append(r)}},en=new WeakSet,ys=function(){if(!this.value)return;let e=new u.CustomEvent(b.MEDIA_PLAYBACK_RATE_REQUEST,{composed:!0,bubbles:!0,detail:this.value});this.dispatchEvent(e)};u.customElements.get("media-playback-rate-menu")||u.customElements.define("media-playback-rate-menu",Jr);var ko=1;function pm(t){return`
    <style>
      :host {
        min-width: 5ch;
        padding: var(--media-button-padding, var(--media-control-padding, 10px 5px));
      }
      
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">${t.mediaplaybackrate||ko}x</slot>
  `}function Em(){return g("Playback rate")}var Ct=class extends z{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_PLAYBACK_RATE]}constructor(){var i;super(),this.container=this.shadowRoot.querySelector('slot[name="icon"]'),this.container.innerHTML=`${(i=this.mediaPlaybackRate)!=null?i:ko}x`}attributeChangedCallback(i,e,r){if(super.attributeChangedCallback(i,e,r),i===a.MEDIA_PLAYBACK_RATE){let n=r?+r:Number.NaN,o=Number.isNaN(n)?ko:n;this.container.innerHTML=`${o}x`,this.setAttribute("aria-label",g("Playback rate {playbackRate}",{playbackRate:o}))}}get invokeTargetElement(){return this.invokeTarget!=null?super.invokeTargetElement:B(this).querySelector("media-playback-rate-menu")}get mediaPlaybackRate(){return R(this,a.MEDIA_PLAYBACK_RATE,ko)}set mediaPlaybackRate(i){C(this,a.MEDIA_PLAYBACK_RATE,i)}};Ct.getSlotTemplateHTML=pm,Ct.getTooltipContentHTML=Em;u.customElements.get("media-playback-rate-menu-button")||u.customElements.define("media-playback-rate-menu-button",Ct);var wi,Dt,wt,tn,nn,Ss,rn=class extends K{constructor(){super(...arguments);c(this,wt);c(this,nn);c(this,wi,[]);c(this,Dt,{})}static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_RENDITION_LIST,a.MEDIA_RENDITION_SELECTED,a.MEDIA_RENDITION_UNAVAILABLE,a.MEDIA_HEIGHT]}attributeChangedCallback(e,r,n){super.attributeChangedCallback(e,r,n),e===a.MEDIA_RENDITION_SELECTED&&r!==n?(this.value=n!=null?n:"auto",p(this,wt,tn).call(this)):e===a.MEDIA_RENDITION_LIST&&r!==n?(h(this,wi,ca(n)),p(this,wt,tn).call(this)):e===a.MEDIA_HEIGHT&&r!==n&&p(this,wt,tn).call(this)}connectedCallback(){super.connectedCallback(),this.addEventListener("change",p(this,nn,Ss))}disconnectedCallback(){super.disconnectedCallback(),this.removeEventListener("change",p(this,nn,Ss))}get anchorElement(){return this.anchor!=="auto"?super.anchorElement:B(this).querySelector("media-rendition-menu-button")}get mediaRenditionList(){return s(this,wi)}set mediaRenditionList(e){h(this,wi,e),p(this,wt,tn).call(this)}get mediaRenditionSelected(){return L(this,a.MEDIA_RENDITION_SELECTED)}set mediaRenditionSelected(e){k(this,a.MEDIA_RENDITION_SELECTED,e)}get mediaHeight(){return R(this,a.MEDIA_HEIGHT)}set mediaHeight(e){C(this,a.MEDIA_HEIGHT,e)}};wi=new WeakMap,Dt=new WeakMap,wt=new WeakSet,tn=function(){if(s(this,Dt).mediaRenditionList===JSON.stringify(this.mediaRenditionList)&&s(this,Dt).mediaHeight===this.mediaHeight)return;s(this,Dt).mediaRenditionList=JSON.stringify(this.mediaRenditionList),s(this,Dt).mediaHeight=this.mediaHeight;let e=this.mediaRenditionList.sort((d,m)=>m.height-d.height);for(let d of e)d.selected=d.id===this.mediaRenditionSelected;this.defaultSlot.textContent="";let r=!this.mediaRenditionSelected;for(let d of e){let m=this.formatMenuItemText(`${Math.min(d.width,d.height)}p`,d),E=Se({type:"radio",text:m,value:`${d.id}`,checked:d.selected&&!r});E.prepend(Ee(this,"checked-indicator")),this.defaultSlot.append(E)}let n=r?this.formatMenuItemText(`${g("Auto")} (${this.mediaHeight}p)`):this.formatMenuItemText(g("Auto")),o=Se({type:"radio",text:n,value:"auto",checked:r}),l=this.mediaHeight>0?`${g("Auto")} (${this.mediaHeight}p)`:g("Auto");o.dataset.description=l,o.prepend(Ee(this,"checked-indicator")),this.defaultSlot.append(o)},nn=new WeakSet,Ss=function(){if(this.value==null)return;let e=new u.CustomEvent(b.MEDIA_RENDITION_REQUEST,{composed:!0,bubbles:!0,detail:this.value});this.dispatchEvent(e)};u.customElements.get("media-rendition-menu")||u.customElements.define("media-rendition-menu",rn);var gm=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M13.5 2.5h2v6h-2v-2h-11v-2h11v-2Zm4 2h4v2h-4v-2Zm-12 4h2v6h-2v-2h-3v-2h3v-2Zm4 2h12v2h-12v-2Zm1 4h2v6h-2v-2h-8v-2h8v-2Zm4 2h7v2h-7v-2Z" />
</svg>`;function bm(){return`
    <style>
      :host([aria-expanded="true"]) slot[name=tooltip] {
        display: none;
      }
    </style>
    <slot name="icon">${gm}</slot>
  `}function fm(){return g("Quality")}var Pt=class extends z{static get observedAttributes(){return[...super.observedAttributes,a.MEDIA_RENDITION_SELECTED,a.MEDIA_RENDITION_UNAVAILABLE,a.MEDIA_HEIGHT]}connectedCallback(){super.connectedCallback(),this.setAttribute("aria-label",g("quality"))}get invokeTargetElement(){return this.invokeTarget!=null?super.invokeTargetElement:B(this).querySelector("media-rendition-menu")}get mediaRenditionSelected(){return L(this,a.MEDIA_RENDITION_SELECTED)}set mediaRenditionSelected(i){k(this,a.MEDIA_RENDITION_SELECTED,i)}get mediaHeight(){return R(this,a.MEDIA_HEIGHT)}set mediaHeight(i){C(this,a.MEDIA_HEIGHT,i)}};Pt.getSlotTemplateHTML=bm,Pt.getTooltipContentHTML=fm;u.customElements.get("media-rendition-menu-button")||u.customElements.define("media-rendition-menu-button",Pt);var Md=1,Ld=0,vm=1,_d={processCallback(t,i,e){if(e){for(let[r,n]of i)if(r in e){let o=e[r];typeof o=="boolean"&&n instanceof fe&&typeof n.element[n.attributeName]=="boolean"?n.booleanValue=o:typeof o=="function"&&n instanceof fe?n.element[n.attributeName]=o:n.value=o}}}},Ut,an,et=class extends u.DocumentFragment{constructor(e,r,n=_d){var o;super();c(this,Ut,void 0);c(this,an,void 0);this.append(e.content.cloneNode(!0)),h(this,Ut,Ms(this)),h(this,an,n),(o=n.createCallback)==null||o.call(n,this,s(this,Ut),r),n.processCallback(this,s(this,Ut),r)}update(e){s(this,an).processCallback(this,s(this,Ut),e)}};Ut=new WeakMap,an=new WeakMap;var Ms=(t,i=[])=>{let e,r;for(let n of t.attributes||[])if(n.value.includes("{{")){let o=new _o;for([e,r]of Is(n.value))if(!e)o.append(r);else{let l=new fe(t,n.name,n.namespaceURI);o.append(l),i.push([r,l])}n.value=o.toString()}for(let n of t.childNodes)if(n.nodeType===Md&&!(n instanceof HTMLTemplateElement))Ms(n,i);else{let o=n.data;if(n.nodeType===Md||o.includes("{{")){let l=[];if(o)for([e,r]of Is(o))if(!e)l.push(new Text(r));else{let d=new sn(t);l.push(d),i.push([r,d])}else if(n instanceof HTMLTemplateElement){let d=new Oi(t,n);l.push(d),i.push([d.expression,d])}n.replaceWith(...l.flatMap(d=>d.replacementNodes||[d]))}}return i},kd={},Is=t=>{let i="",e=0,r=kd[t],n=0,o;if(r)return r;for(r=[];o=t[n];n++)o==="{"&&t[n+1]==="{"&&t[n-1]!=="\\"&&t[n+2]&&++e==1?(i&&r.push([Ld,i]),i="",n++):o==="}"&&t[n+1]==="}"&&t[n-1]!=="\\"&&!--e?(r.push([vm,i.trim()]),i="",n++):i+=o||"";return i&&r.push([Ld,(e>0?"{{":"")+i]),kd[t]=r},Tm=11,on=class{get value(){return""}set value(i){}toString(){return this.value}},Rd=new WeakMap,Xe,_o=class{constructor(){c(this,Xe,[])}[Symbol.iterator](){return s(this,Xe).values()}get length(){return s(this,Xe).length}item(i){return s(this,Xe)[i]}append(...i){for(let e of i)e instanceof fe&&Rd.set(e,this),s(this,Xe).push(e)}toString(){return s(this,Xe).join("")}};Xe=new WeakMap;var Ui,Pe,Ue,Oe,Je,Pi,fe=class extends on{constructor(e,r,n){super();c(this,Je);c(this,Ui,"");c(this,Pe,void 0);c(this,Ue,void 0);c(this,Oe,void 0);h(this,Pe,e),h(this,Ue,r),h(this,Oe,n)}get attributeName(){return s(this,Ue)}get attributeNamespace(){return s(this,Oe)}get element(){return s(this,Pe)}get value(){return s(this,Ui)}set value(e){s(this,Ui)!==e&&(h(this,Ui,e),!s(this,Je,Pi)||s(this,Je,Pi).length===1?e==null?s(this,Pe).removeAttributeNS(s(this,Oe),s(this,Ue)):s(this,Pe).setAttributeNS(s(this,Oe),s(this,Ue),e):s(this,Pe).setAttributeNS(s(this,Oe),s(this,Ue),s(this,Je,Pi).toString()))}get booleanValue(){return s(this,Pe).hasAttributeNS(s(this,Oe),s(this,Ue))}set booleanValue(e){if(!s(this,Je,Pi)||s(this,Je,Pi).length===1)this.value=e?"":null;else throw new DOMException("Value is not fully templatized")}};Ui=new WeakMap,Pe=new WeakMap,Ue=new WeakMap,Oe=new WeakMap,Je=new WeakSet,Pi=function(){return Rd.get(this)};var ln,ne,sn=class extends on{constructor(e,r){super();c(this,ln,void 0);c(this,ne,void 0);h(this,ln,e),h(this,ne,r?[...r]:[new Text])}get replacementNodes(){return s(this,ne)}get parentNode(){return s(this,ln)}get nextSibling(){return s(this,ne)[s(this,ne).length-1].nextSibling}get previousSibling(){return s(this,ne)[0].previousSibling}get value(){return s(this,ne).map(e=>e.textContent).join("")}set value(e){this.replace(e)}replace(...e){let r=e.flat().flatMap(n=>n==null?[new Text]:n.forEach?[...n]:n.nodeType===Tm?[...n.childNodes]:n.nodeType?[n]:[new Text(n)]);r.length||r.push(new Text),h(this,ne,Am(s(this,ne)[0].parentNode,s(this,ne),r,this.nextSibling))}};ln=new WeakMap,ne=new WeakMap;var Oi=class extends sn{constructor(i,e){let r=e.getAttribute("directive")||e.getAttribute("type"),n=e.getAttribute("expression")||e.getAttribute(r)||"";n.startsWith("{{")&&(n=n.trim().slice(2,-2).trim()),super(i),this.expression=n,this.template=e,this.directive=r}};function Am(t,i,e,r=null){let n=0,o,l,d,m=e.length,E=i.length;for(;n<m&&n<E&&i[n]==e[n];)n++;for(;n<m&&n<E&&e[m-1]==i[E-1];)r=e[--E,--m];if(n==E)for(;n<m;)t.insertBefore(e[n++],r);if(n==m)for(;n<E;)t.removeChild(i[n++]);else{for(o=i[n];n<m;)d=e[n++],l=o?o.nextSibling:r,o==d?o=l:n<m&&e[n]==l?(t.replaceChild(d,o),o=l):t.insertBefore(d,o);for(;o!=r;)l=o.nextSibling,t.removeChild(o),o=l}return e}var Ls={string:t=>String(t)},Co=class{constructor(i){this.template=i,this.state=void 0}},Ot=new WeakMap,Ht=new WeakMap,xo={partial:(t,i)=>{i[t.expression]=new Co(t.template)},if:(t,i)=>{var e;if(Cd(t.expression,i))if(Ot.get(t)!==t.template){Ot.set(t,t.template);let r=new et(t.template,i,Do);t.replace(r),Ht.set(t,r)}else(e=Ht.get(t))==null||e.update(i);else t.replace(""),Ot.delete(t),Ht.delete(t)}},ym=Object.keys(xo),Do={processCallback(t,i,e){var r,n;if(e)for(let[o,l]of i){if(l instanceof Oi){if(!l.directive){let m=ym.find(E=>l.template.hasAttribute(E));m&&(l.directive=m,l.expression=l.template.getAttribute(m))}(r=xo[l.directive])==null||r.call(xo,l,e);continue}let d=Cd(o,e);if(d instanceof Co){Ot.get(l)!==d.template?(Ot.set(l,d.template),d=new et(d.template,d.state,Do),l.value=d,Ht.set(l,d)):(n=Ht.get(l))==null||n.update(d.state);continue}d?(l instanceof fe&&l.attributeName.startsWith("aria-")&&(d=String(d)),l instanceof fe?typeof d=="boolean"?l.booleanValue=d:typeof d=="function"?l.element[l.attributeName]=d:l.value=d:(l.value=d,Ot.delete(l),Ht.delete(l))):l instanceof fe?l.value=void 0:(l.value=void 0,Ot.delete(l),Ht.delete(l))}}},xd={"!":t=>!t,"!!":t=>!!t,"==":(t,i)=>t==i,"!=":(t,i)=>t!=i,">":(t,i)=>t>i,">=":(t,i)=>t>=i,"<":(t,i)=>t<i,"<=":(t,i)=>t<=i,"??":(t,i)=>t!=null?t:i,"|":(t,i)=>{var e;return(e=Ls[i])==null?void 0:e.call(Ls,t)}};function Sm(t){return Im(t,{boolean:/true|false/,number:/-?\d+\.?\d*/,string:/(["'])((?:\\.|[^\\])*?)\1/,operator:/[!=><][=!]?|\?\?|\|/,ws:/\s+/,param:/[$a-z_][$\w]*/i}).filter(({type:i})=>i!=="ws")}function Cd(t,i={}){var r,n,o,l,d,m,E;let e=Sm(t);if(e.length===0||e.some(({type:T})=>!T))return dn(t);if(((r=e[0])==null?void 0:r.token)===">"){let T=i[(n=e[1])==null?void 0:n.token];if(!T)return dn(t);let y={...i};T.state=y;let v=e.slice(2);for(let f=0;f<v.length;f+=3){let w=(o=v[f])==null?void 0:o.token,x=(l=v[f+1])==null?void 0:l.token,D=(d=v[f+2])==null?void 0:d.token;w&&x==="="&&(y[w]=un(D,i))}return T}if(e.length===1)return Ro(e[0])?un(e[0].token,i):dn(t);if(e.length===2){let T=(m=e[0])==null?void 0:m.token,y=xd[T];if(!y||!Ro(e[1]))return dn(t);let v=un(e[1].token,i);return y(v)}if(e.length===3){let T=(E=e[1])==null?void 0:E.token,y=xd[T];if(!y||!Ro(e[0])||!Ro(e[2]))return dn(t);let v=un(e[0].token,i);if(T==="|")return y(v,e[2].token);let f=un(e[2].token,i);return y(v,f)}}function dn(t){return console.warn(`Warning: invalid expression \`${t}\``),!1}function Ro({type:t}){return["number","boolean","string","param"].includes(t)}function un(t,i){let e=t[0],r=t.slice(-1);return t==="true"||t==="false"?t==="true":e===r&&["'",'"'].includes(e)?t.slice(1,-1):gn(t)?parseFloat(t):i[t]}function Im(t,i){let e,r,n,o=[];for(;t;){n=null,e=t.length;for(let l in i)r=i[l].exec(t),r&&r.index<e&&(n={token:r[0],type:l,matches:r.slice(1)},e=r.index);e&&o.push({token:t.substr(0,e),type:void 0}),n&&o.push(n),t=t.substr(e+(n?n.token.length:0))}return o}var ks={mediatargetlivewindow:"targetlivewindow",mediastreamtype:"streamtype"},Dd=F.createElement("template");Dd.innerHTML=`
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
`;var Nt,cn,Ft,wo,wd,mn,_s,Hi=class extends u.HTMLElement{constructor(){super();c(this,wo);c(this,mn);c(this,Nt,void 0);c(this,cn,void 0);c(this,Ft,void 0);this.shadowRoot?this.renderRoot=this.shadowRoot:(this.renderRoot=this.attachShadow({mode:"open"}),this.createRenderer());let e=new MutationObserver(r=>{var n;this.mediaController&&!((n=this.mediaController)!=null&&n.breakpointsComputed)||r.some(o=>{let l=o.target;return l===this?!0:l.localName!=="media-controller"?!1:!!(ks[o.attributeName]||o.attributeName.startsWith("breakpoint"))})&&this.render()});e.observe(this,{attributes:!0}),e.observe(this.renderRoot,{attributes:!0,subtree:!0}),this.addEventListener(ve.BREAKPOINTS_COMPUTED,this.render),p(this,wo,wd).call(this,"template")}get mediaController(){return this.renderRoot.querySelector("media-controller")}get template(){var e;return(e=s(this,Nt))!=null?e:this.constructor.template}set template(e){h(this,Ft,null),h(this,Nt,e),this.createRenderer()}get props(){var n,o,l;let e=[...Array.from((o=(n=this.mediaController)==null?void 0:n.attributes)!=null?o:[]).filter(({name:d})=>ks[d]||d.startsWith("breakpoint")),...Array.from(this.attributes)],r={};for(let d of e){let m=(l=ks[d.name])!=null?l:pa(d.name),{value:E}=d;E!=null?(gn(E)&&(E=parseFloat(E)),r[m]=E===""?!0:E):r[m]=!1}return r}attributeChangedCallback(e,r,n){e==="template"&&r!=n&&p(this,mn,_s).call(this)}connectedCallback(){p(this,mn,_s).call(this)}createRenderer(){this.template&&this.template!==s(this,cn)&&(h(this,cn,this.template),this.renderer=new et(this.template,this.props,this.constructor.processor),this.renderRoot.textContent="",this.renderRoot.append(Dd.content.cloneNode(!0),this.renderer))}render(){var e;(e=this.renderer)==null||e.update(this.props)}};Nt=new WeakMap,cn=new WeakMap,Ft=new WeakMap,wo=new WeakSet,wd=function(e){if(Object.prototype.hasOwnProperty.call(this,e)){let r=this[e];delete this[e],this[e]=r}},mn=new WeakSet,_s=function(){var o;let e=this.getAttribute("template");if(!e||e===s(this,Ft))return;let r=this.getRootNode(),n=(o=r==null?void 0:r.getElementById)==null?void 0:o.call(r,e);if(n){h(this,Ft,e),h(this,Nt,n),this.createRenderer();return}Mm(e)&&(h(this,Ft,e),Lm(e).then(l=>{let d=F.createElement("template");d.innerHTML=l,h(this,Nt,d),this.createRenderer()}).catch(console.error))},Hi.observedAttributes=["template"],Hi.processor=Do;function Mm(t){if(!/^(\/|\.\/|https?:\/\/)/.test(t))return!1;let i=/^https?:\/\//.test(t)?void 0:location.origin;try{new URL(t,i)}catch{return!1}return!0}async function Lm(t){let i=await fetch(t);if(i.status!==200)throw new Error(`Failed to load resource: the server responded with a status of ${i.status}`);return i.text()}u.customElements.get("media-theme")||u.customElements.define("media-theme",Hi);return qd(km);})();
//# sourceMappingURL=all.js.map
