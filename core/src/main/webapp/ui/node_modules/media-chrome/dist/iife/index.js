var MediaChrome=(()=>{var Ir=Object.defineProperty;var Os=Object.getOwnPropertyDescriptor;var Ns=Object.getOwnPropertyNames;var Hs=Object.prototype.hasOwnProperty;var Sr=(i,t)=>{for(var e in t)Ir(i,e,{get:t[e],enumerable:!0})},Fs=(i,t,e,r)=>{if(t&&typeof t=="object"||typeof t=="function")for(let o of Ns(t))!Hs.call(i,o)&&o!==e&&Ir(i,o,{get:()=>t[o],enumerable:!(r=Os(t,o))||r.enumerable});return i};var $s=i=>Fs(Ir({},"__esModule",{value:!0}),i);var yr=(i,t,e)=>{if(!t.has(i))throw TypeError("Cannot "+e)};var a=(i,t,e)=>(yr(i,t,"read from private field"),e?e.call(i):t.get(i)),m=(i,t,e)=>{if(t.has(i))throw TypeError("Cannot add the same private member more than once");t instanceof WeakSet?t.add(i):t.set(i,e)},p=(i,t,e,r)=>(yr(i,t,"write to private field"),r?r.call(i,e):t.set(i,e),e);var Uo=(i,t,e,r)=>({set _(o){p(i,t,o,e)},get _(){return a(i,t,r)}}),v=(i,t,e)=>(yr(i,t,"access private method"),e);var Gd={};Sr(Gd,{MediaAirplayButton:()=>kn,MediaCaptionsButton:()=>Cn,MediaCastButton:()=>Un,MediaChromeButton:()=>_n,MediaChromeDialog:()=>Bn,MediaChromeRange:()=>Yn,MediaContainer:()=>tn,MediaControlBar:()=>Qn,MediaController:()=>yn,MediaDurationDisplay:()=>zn,MediaErrorDialog:()=>Jn,MediaFullscreenButton:()=>ts,MediaGestureReceiver:()=>Ot,MediaLiveButton:()=>rs,MediaLoadingIndicator:()=>ns,MediaMuteButton:()=>ds,MediaPipButton:()=>cs,MediaPlayButton:()=>hs,MediaPlaybackRateButton:()=>ms,MediaPosterImage:()=>Es,MediaPreviewChapterDisplay:()=>gs,MediaPreviewThumbnail:()=>bi,MediaPreviewTimeDisplay:()=>bs,MediaSeekBackwardButton:()=>fs,MediaSeekForwardButton:()=>vs,MediaTextDisplay:()=>jn,MediaTimeDisplay:()=>Is,MediaTimeRange:()=>Ls,MediaTooltip:()=>jt,MediaVolumeRange:()=>Rs,constants:()=>Ci,t:()=>b,timeUtils:()=>Ui});var Ci={};Sr(Ci,{AttributeToStateChangeEventMap:()=>Mr,AvailabilityStates:()=>Y,MediaStateChangeEvents:()=>be,MediaStateReceiverAttributes:()=>_,MediaUIAttributes:()=>s,MediaUIEvents:()=>E,MediaUIProps:()=>Di,PointerTypes:()=>wi,ReadyStates:()=>Ks,StateChangeEventToAttributeMap:()=>Vs,StreamTypes:()=>z,TextTrackKinds:()=>G,TextTrackModes:()=>me,VolumeLevels:()=>Gs,WebkitPresentationModes:()=>_r});var E={MEDIA_PLAY_REQUEST:"mediaplayrequest",MEDIA_PAUSE_REQUEST:"mediapauserequest",MEDIA_MUTE_REQUEST:"mediamuterequest",MEDIA_UNMUTE_REQUEST:"mediaunmuterequest",MEDIA_VOLUME_REQUEST:"mediavolumerequest",MEDIA_SEEK_REQUEST:"mediaseekrequest",MEDIA_AIRPLAY_REQUEST:"mediaairplayrequest",MEDIA_ENTER_FULLSCREEN_REQUEST:"mediaenterfullscreenrequest",MEDIA_EXIT_FULLSCREEN_REQUEST:"mediaexitfullscreenrequest",MEDIA_PREVIEW_REQUEST:"mediapreviewrequest",MEDIA_ENTER_PIP_REQUEST:"mediaenterpiprequest",MEDIA_EXIT_PIP_REQUEST:"mediaexitpiprequest",MEDIA_ENTER_CAST_REQUEST:"mediaentercastrequest",MEDIA_EXIT_CAST_REQUEST:"mediaexitcastrequest",MEDIA_SHOW_TEXT_TRACKS_REQUEST:"mediashowtexttracksrequest",MEDIA_HIDE_TEXT_TRACKS_REQUEST:"mediahidetexttracksrequest",MEDIA_SHOW_SUBTITLES_REQUEST:"mediashowsubtitlesrequest",MEDIA_DISABLE_SUBTITLES_REQUEST:"mediadisablesubtitlesrequest",MEDIA_TOGGLE_SUBTITLES_REQUEST:"mediatogglesubtitlesrequest",MEDIA_PLAYBACK_RATE_REQUEST:"mediaplaybackraterequest",MEDIA_RENDITION_REQUEST:"mediarenditionrequest",MEDIA_AUDIO_TRACK_REQUEST:"mediaaudiotrackrequest",MEDIA_SEEK_TO_LIVE_REQUEST:"mediaseektoliverequest",REGISTER_MEDIA_STATE_RECEIVER:"registermediastatereceiver",UNREGISTER_MEDIA_STATE_RECEIVER:"unregistermediastatereceiver"},_={MEDIA_CHROME_ATTRIBUTES:"mediachromeattributes",MEDIA_CONTROLLER:"mediacontroller"},Di={MEDIA_AIRPLAY_UNAVAILABLE:"mediaAirplayUnavailable",MEDIA_AUDIO_TRACK_ENABLED:"mediaAudioTrackEnabled",MEDIA_AUDIO_TRACK_LIST:"mediaAudioTrackList",MEDIA_AUDIO_TRACK_UNAVAILABLE:"mediaAudioTrackUnavailable",MEDIA_BUFFERED:"mediaBuffered",MEDIA_CAST_UNAVAILABLE:"mediaCastUnavailable",MEDIA_CHAPTERS_CUES:"mediaChaptersCues",MEDIA_CURRENT_TIME:"mediaCurrentTime",MEDIA_DURATION:"mediaDuration",MEDIA_ENDED:"mediaEnded",MEDIA_ERROR:"mediaError",MEDIA_ERROR_CODE:"mediaErrorCode",MEDIA_ERROR_MESSAGE:"mediaErrorMessage",MEDIA_FULLSCREEN_UNAVAILABLE:"mediaFullscreenUnavailable",MEDIA_HAS_PLAYED:"mediaHasPlayed",MEDIA_HEIGHT:"mediaHeight",MEDIA_IS_AIRPLAYING:"mediaIsAirplaying",MEDIA_IS_CASTING:"mediaIsCasting",MEDIA_IS_FULLSCREEN:"mediaIsFullscreen",MEDIA_IS_PIP:"mediaIsPip",MEDIA_LOADING:"mediaLoading",MEDIA_MUTED:"mediaMuted",MEDIA_PAUSED:"mediaPaused",MEDIA_PIP_UNAVAILABLE:"mediaPipUnavailable",MEDIA_PLAYBACK_RATE:"mediaPlaybackRate",MEDIA_PREVIEW_CHAPTER:"mediaPreviewChapter",MEDIA_PREVIEW_COORDS:"mediaPreviewCoords",MEDIA_PREVIEW_IMAGE:"mediaPreviewImage",MEDIA_PREVIEW_TIME:"mediaPreviewTime",MEDIA_RENDITION_LIST:"mediaRenditionList",MEDIA_RENDITION_SELECTED:"mediaRenditionSelected",MEDIA_RENDITION_UNAVAILABLE:"mediaRenditionUnavailable",MEDIA_SEEKABLE:"mediaSeekable",MEDIA_STREAM_TYPE:"mediaStreamType",MEDIA_SUBTITLES_LIST:"mediaSubtitlesList",MEDIA_SUBTITLES_SHOWING:"mediaSubtitlesShowing",MEDIA_TARGET_LIVE_WINDOW:"mediaTargetLiveWindow",MEDIA_TIME_IS_LIVE:"mediaTimeIsLive",MEDIA_VOLUME:"mediaVolume",MEDIA_VOLUME_LEVEL:"mediaVolumeLevel",MEDIA_VOLUME_UNAVAILABLE:"mediaVolumeUnavailable",MEDIA_WIDTH:"mediaWidth"},Oo=Object.entries(Di),s=Oo.reduce((i,[t,e])=>(i[t]=e.toLowerCase(),i),{}),Bs={USER_INACTIVE_CHANGE:"userinactivechange",BREAKPOINTS_CHANGE:"breakpointchange",BREAKPOINTS_COMPUTED:"breakpointscomputed"},be=Oo.reduce((i,[t,e])=>(i[t]=e.toLowerCase(),i),{...Bs}),Vs=Object.entries(be).reduce((i,[t,e])=>{let r=s[t];return r&&(i[e]=r),i},{userinactivechange:"userinactive"}),Mr=Object.entries(s).reduce((i,[t,e])=>{let r=be[t];return r&&(i[e]=r),i},{userinactive:"userinactivechange"}),G={SUBTITLES:"subtitles",CAPTIONS:"captions",DESCRIPTIONS:"descriptions",CHAPTERS:"chapters",METADATA:"metadata"},me={DISABLED:"disabled",HIDDEN:"hidden",SHOWING:"showing"},Ks={HAVE_NOTHING:0,HAVE_METADATA:1,HAVE_CURRENT_DATA:2,HAVE_FUTURE_DATA:3,HAVE_ENOUGH_DATA:4},wi={MOUSE:"mouse",PEN:"pen",TOUCH:"touch"},Y={UNAVAILABLE:"unavailable",UNSUPPORTED:"unsupported"},z={LIVE:"live",ON_DEMAND:"on-demand",UNKNOWN:"unknown"},Gs={HIGH:"high",MEDIUM:"medium",LOW:"low",OFF:"off"},_r={INLINE:"inline",FULLSCREEN:"fullscreen",PICTURE_IN_PICTURE:"picture-in-picture"};var Ui={};Sr(Ui,{emptyTimeRanges:()=>$o,formatAsTimePhrase:()=>fe,formatTime:()=>Z,serializeTimeRanges:()=>Qs});function No(i){return i==null?void 0:i.map(Ws).join(" ")}function Ws(i){if(i){let{id:t,width:e,height:r}=i;return[t,e,r].filter(o=>o!=null).join(":")}}function Ho(i){return i==null?void 0:i.map(qs).join(" ")}function qs(i){if(i){let{id:t,kind:e,language:r,label:o}=i;return[t,e,r,o].filter(n=>n!=null).join(":")}}function je(i){return typeof i=="number"&&!Number.isNaN(i)&&Number.isFinite(i)}var Pi=i=>new Promise(t=>setTimeout(t,i));var Fo=[{singular:"hour",plural:"hours"},{singular:"minute",plural:"minutes"},{singular:"second",plural:"seconds"}],Ys=(i,t)=>{let e=i===1?Fo[t].singular:Fo[t].plural;return`${i} ${e}`},fe=i=>{if(!je(i))return"";let t=Math.abs(i),e=t!==i,r=new Date(0,0,0,0,0,t,0);return`${[r.getHours(),r.getMinutes(),r.getSeconds()].map((u,c)=>u&&Ys(u,c)).filter(u=>u).join(", ")}${e?" remaining":""}`};function Z(i,t){let e=!1;i<0&&(e=!0,i=0-i),i=i<0?0:i;let r=Math.floor(i%60),o=Math.floor(i/60%60),n=Math.floor(i/3600),d=Math.floor(t/60%60),u=Math.floor(t/3600);return(isNaN(i)||i===1/0)&&(n=o=r="0"),n=n>0||u>0?n+":":"",o=((n||d>=10)&&o<10?"0"+o:o)+":",r=r<10?"0"+r:r,(e?"-":"")+n+o+r}var $o=Object.freeze({length:0,start(i){let t=i>>>0;if(t>=this.length)throw new DOMException(`Failed to execute 'start' on 'TimeRanges': The index provided (${t}) is greater than or equal to the maximum bound (${this.length}).`);return 0},end(i){let t=i>>>0;if(t>=this.length)throw new DOMException(`Failed to execute 'end' on 'TimeRanges': The index provided (${t}) is greater than or equal to the maximum bound (${this.length}).`);return 0}});function Qs(i=$o){return Array.from(i).map((t,e)=>[Number(i.start(e).toFixed(3)),Number(i.end(e).toFixed(3))].join(":")).join(" ")}var Bo={"Start airplay":"Start airplay","Stop airplay":"Stop airplay",Audio:"Audio",Captions:"Captions","Enable captions":"Enable captions","Disable captions":"Disable captions","Start casting":"Start casting","Stop casting":"Stop casting","Enter fullscreen mode":"Enter fullscreen mode","Exit fullscreen mode":"Exit fullscreen mode",Mute:"Mute",Unmute:"Unmute","Enter picture in picture mode":"Enter picture in picture mode","Exit picture in picture mode":"Exit picture in picture mode",Play:"Play",Pause:"Pause","Playback rate":"Playback rate","Playback rate {playbackRate}":"Playback rate {playbackRate}",Quality:"Quality","Seek backward":"Seek backward","Seek forward":"Seek forward",Settings:"Settings",Auto:"Auto","audio player":"audio player","video player":"video player",volume:"volume",seek:"seek","closed captions":"closed captions","current playback rate":"current playback rate","playback time":"playback time","media loading":"media loading",settings:"settings","audio tracks":"audio tracks",quality:"quality",play:"play",pause:"pause",mute:"mute",unmute:"unmute",live:"live",Off:"Off","start airplay":"start airplay","stop airplay":"stop airplay","start casting":"start casting","stop casting":"stop casting","enter fullscreen mode":"enter fullscreen mode","exit fullscreen mode":"exit fullscreen mode","enter picture in picture mode":"enter picture in picture mode","exit picture in picture mode":"exit picture in picture mode","seek to live":"seek to live","playing live":"playing live","seek back {seekOffset} seconds":"seek back {seekOffset} seconds","seek forward {seekOffset} seconds":"seek forward {seekOffset} seconds","Network Error":"Network Error","Decode Error":"Decode Error","Source Not Supported":"Source Not Supported","Encryption Error":"Encryption Error","A network error caused the media download to fail.":"A network error caused the media download to fail.","A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.":"A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format.","An unsupported error occurred. The server or network failed, or your browser does not support this format.":"An unsupported error occurred. The server or network failed, or your browser does not support this format.","The media is encrypted and there are no keys to decrypt it.":"The media is encrypted and there are no keys to decrypt it."};var Lr={en:Bo},Vo,Rr=((Vo=globalThis.navigator)==null?void 0:Vo.language)||"en",Ko=i=>{Rr=i};var js=i=>{var e,r,o;let[t]=Rr.split("-");return((e=Lr[Rr])==null?void 0:e[i])||((r=Lr[t])==null?void 0:r[i])||((o=Lr.en)==null?void 0:o[i])||i},b=(i,t={})=>js(i).replace(/\{(\w+)\}/g,(e,r)=>r in t?String(t[r]):`{${r}}`);var Oi=class{addEventListener(){}removeEventListener(){}dispatchEvent(){return!0}},Ni=class extends Oi{},Hi=class extends Ni{constructor(){super(...arguments);this.role=null}},kr=class{observe(){}unobserve(){}disconnect(){}},Go={createElement:function(){return new Ut.HTMLElement},createElementNS:function(){return new Ut.HTMLElement},addEventListener(){},removeEventListener(){},dispatchEvent(i){return!1}},Ut={ResizeObserver:kr,document:Go,Node:Ni,Element:Hi,HTMLElement:class extends Hi{constructor(){super(...arguments);this.innerHTML=""}get content(){return new Ut.DocumentFragment}},DocumentFragment:class extends Oi{},customElements:{get:function(){},define:function(){},whenDefined:function(){}},localStorage:{getItem(i){return null},setItem(i,t){},removeItem(i){}},CustomEvent:function(){},getComputedStyle:function(){},navigator:{languages:[],get userAgent(){return""}},matchMedia(i){return{matches:!1,media:i}},DOMParser:class{parseFromString(t,e){return{body:{textContent:t}}}}},Wo=typeof window=="undefined"||typeof window.customElements=="undefined",qo=Object.keys(Ut).every(i=>i in globalThis),l=Wo&&!qo?Ut:globalThis,B=Wo&&!qo?Go:globalThis.document;var Yo=new WeakMap,xr=i=>{let t=Yo.get(i);return t||Yo.set(i,t=new Set),t},Qo=new l.ResizeObserver(i=>{for(let t of i)for(let e of xr(t.target))e(t)});function Fi(i,t){xr(i).add(t),Qo.observe(i)}function $i(i,t){let e=xr(i);e.delete(t),e.size||Qo.unobserve(i)}function F(i){let t={};for(let e of i)t[e.name]=e.value;return t}function jo(i){var t;return(t=zs(i))!=null?t:ve(i,"media-controller")}function zs(i){var r;let{MEDIA_CONTROLLER:t}=_,e=i.getAttribute(t);if(e)return(r=Xs(i))==null?void 0:r.getElementById(e)}var Bi=(i,t,e=".value")=>{let r=i.querySelector(e);r&&(r.textContent=t)},Zs=(i,t)=>{let e=`slot[name="${t}"]`,r=i.shadowRoot.querySelector(e);return r?r.children:[]},Vi=(i,t)=>Zs(i,t)[0],de=(i,t)=>!i||!t?!1:i!=null&&i.contains(t)?!0:de(i,t.getRootNode().host),ve=(i,t)=>{if(!i)return null;let e=i.closest(t);return e||ve(i.getRootNode().host,t)};function Dr(i=document){var e;let t=i==null?void 0:i.activeElement;return t?(e=Dr(t.shadowRoot))!=null?e:t:null}function Xs(i){var e;let t=(e=i==null?void 0:i.getRootNode)==null?void 0:e.call(i);return t instanceof ShadowRoot||t instanceof Document?t:null}function Ki(i,{depth:t=3,checkOpacity:e=!0,checkVisibilityCSS:r=!0}={}){if(i.checkVisibility)return i.checkVisibility({checkOpacity:e,checkVisibilityCSS:r});let o=i;for(;o&&t>0;){let n=getComputedStyle(o);if(e&&n.opacity==="0"||r&&n.visibility==="hidden"||n.display==="none")return!1;o=o.parentElement,t--}return!0}function zo(i,t,e,r){let o=r.x-e.x,n=r.y-e.y,d=o*o+n*n;if(d===0)return 0;let u=((i-e.x)*o+(t-e.y)*n)/d;return Math.max(0,Math.min(1,u))}function U(i,t){let e=Js(i,r=>r===t);return e||ea(i,t)}function Js(i,t){var r,o;let e;for(e of(r=i.querySelectorAll("style:not([media])"))!=null?r:[]){let n;try{n=(o=e.sheet)==null?void 0:o.cssRules}catch{continue}for(let d of n!=null?n:[])if(t(d.selectorText))return d}}function ea(i,t){var o,n;let e=(o=i.querySelectorAll("style:not([media])"))!=null?o:[],r=e==null?void 0:e[e.length-1];return r!=null&&r.sheet?(r==null||r.sheet.insertRule(`${t}{}`,r.sheet.cssRules.length),(n=r.sheet.cssRules)==null?void 0:n[r.sheet.cssRules.length-1]):(console.warn("Media Chrome: No style sheet found on style tag of",i),{style:{setProperty:()=>{},removeProperty:()=>"",getPropertyValue:()=>""}})}function D(i,t,e=Number.NaN){let r=i.getAttribute(t);return r!=null?+r:e}function O(i,t,e){let r=+e;if(e==null||Number.isNaN(r)){i.hasAttribute(t)&&i.removeAttribute(t);return}D(i,t,void 0)!==r&&i.setAttribute(t,`${r}`)}function S(i,t){return i.hasAttribute(t)}function y(i,t,e){if(e==null){i.hasAttribute(t)&&i.removeAttribute(t);return}S(i,t)!=e&&i.toggleAttribute(t,e)}function L(i,t,e=null){var r;return(r=i.getAttribute(t))!=null?r:e}function R(i,t,e){if(e==null){i.hasAttribute(t)&&i.removeAttribute(t);return}let r=`${e}`;L(i,t,void 0)!==r&&i.setAttribute(t,r)}function ta(i){return`
    <style>
      :host {
        display: var(--media-control-display, var(--media-gesture-receiver-display, inline-block));
        box-sizing: border-box;
      }
    </style>
  `}var W,ze=class extends l.HTMLElement{constructor(){super();m(this,W,void 0);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=F(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER,s.MEDIA_PAUSED]}attributeChangedCallback(e,r,o){var n,d,u,c,h;e===_.MEDIA_CONTROLLER&&(r&&((d=(n=a(this,W))==null?void 0:n.unassociateElement)==null||d.call(n,this),p(this,W,null)),o&&this.isConnected&&(p(this,W,(u=this.getRootNode())==null?void 0:u.getElementById(o)),(h=(c=a(this,W))==null?void 0:c.associateElement)==null||h.call(c,this)))}connectedCallback(){var e,r,o,n;this.tabIndex=-1,this.setAttribute("aria-hidden","true"),p(this,W,ia(this)),this.getAttribute(_.MEDIA_CONTROLLER)&&((r=(e=a(this,W))==null?void 0:e.associateElement)==null||r.call(e,this)),(o=a(this,W))==null||o.addEventListener("pointerdown",this),(n=a(this,W))==null||n.addEventListener("click",this)}disconnectedCallback(){var e,r,o,n;this.getAttribute(_.MEDIA_CONTROLLER)&&((r=(e=a(this,W))==null?void 0:e.unassociateElement)==null||r.call(e,this)),(o=a(this,W))==null||o.removeEventListener("pointerdown",this),(n=a(this,W))==null||n.removeEventListener("click",this),p(this,W,null)}handleEvent(e){var n;let r=(n=e.composedPath())==null?void 0:n[0];if(["video","media-controller"].includes(r==null?void 0:r.localName)){if(e.type==="pointerdown")this._pointerType=e.pointerType;else if(e.type==="click"){let{clientX:d,clientY:u}=e,{left:c,top:h,width:A,height:M}=this.getBoundingClientRect(),T=d-c,f=u-h;if(T<0||f<0||T>A||f>M||A===0&&M===0)return;let{pointerType:w=this._pointerType}=e;if(this._pointerType=void 0,w===wi.TOUCH){this.handleTap(e);return}else if(w===wi.MOUSE){this.handleMouseClick(e);return}}}}get mediaPaused(){return S(this,s.MEDIA_PAUSED)}set mediaPaused(e){y(this,s.MEDIA_PAUSED,e)}handleTap(e){}handleMouseClick(e){let r=this.mediaPaused?E.MEDIA_PLAY_REQUEST:E.MEDIA_PAUSE_REQUEST;this.dispatchEvent(new l.CustomEvent(r,{composed:!0,bubbles:!0}))}};W=new WeakMap,ze.shadowRootOptions={mode:"open"},ze.getTemplateHTML=ta;function ia(i){var e;let t=i.getAttribute(_.MEDIA_CONTROLLER);return t?(e=i.getRootNode())==null?void 0:e.getElementById(t):ve(i,"media-controller")}l.customElements.get("media-gesture-receiver")||l.customElements.define("media-gesture-receiver",ze);var Ot=ze;var I={AUDIO:"audio",AUTOHIDE:"autohide",BREAKPOINTS:"breakpoints",GESTURES_DISABLED:"gesturesdisabled",KEYBOARD_CONTROL:"keyboardcontrol",NO_AUTOHIDE:"noautohide",USER_INACTIVE:"userinactive",AUTOHIDE_OVER_CONTROLS:"autohideovercontrols"};function ra(i){return`
    <style>
      
      :host([${s.MEDIA_IS_FULLSCREEN}]) ::slotted([slot=media]) {
        outline: none;
      }

      :host {
        box-sizing: border-box;
        position: relative;
        display: inline-block;
        line-height: 0;
        background-color: var(--media-background-color, #000);
      }

      :host(:not([${I.AUDIO}])) [part~=layer]:not([part~=media-layer]) {
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

      
      :host([${I.AUDIO}]) slot[name=media] {
        display: var(--media-slot-display, none);
      }

      
      :host([${I.AUDIO}]) [part~=layer][part~=gesture-layer] {
        height: 0;
        display: block;
      }

      
      :host(:not([${I.AUDIO}])[${I.GESTURES_DISABLED}]) ::slotted([slot=gestures-chrome]),
          :host(:not([${I.AUDIO}])[${I.GESTURES_DISABLED}]) media-gesture-receiver[slot=gestures-chrome] {
        display: none;
      }

      
      ::slotted(:not([slot=media]):not([slot=poster]):not(media-loading-indicator):not([role=dialog]):not([hidden])) {
        pointer-events: auto;
      }

      :host(:not([${I.AUDIO}])) *[part~=layer][part~=centered-layer] {
        align-items: center;
        justify-content: center;
      }

      :host(:not([${I.AUDIO}])) ::slotted(media-gesture-receiver[slot=gestures-chrome]),
      :host(:not([${I.AUDIO}])) media-gesture-receiver[slot=gestures-chrome] {
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

      
      :host(:not([${I.AUDIO}])) .spacer {
        flex-grow: 1;
      }

      
      :host(:-webkit-full-screen) {
        
        width: 100% !important;
        height: 100% !important;
      }

      
      ::slotted(:not([slot=media]):not([slot=poster]):not([${I.NO_AUTOHIDE}]):not([hidden]):not([role=dialog])) {
        opacity: 1;
        transition: var(--media-control-transition-in, opacity 0.25s);
      }

      
      :host([${I.USER_INACTIVE}]:not([${s.MEDIA_PAUSED}]):not([${s.MEDIA_IS_AIRPLAYING}]):not([${s.MEDIA_IS_CASTING}]):not([${I.AUDIO}])) ::slotted(:not([slot=media]):not([slot=poster]):not([${I.NO_AUTOHIDE}]):not([role=dialog])) {
        opacity: 0;
        transition: var(--media-control-transition-out, opacity 1s);
      }

      :host([${I.USER_INACTIVE}]:not([${I.NO_AUTOHIDE}]):not([${s.MEDIA_PAUSED}]):not([${s.MEDIA_IS_CASTING}]):not([${I.AUDIO}])) ::slotted([slot=media]) {
        cursor: none;
      }

      :host([${I.USER_INACTIVE}][${I.AUTOHIDE_OVER_CONTROLS}]:not([${I.NO_AUTOHIDE}]):not([${s.MEDIA_PAUSED}]):not([${s.MEDIA_IS_CASTING}]):not([${I.AUDIO}])) * {
        --media-cursor: none;
        cursor: none;
      }


      ::slotted(media-control-bar)  {
        align-self: stretch;
      }

      
      :host(:not([${I.AUDIO}])[${s.MEDIA_HAS_PLAYED}]) slot[name=poster] {
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
        <template shadowrootmode="${Ot.shadowRootOptions.mode}">
          ${Ot.getTemplateHTML({})}
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
  `}var oa=Object.values(s),na="sm:384 md:576 lg:768 xl:960";function sa(i){Zo(i.target,i.contentRect.width)}function Zo(i,t){var d;if(!i.isConnected)return;let e=(d=i.getAttribute(I.BREAKPOINTS))!=null?d:na,r=aa(e),o=da(r,t),n=!1;if(Object.keys(r).forEach(u=>{if(o.includes(u)){i.hasAttribute(`breakpoint${u}`)||(i.setAttribute(`breakpoint${u}`,""),n=!0);return}i.hasAttribute(`breakpoint${u}`)&&(i.removeAttribute(`breakpoint${u}`),n=!0)}),n){let u=new CustomEvent(be.BREAKPOINTS_CHANGE,{detail:o});i.dispatchEvent(u)}i.breakpointsComputed||(i.breakpointsComputed=!0,i.dispatchEvent(new CustomEvent(be.BREAKPOINTS_COMPUTED,{bubbles:!0,composed:!0})))}function aa(i){let t=i.split(/\s+/);return Object.fromEntries(t.map(e=>e.split(":")))}function da(i,t){return Object.keys(i).filter(e=>t>=parseInt(i[e]))}var Ht,Re,Ze,ke,Ft,Wi,Xo,Xe,$t,qi,Jo,Yi,en,Je,Gi,Bt,wr,xe,Nt,Te=class extends l.HTMLElement{constructor(){super();m(this,Wi);m(this,qi);m(this,Yi);m(this,Je);m(this,Bt);m(this,xe);m(this,Ht,0);m(this,Re,null);m(this,Ze,null);m(this,ke,void 0);this.breakpointsComputed=!1;m(this,Ft,new MutationObserver(v(this,Wi,Xo).bind(this)));m(this,Xe,!1);m(this,$t,e=>{a(this,Xe)||(setTimeout(()=>{sa(e),p(this,Xe,!1)},0),p(this,Xe,!0))});if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let r=F(this.attributes),o=this.constructor.getTemplateHTML(r);this.shadowRoot.setHTMLUnsafe?this.shadowRoot.setHTMLUnsafe(o):this.shadowRoot.innerHTML=o}let e=this.querySelector(":scope > slot[slot=media]");e&&e.addEventListener("slotchange",()=>{if(!e.assignedElements({flatten:!0}).length){a(this,Re)&&this.mediaUnsetCallback(a(this,Re));return}this.handleMediaUpdated(this.media)})}static get observedAttributes(){return[I.AUTOHIDE,I.GESTURES_DISABLED].concat(oa).filter(e=>![s.MEDIA_RENDITION_LIST,s.MEDIA_AUDIO_TRACK_LIST,s.MEDIA_CHAPTERS_CUES,s.MEDIA_WIDTH,s.MEDIA_HEIGHT,s.MEDIA_ERROR,s.MEDIA_ERROR_MESSAGE].includes(e))}attributeChangedCallback(e,r,o){e.toLowerCase()==I.AUTOHIDE&&(this.autohide=o)}get media(){let e=this.querySelector(":scope > [slot=media]");return(e==null?void 0:e.nodeName)=="SLOT"&&(e=e.assignedElements({flatten:!0})[0]),e}async handleMediaUpdated(e){e&&(p(this,Re,e),e.localName.includes("-")&&await l.customElements.whenDefined(e.localName),this.mediaSetCallback(e))}connectedCallback(){var o;a(this,Ft).observe(this,{childList:!0,subtree:!0}),Fi(this,a(this,$t));let r=this.getAttribute(I.AUDIO)!=null?b("audio player"):b("video player");this.setAttribute("role","region"),this.setAttribute("aria-label",r),this.handleMediaUpdated(this.media),this.setAttribute(I.USER_INACTIVE,""),Zo(this,this.getBoundingClientRect().width),this.addEventListener("pointerdown",this),this.addEventListener("pointermove",this),this.addEventListener("pointerup",this),this.addEventListener("mouseleave",this),this.addEventListener("keyup",this),(o=l.window)==null||o.addEventListener("mouseup",this)}disconnectedCallback(){var e;a(this,Ft).disconnect(),$i(this,a(this,$t)),this.media&&this.mediaUnsetCallback(this.media),(e=l.window)==null||e.removeEventListener("mouseup",this)}mediaSetCallback(e){}mediaUnsetCallback(e){p(this,Re,null)}handleEvent(e){switch(e.type){case"pointerdown":p(this,Ht,e.timeStamp);break;case"pointermove":v(this,qi,Jo).call(this,e);break;case"pointerup":v(this,Yi,en).call(this,e);break;case"mouseleave":v(this,Je,Gi).call(this);break;case"mouseup":this.removeAttribute(I.KEYBOARD_CONTROL);break;case"keyup":v(this,xe,Nt).call(this),this.setAttribute(I.KEYBOARD_CONTROL,"");break}}set autohide(e){let r=Number(e);p(this,ke,isNaN(r)?0:r)}get autohide(){return(a(this,ke)===void 0?2:a(this,ke)).toString()}get breakpoints(){return L(this,I.BREAKPOINTS)}set breakpoints(e){R(this,I.BREAKPOINTS,e)}get audio(){return S(this,I.AUDIO)}set audio(e){y(this,I.AUDIO,e)}get gesturesDisabled(){return S(this,I.GESTURES_DISABLED)}set gesturesDisabled(e){y(this,I.GESTURES_DISABLED,e)}get keyboardControl(){return S(this,I.KEYBOARD_CONTROL)}set keyboardControl(e){y(this,I.KEYBOARD_CONTROL,e)}get noAutohide(){return S(this,I.NO_AUTOHIDE)}set noAutohide(e){y(this,I.NO_AUTOHIDE,e)}get autohideOverControls(){return S(this,I.AUTOHIDE_OVER_CONTROLS)}set autohideOverControls(e){y(this,I.AUTOHIDE_OVER_CONTROLS,e)}get userInteractive(){return S(this,I.USER_INACTIVE)}set userInteractive(e){y(this,I.USER_INACTIVE,e)}};Ht=new WeakMap,Re=new WeakMap,Ze=new WeakMap,ke=new WeakMap,Ft=new WeakMap,Wi=new WeakSet,Xo=function(e){let r=this.media;for(let o of e){if(o.type!=="childList")continue;let n=o.removedNodes;for(let d of n){if(d.slot!="media"||o.target!=this)continue;let u=o.previousSibling&&o.previousSibling.previousElementSibling;if(!u||!r)this.mediaUnsetCallback(d);else{let c=u.slot!=="media";for(;(u=u.previousSibling)!==null;)u.slot=="media"&&(c=!1);c&&this.mediaUnsetCallback(d)}}if(r)for(let d of o.addedNodes)d===r&&this.handleMediaUpdated(r)}},Xe=new WeakMap,$t=new WeakMap,qi=new WeakSet,Jo=function(e){if(e.pointerType!=="mouse"&&e.timeStamp-a(this,Ht)<250)return;v(this,Bt,wr).call(this),clearTimeout(a(this,Ze));let r=this.hasAttribute(I.AUTOHIDE_OVER_CONTROLS);([this,this.media].includes(e.target)||r)&&v(this,xe,Nt).call(this)},Yi=new WeakSet,en=function(e){if(e.pointerType==="touch"){let r=!this.hasAttribute(I.USER_INACTIVE);[this,this.media].includes(e.target)&&r?v(this,Je,Gi).call(this):v(this,xe,Nt).call(this)}else e.composedPath().some(r=>["media-play-button","media-fullscreen-button"].includes(r==null?void 0:r.localName))&&v(this,xe,Nt).call(this)},Je=new WeakSet,Gi=function(){if(a(this,ke)<0||this.hasAttribute(I.USER_INACTIVE))return;this.setAttribute(I.USER_INACTIVE,"");let e=new l.CustomEvent(be.USER_INACTIVE_CHANGE,{composed:!0,bubbles:!0,detail:!0});this.dispatchEvent(e)},Bt=new WeakSet,wr=function(){if(!this.hasAttribute(I.USER_INACTIVE))return;this.removeAttribute(I.USER_INACTIVE);let e=new l.CustomEvent(be.USER_INACTIVE_CHANGE,{composed:!0,bubbles:!0,detail:!1});this.dispatchEvent(e)},xe=new WeakSet,Nt=function(){v(this,Bt,wr).call(this),clearTimeout(a(this,Ze));let e=parseInt(this.autohide);e<0||p(this,Ze,setTimeout(()=>{v(this,Je,Gi).call(this)},e*1e3))},Te.shadowRootOptions={mode:"open"},Te.getTemplateHTML=ra;l.customElements.get("media-container")||l.customElements.define("media-container",Te);var tn=Te;var De,we,Vt,Ie,le,Ae,et=class{constructor(t,e,{defaultValue:r}={defaultValue:void 0}){m(this,le);m(this,De,void 0);m(this,we,void 0);m(this,Vt,void 0);m(this,Ie,new Set);p(this,De,t),p(this,we,e),p(this,Vt,new Set(r))}[Symbol.iterator](){return a(this,le,Ae).values()}get length(){return a(this,le,Ae).size}get value(){var t;return(t=[...a(this,le,Ae)].join(" "))!=null?t:""}set value(t){var e;t!==this.value&&(p(this,Ie,new Set),this.add(...(e=t==null?void 0:t.split(" "))!=null?e:[]))}toString(){return this.value}item(t){return[...a(this,le,Ae)][t]}values(){return a(this,le,Ae).values()}forEach(t,e){a(this,le,Ae).forEach(t,e)}add(...t){var e,r;t.forEach(o=>a(this,Ie).add(o)),!(this.value===""&&!((e=a(this,De))!=null&&e.hasAttribute(`${a(this,we)}`)))&&((r=a(this,De))==null||r.setAttribute(`${a(this,we)}`,`${this.value}`))}remove(...t){var e;t.forEach(r=>a(this,Ie).delete(r)),(e=a(this,De))==null||e.setAttribute(`${a(this,we)}`,`${this.value}`)}contains(t){return a(this,le,Ae).has(t)}toggle(t,e){return typeof e!="undefined"?e?(this.add(t),!0):(this.remove(t),!1):this.contains(t)?(this.remove(t),!1):(this.add(t),!0)}replace(t,e){return this.remove(t),this.add(e),t===e}};De=new WeakMap,we=new WeakMap,Vt=new WeakMap,Ie=new WeakMap,le=new WeakSet,Ae=function(){return a(this,Ie).size?a(this,Ie):a(this,Vt)};var la=(i="")=>i.split(/\s+/),rn=(i="")=>{let[t,e,r]=i.split(":"),o=r?decodeURIComponent(r):void 0;return{kind:t==="cc"?G.CAPTIONS:G.SUBTITLES,language:e,label:o}},Cr=(i="",t={})=>la(i).map(e=>{let r=rn(e);return{...t,...r}}),Pr=i=>i?Array.isArray(i)?i.map(t=>typeof t=="string"?rn(t):t):typeof i=="string"?Cr(i):[i]:[],ua=({kind:i,label:t,language:e}={kind:"subtitles"})=>t?`${i==="captions"?"cc":"sb"}:${e}:${encodeURIComponent(t)}`:e,Kt=(i=[])=>Array.prototype.map.call(i,ua).join(" "),ca=(i,t)=>e=>e[i]===t,on=i=>{let t=Object.entries(i).map(([e,r])=>ca(e,r));return e=>t.every(r=>r(e))},Ce=(i,t=[],e=[])=>{let r=Pr(e).map(on),o=n=>r.some(d=>d(n));Array.from(t).filter(o).forEach(n=>{n.mode=i})},Pe=(i,t=()=>!0)=>{if(!(i!=null&&i.textTracks))return[];let e=typeof t=="function"?t:on(t);return Array.from(i.textTracks).filter(e)},nn=i=>{var e;return!!((e=i.mediaSubtitlesShowing)!=null&&e.length)||i.hasAttribute(s.MEDIA_SUBTITLES_SHOWING)};var an=i=>{var r;let{media:t,fullscreenElement:e}=i;try{let o=e&&"requestFullscreen"in e?"requestFullscreen":e&&"webkitRequestFullScreen"in e?"webkitRequestFullScreen":void 0;if(o){let n=(r=e[o])==null?void 0:r.call(e);if(n instanceof Promise)return n.catch(()=>{})}else t!=null&&t.webkitEnterFullscreen?t.webkitEnterFullscreen():t!=null&&t.requestFullscreen&&t.requestFullscreen()}catch(o){console.error(o)}},sn="exitFullscreen"in B?"exitFullscreen":"webkitExitFullscreen"in B?"webkitExitFullscreen":"webkitCancelFullScreen"in B?"webkitCancelFullScreen":void 0,dn=i=>{var e;let{documentElement:t}=i;if(sn){let r=(e=t==null?void 0:t[sn])==null?void 0:e.call(t);if(r instanceof Promise)return r.catch(()=>{})}},Gt="fullscreenElement"in B?"fullscreenElement":"webkitFullscreenElement"in B?"webkitFullscreenElement":void 0,ma=i=>{let{documentElement:t,media:e}=i,r=t==null?void 0:t[Gt];return!r&&"webkitDisplayingFullscreen"in e&&"webkitPresentationMode"in e&&e.webkitDisplayingFullscreen&&e.webkitPresentationMode===_r.FULLSCREEN?e:r},ln=i=>{var n;let{media:t,documentElement:e,fullscreenElement:r=t}=i;if(!t||!e)return!1;let o=ma(i);if(!o)return!1;if(o===r||o===t)return!0;if(o.localName.includes("-")){let d=o.shadowRoot;if(!(Gt in d))return de(o,r);for(;d!=null&&d[Gt];){if(d[Gt]===r)return!0;d=(n=d[Gt])==null?void 0:n.shadowRoot}}return!1},pa="fullscreenEnabled"in B?"fullscreenEnabled":"webkitFullscreenEnabled"in B?"webkitFullscreenEnabled":void 0,un=i=>{let{documentElement:t,media:e}=i;return!!(t!=null&&t[pa])||e&&"webkitSupportsFullscreen"in e};var Qi,Ur=()=>{var i,t;return Qi||(Qi=(t=(i=B)==null?void 0:i.createElement)==null?void 0:t.call(i,"video"),Qi)},cn=async(i=Ur())=>{if(!i)return!1;let t=i.volume;i.volume=t/2+.1;let e=new AbortController,r=await Promise.race([ha(i,e.signal),Ea(i,t)]);return e.abort(),r},ha=(i,t)=>new Promise(e=>{i.addEventListener("volumechange",()=>e(!0),{signal:t})}),Ea=async(i,t)=>{for(let e=0;e<10;e++){if(i.volume===t)return!1;await Pi(10)}return i.volume!==t},ga=/.*Version\/.*Safari\/.*/.test(l.navigator.userAgent),Or=(i=Ur())=>l.matchMedia("(display-mode: standalone)").matches&&ga?!1:typeof(i==null?void 0:i.requestPictureInPicture)=="function",Nr=(i=Ur())=>un({documentElement:B,media:i}),mn=Nr(),pn=Or(),hn=!!l.WebKitPlaybackTargetAvailabilityEvent,En=!!l.chrome;var tt=i=>Pe(i.media,t=>[G.SUBTITLES,G.CAPTIONS].includes(t.kind)).sort((t,e)=>t.kind>=e.kind?1:-1),Hr=i=>Pe(i.media,t=>t.mode===me.SHOWING&&[G.SUBTITLES,G.CAPTIONS].includes(t.kind)),ji=(i,t)=>{let e=tt(i),r=Hr(i),o=!!r.length;if(e.length){if(t===!1||o&&t!==!0)Ce(me.DISABLED,e,r);else if(t===!0||!o&&t!==!1){let n=e[0],{options:d}=i;if(!(d!=null&&d.noSubtitlesLangPref)){let A=globalThis.localStorage.getItem("media-chrome-pref-subtitles-lang"),M=A?[A,...globalThis.navigator.languages]:globalThis.navigator.languages,T=e.filter(f=>M.some(w=>f.language.toLowerCase().startsWith(w.split("-")[0]))).sort((f,w)=>{let k=M.findIndex(C=>f.language.toLowerCase().startsWith(C.split("-")[0])),x=M.findIndex(C=>w.language.toLowerCase().startsWith(C.split("-")[0]));return k-x});T[0]&&(n=T[0])}let{language:u,label:c,kind:h}=n;Ce(me.DISABLED,e,r),Ce(me.SHOWING,e,[{language:u,label:c,kind:h}])}}},zi=(i,t)=>i===t?!0:i==null||t==null||typeof i!=typeof t?!1:typeof i=="number"&&Number.isNaN(i)&&Number.isNaN(t)?!0:typeof i!="object"?!1:Array.isArray(i)?ba(i,t):Object.entries(i).every(([e,r])=>e in t&&zi(r,t[e])),ba=(i,t)=>{let e=Array.isArray(i),r=Array.isArray(t);return e!==r?!1:e||r?i.length!==t.length?!1:i.every((o,n)=>zi(o,t[n])):!0};var fa=Object.values(z),Zi,va=cn().then(i=>(Zi=i,Zi)),gn=async(...i)=>{await Promise.all(i.filter(t=>t).map(async t=>{if(!("localName"in t&&t instanceof l.HTMLElement))return;let e=t.localName;if(!e.includes("-"))return;let r=l.customElements.get(e);r&&t instanceof r||(await l.customElements.whenDefined(e),l.customElements.upgrade(t))}))},Ta=new l.DOMParser,Aa=i=>i&&(Ta.parseFromString(i,"text/html").body.textContent||i),it={mediaError:{get(i,t){let{media:e}=i;if((t==null?void 0:t.type)!=="playing")return e==null?void 0:e.error},mediaEvents:["emptied","error","playing"]},mediaErrorCode:{get(i,t){var r;let{media:e}=i;if((t==null?void 0:t.type)!=="playing")return(r=e==null?void 0:e.error)==null?void 0:r.code},mediaEvents:["emptied","error","playing"]},mediaErrorMessage:{get(i,t){var r,o;let{media:e}=i;if((t==null?void 0:t.type)!=="playing")return(o=(r=e==null?void 0:e.error)==null?void 0:r.message)!=null?o:""},mediaEvents:["emptied","error","playing"]},mediaWidth:{get(i){var e;let{media:t}=i;return(e=t==null?void 0:t.videoWidth)!=null?e:0},mediaEvents:["resize"]},mediaHeight:{get(i){var e;let{media:t}=i;return(e=t==null?void 0:t.videoHeight)!=null?e:0},mediaEvents:["resize"]},mediaPaused:{get(i){var e;let{media:t}=i;return(e=t==null?void 0:t.paused)!=null?e:!0},set(i,t){var r;let{media:e}=t;e&&(i?e.pause():(r=e.play())==null||r.catch(()=>{}))},mediaEvents:["play","playing","pause","emptied"]},mediaHasPlayed:{get(i,t){let{media:e}=i;return e?t?t.type==="playing":!e.paused:!1},mediaEvents:["playing","emptied"]},mediaEnded:{get(i){var e;let{media:t}=i;return(e=t==null?void 0:t.ended)!=null?e:!1},mediaEvents:["seeked","ended","emptied"]},mediaPlaybackRate:{get(i){var e;let{media:t}=i;return(e=t==null?void 0:t.playbackRate)!=null?e:1},set(i,t){let{media:e}=t;e&&Number.isFinite(+i)&&(e.playbackRate=+i)},mediaEvents:["ratechange","loadstart"]},mediaMuted:{get(i){var e;let{media:t}=i;return(e=t==null?void 0:t.muted)!=null?e:!1},set(i,t){let{media:e}=t;if(e){try{l.localStorage.setItem("media-chrome-pref-muted",i?"true":"false")}catch(r){console.debug("Error setting muted pref",r)}e.muted=i}},mediaEvents:["volumechange"],stateOwnersUpdateHandlers:[(i,t)=>{let{options:{noMutedPref:e}}=t,{media:r}=t;if(!(!r||r.muted||e))try{let o=l.localStorage.getItem("media-chrome-pref-muted")==="true";it.mediaMuted.set(o,t),i(o)}catch(o){console.debug("Error getting muted pref",o)}}]},mediaVolume:{get(i){var e;let{media:t}=i;return(e=t==null?void 0:t.volume)!=null?e:1},set(i,t){let{media:e}=t;if(e){try{i==null?l.localStorage.removeItem("media-chrome-pref-volume"):l.localStorage.setItem("media-chrome-pref-volume",i.toString())}catch(r){console.debug("Error setting volume pref",r)}Number.isFinite(+i)&&(e.volume=+i)}},mediaEvents:["volumechange"],stateOwnersUpdateHandlers:[(i,t)=>{let{options:{noVolumePref:e}}=t;if(!e)try{let{media:r}=t;if(!r)return;let o=l.localStorage.getItem("media-chrome-pref-volume");if(o==null)return;it.mediaVolume.set(+o,t),i(+o)}catch(r){console.debug("Error getting volume pref",r)}}]},mediaVolumeLevel:{get(i){let{media:t}=i;return typeof(t==null?void 0:t.volume)=="undefined"?"high":t.muted||t.volume===0?"off":t.volume<.5?"low":t.volume<.75?"medium":"high"},mediaEvents:["volumechange"]},mediaCurrentTime:{get(i){var e;let{media:t}=i;return(e=t==null?void 0:t.currentTime)!=null?e:0},set(i,t){let{media:e}=t;!e||!je(i)||(e.currentTime=i)},mediaEvents:["timeupdate","loadedmetadata"]},mediaDuration:{get(i){let{media:t,options:{defaultDuration:e}={}}=i;return e&&(!t||!t.duration||Number.isNaN(t.duration)||!Number.isFinite(t.duration))?e:Number.isFinite(t==null?void 0:t.duration)?t.duration:Number.NaN},mediaEvents:["durationchange","loadedmetadata","emptied"]},mediaLoading:{get(i){let{media:t}=i;return(t==null?void 0:t.readyState)<3},mediaEvents:["waiting","playing","emptied"]},mediaSeekable:{get(i){var o;let{media:t}=i;if(!((o=t==null?void 0:t.seekable)!=null&&o.length))return;let e=t.seekable.start(0),r=t.seekable.end(t.seekable.length-1);if(!(!e&&!r))return[Number(e.toFixed(3)),Number(r.toFixed(3))]},mediaEvents:["loadedmetadata","emptied","progress","seekablechange"]},mediaBuffered:{get(i){var r;let{media:t}=i,e=(r=t==null?void 0:t.buffered)!=null?r:[];return Array.from(e).map((o,n)=>[Number(e.start(n).toFixed(3)),Number(e.end(n).toFixed(3))])},mediaEvents:["progress","emptied"]},mediaStreamType:{get(i){let{media:t,options:{defaultStreamType:e}={}}=i,r=[z.LIVE,z.ON_DEMAND].includes(e)?e:void 0;if(!t)return r;let{streamType:o}=t;if(fa.includes(o))return o===z.UNKNOWN?r:o;let n=t.duration;return n===1/0?z.LIVE:Number.isFinite(n)?z.ON_DEMAND:r},mediaEvents:["emptied","durationchange","loadedmetadata","streamtypechange"]},mediaTargetLiveWindow:{get(i){let{media:t}=i;if(!t)return Number.NaN;let{targetLiveWindow:e}=t,r=it.mediaStreamType.get(i);return(e==null||Number.isNaN(e))&&r===z.LIVE?0:e},mediaEvents:["emptied","durationchange","loadedmetadata","streamtypechange","targetlivewindowchange"]},mediaTimeIsLive:{get(i){let{media:t,options:{liveEdgeOffset:e=10}={}}=i;if(!t)return!1;if(typeof t.liveEdgeStart=="number")return Number.isNaN(t.liveEdgeStart)?!1:t.currentTime>=t.liveEdgeStart;if(!(it.mediaStreamType.get(i)===z.LIVE))return!1;let o=t.seekable;if(!o)return!0;if(!o.length)return!1;let n=o.end(o.length-1)-e;return t.currentTime>=n},mediaEvents:["playing","timeupdate","progress","waiting","emptied"]},mediaSubtitlesList:{get(i){return tt(i).map(({kind:t,label:e,language:r})=>({kind:t,label:e,language:r}))},mediaEvents:["loadstart"],textTracksEvents:["addtrack","removetrack"]},mediaSubtitlesShowing:{get(i){return Hr(i).map(({kind:t,label:e,language:r})=>({kind:t,label:e,language:r}))},mediaEvents:["loadstart"],textTracksEvents:["addtrack","removetrack","change"],stateOwnersUpdateHandlers:[(i,t)=>{var n,d;let{media:e,options:r}=t;if(!e)return;let o=u=>{var h;!r.defaultSubtitles||u&&![G.CAPTIONS,G.SUBTITLES].includes((h=u==null?void 0:u.track)==null?void 0:h.kind)||ji(t,!0)};return e.addEventListener("loadstart",o),(n=e.textTracks)==null||n.addEventListener("addtrack",o),(d=e.textTracks)==null||d.addEventListener("removetrack",o),()=>{var u,c;e.removeEventListener("loadstart",o),(u=e.textTracks)==null||u.removeEventListener("addtrack",o),(c=e.textTracks)==null||c.removeEventListener("removetrack",o)}}]},mediaChaptersCues:{get(i){var r;let{media:t}=i;if(!t)return[];let[e]=Pe(t,{kind:G.CHAPTERS});return Array.from((r=e==null?void 0:e.cues)!=null?r:[]).map(({text:o,startTime:n,endTime:d})=>({text:Aa(o),startTime:n,endTime:d}))},mediaEvents:["loadstart","loadedmetadata"],textTracksEvents:["addtrack","removetrack","change"],stateOwnersUpdateHandlers:[(i,t)=>{var n;let{media:e}=t;if(!e)return;let r=e.querySelector('track[kind="chapters"][default][src]'),o=(n=e.shadowRoot)==null?void 0:n.querySelector(':is(video,audio) > track[kind="chapters"][default][src]');return r==null||r.addEventListener("load",i),o==null||o.addEventListener("load",i),()=>{r==null||r.removeEventListener("load",i),o==null||o.removeEventListener("load",i)}}]},mediaIsPip:{get(i){var r,o;let{media:t,documentElement:e}=i;if(!t||!e||!e.pictureInPictureElement)return!1;if(e.pictureInPictureElement===t)return!0;if(e.pictureInPictureElement instanceof HTMLMediaElement)return(r=t.localName)!=null&&r.includes("-")?de(t,e.pictureInPictureElement):!1;if(e.pictureInPictureElement.localName.includes("-")){let n=e.pictureInPictureElement.shadowRoot;for(;n!=null&&n.pictureInPictureElement;){if(n.pictureInPictureElement===t)return!0;n=(o=n.pictureInPictureElement)==null?void 0:o.shadowRoot}}return!1},set(i,t){let{media:e}=t;if(e)if(i){if(!B.pictureInPictureEnabled){console.warn("MediaChrome: Picture-in-picture is not enabled");return}if(!e.requestPictureInPicture){console.warn("MediaChrome: The current media does not support picture-in-picture");return}let r=()=>{console.warn("MediaChrome: The media is not ready for picture-in-picture. It must have a readyState > 0.")};e.requestPictureInPicture().catch(o=>{if(o.code===11){if(!e.src){console.warn("MediaChrome: The media is not ready for picture-in-picture. It must have a src set.");return}if(e.readyState===0&&e.preload==="none"){let n=()=>{e.removeEventListener("loadedmetadata",d),e.preload="none"},d=()=>{e.requestPictureInPicture().catch(r),n()};e.addEventListener("loadedmetadata",d),e.preload="metadata",setTimeout(()=>{e.readyState===0&&r(),n()},1e3)}else throw o}else throw o})}else B.pictureInPictureElement&&B.exitPictureInPicture()},mediaEvents:["enterpictureinpicture","leavepictureinpicture"]},mediaRenditionList:{get(i){var e;let{media:t}=i;return[...(e=t==null?void 0:t.videoRenditions)!=null?e:[]].map(r=>({...r}))},mediaEvents:["emptied","loadstart"],videoRenditionsEvents:["addrendition","removerendition"]},mediaRenditionSelected:{get(i){var e,r,o;let{media:t}=i;return(o=(r=t==null?void 0:t.videoRenditions)==null?void 0:r[(e=t.videoRenditions)==null?void 0:e.selectedIndex])==null?void 0:o.id},set(i,t){let{media:e}=t;if(!(e!=null&&e.videoRenditions)){console.warn("MediaController: Rendition selection not supported by this media.");return}let r=i,o=Array.prototype.findIndex.call(e.videoRenditions,n=>n.id==r);e.videoRenditions.selectedIndex!=o&&(e.videoRenditions.selectedIndex=o)},mediaEvents:["emptied"],videoRenditionsEvents:["addrendition","removerendition","change"]},mediaAudioTrackList:{get(i){var e;let{media:t}=i;return[...(e=t==null?void 0:t.audioTracks)!=null?e:[]]},mediaEvents:["emptied","loadstart"],audioTracksEvents:["addtrack","removetrack"]},mediaAudioTrackEnabled:{get(i){var e,r;let{media:t}=i;return(r=[...(e=t==null?void 0:t.audioTracks)!=null?e:[]].find(o=>o.enabled))==null?void 0:r.id},set(i,t){let{media:e}=t;if(!(e!=null&&e.audioTracks)){console.warn("MediaChrome: Audio track selection not supported by this media.");return}let r=i;for(let o of e.audioTracks)o.enabled=r==o.id},mediaEvents:["emptied"],audioTracksEvents:["addtrack","removetrack","change"]},mediaIsFullscreen:{get(i){return ln(i)},set(i,t){i?an(t):dn(t)},rootEvents:["fullscreenchange","webkitfullscreenchange"],mediaEvents:["webkitbeginfullscreen","webkitendfullscreen","webkitpresentationmodechanged"]},mediaIsCasting:{get(i){var e;let{media:t}=i;return!(t!=null&&t.remote)||((e=t.remote)==null?void 0:e.state)==="disconnected"?!1:!!t.remote.state},set(i,t){var r,o;let{media:e}=t;if(e&&!(i&&((r=e.remote)==null?void 0:r.state)!=="disconnected")&&!(!i&&((o=e.remote)==null?void 0:o.state)!=="connected")){if(typeof e.remote.prompt!="function"){console.warn("MediaChrome: Casting is not supported in this environment");return}e.remote.prompt().catch(()=>{})}},remoteEvents:["connect","connecting","disconnect"]},mediaIsAirplaying:{get(){return!1},set(i,t){let{media:e}=t;if(e){if(!(e.webkitShowPlaybackTargetPicker&&l.WebKitPlaybackTargetAvailabilityEvent)){console.error("MediaChrome: received a request to select AirPlay but AirPlay is not supported in this environment");return}e.webkitShowPlaybackTargetPicker()}},mediaEvents:["webkitcurrentplaybacktargetiswirelesschanged"]},mediaFullscreenUnavailable:{get(i){let{media:t}=i;if(!mn||!Nr(t))return Y.UNSUPPORTED}},mediaPipUnavailable:{get(i){let{media:t}=i;if(!pn||!Or(t))return Y.UNSUPPORTED}},mediaVolumeUnavailable:{get(i){let{media:t}=i;if(Zi===!1||(t==null?void 0:t.volume)==null)return Y.UNSUPPORTED},stateOwnersUpdateHandlers:[i=>{Zi==null&&va.then(t=>i(t?void 0:Y.UNSUPPORTED))}]},mediaCastUnavailable:{get(i,{availability:t="not-available"}={}){var r;let{media:e}=i;if(!En||!((r=e==null?void 0:e.remote)!=null&&r.state))return Y.UNSUPPORTED;if(!(t==null||t==="available"))return Y.UNAVAILABLE},stateOwnersUpdateHandlers:[(i,t)=>{var o;let{media:e}=t;return e?(e.disableRemotePlayback||e.hasAttribute("disableremoteplayback")||(o=e==null?void 0:e.remote)==null||o.watchAvailability(n=>{i({availability:n?"available":"not-available"})}).catch(n=>{n.name==="NotSupportedError"?i({availability:null}):i({availability:"not-available"})}),()=>{var n;(n=e==null?void 0:e.remote)==null||n.cancelWatchAvailability().catch(()=>{})}):void 0}]},mediaAirplayUnavailable:{get(i,t){if(!hn)return Y.UNSUPPORTED;if((t==null?void 0:t.availability)==="not-available")return Y.UNAVAILABLE},mediaEvents:["webkitplaybacktargetavailabilitychanged"],stateOwnersUpdateHandlers:[(i,t)=>{var o;let{media:e}=t;return e?(e.disableRemotePlayback||e.hasAttribute("disableremoteplayback")||(o=e==null?void 0:e.remote)==null||o.watchAvailability(n=>{i({availability:n?"available":"not-available"})}).catch(n=>{n.name==="NotSupportedError"?i({availability:null}):i({availability:"not-available"})}),()=>{var n;(n=e==null?void 0:e.remote)==null||n.cancelWatchAvailability().catch(()=>{})}):void 0}]},mediaRenditionUnavailable:{get(i){var e;let{media:t}=i;if(!(t!=null&&t.videoRenditions))return Y.UNSUPPORTED;if(!((e=t.videoRenditions)!=null&&e.length))return Y.UNAVAILABLE},mediaEvents:["emptied","loadstart"],videoRenditionsEvents:["addrendition","removerendition"]},mediaAudioTrackUnavailable:{get(i){var e,r;let{media:t}=i;if(!(t!=null&&t.audioTracks))return Y.UNSUPPORTED;if(((r=(e=t.audioTracks)==null?void 0:e.length)!=null?r:0)<=1)return Y.UNAVAILABLE},mediaEvents:["emptied","loadstart"],audioTracksEvents:["addtrack","removetrack"]}};var bn={[E.MEDIA_PREVIEW_REQUEST](i,t,{detail:e}){var A,M,T;let{media:r}=t,o=e!=null?e:void 0,n,d;if(r&&o!=null){let[f]=Pe(r,{kind:G.METADATA,label:"thumbnails"}),w=Array.prototype.find.call((A=f==null?void 0:f.cues)!=null?A:[],(k,x,C)=>x===0?k.endTime>o:x===C.length-1?k.startTime<=o:k.startTime<=o&&k.endTime>o);if(w){let k=/'^(?:[a-z]+:)?\/\//i.test(w.text)||(M=r==null?void 0:r.querySelector('track[label="thumbnails"]'))==null?void 0:M.src,x=new URL(w.text,k);d=new URLSearchParams(x.hash).get("#xywh").split(",").map(K=>+K),n=x.href}}let u=i.mediaDuration.get(t),h=(T=i.mediaChaptersCues.get(t).find((f,w,k)=>w===k.length-1&&u===f.endTime?f.startTime<=o&&f.endTime>=o:f.startTime<=o&&f.endTime>o))==null?void 0:T.text;return e!=null&&h==null&&(h=""),{mediaPreviewTime:o,mediaPreviewImage:n,mediaPreviewCoords:d,mediaPreviewChapter:h}},[E.MEDIA_PAUSE_REQUEST](i,t){i["mediaPaused"].set(!0,t)},[E.MEDIA_PLAY_REQUEST](i,t){var u,c,h,A;let e="mediaPaused",o=i.mediaStreamType.get(t)===z.LIVE,n=!((u=t.options)!=null&&u.noAutoSeekToLive),d=i.mediaTargetLiveWindow.get(t)>0;if(o&&n&&!d){let M=(c=i.mediaSeekable.get(t))==null?void 0:c[1];if(M){let T=(A=(h=t.options)==null?void 0:h.seekToLiveOffset)!=null?A:0,f=M-T;i.mediaCurrentTime.set(f,t)}}i[e].set(!1,t)},[E.MEDIA_PLAYBACK_RATE_REQUEST](i,t,{detail:e}){let r="mediaPlaybackRate",o=e;i[r].set(o,t)},[E.MEDIA_MUTE_REQUEST](i,t){i["mediaMuted"].set(!0,t)},[E.MEDIA_UNMUTE_REQUEST](i,t){let e="mediaMuted";i.mediaVolume.get(t)||i.mediaVolume.set(.25,t),i[e].set(!1,t)},[E.MEDIA_VOLUME_REQUEST](i,t,{detail:e}){let r="mediaVolume",o=e;o&&i.mediaMuted.get(t)&&i.mediaMuted.set(!1,t),i[r].set(o,t)},[E.MEDIA_SEEK_REQUEST](i,t,{detail:e}){let r="mediaCurrentTime",o=e;i[r].set(o,t)},[E.MEDIA_SEEK_TO_LIVE_REQUEST](i,t){var d,u,c;let e="mediaCurrentTime",r=(d=i.mediaSeekable.get(t))==null?void 0:d[1];if(Number.isNaN(Number(r)))return;let o=(c=(u=t.options)==null?void 0:u.seekToLiveOffset)!=null?c:0,n=r-o;i[e].set(n,t)},[E.MEDIA_SHOW_SUBTITLES_REQUEST](i,t,{detail:e}){var u;let{options:r}=t,o=tt(t),n=Pr(e),d=(u=n[0])==null?void 0:u.language;d&&!r.noSubtitlesLangPref&&l.localStorage.setItem("media-chrome-pref-subtitles-lang",d),Ce(me.SHOWING,o,n)},[E.MEDIA_DISABLE_SUBTITLES_REQUEST](i,t,{detail:e}){let r=tt(t),o=e!=null?e:[];Ce(me.DISABLED,r,o)},[E.MEDIA_TOGGLE_SUBTITLES_REQUEST](i,t,{detail:e}){ji(t,e)},[E.MEDIA_RENDITION_REQUEST](i,t,{detail:e}){let r="mediaRenditionSelected",o=e;i[r].set(o,t)},[E.MEDIA_AUDIO_TRACK_REQUEST](i,t,{detail:e}){let r="mediaAudioTrackEnabled",o=e;i[r].set(o,t)},[E.MEDIA_ENTER_PIP_REQUEST](i,t){let e="mediaIsPip";i.mediaIsFullscreen.get(t)&&i.mediaIsFullscreen.set(!1,t),i[e].set(!0,t)},[E.MEDIA_EXIT_PIP_REQUEST](i,t){i["mediaIsPip"].set(!1,t)},[E.MEDIA_ENTER_FULLSCREEN_REQUEST](i,t){let e="mediaIsFullscreen";i.mediaIsPip.get(t)&&i.mediaIsPip.set(!1,t),i[e].set(!0,t)},[E.MEDIA_EXIT_FULLSCREEN_REQUEST](i,t){i["mediaIsFullscreen"].set(!1,t)},[E.MEDIA_ENTER_CAST_REQUEST](i,t){let e="mediaIsCasting";i.mediaIsFullscreen.get(t)&&i.mediaIsFullscreen.set(!1,t),i[e].set(!0,t)},[E.MEDIA_EXIT_CAST_REQUEST](i,t){i["mediaIsCasting"].set(!1,t)},[E.MEDIA_AIRPLAY_REQUEST](i,t){i["mediaIsAirplaying"].set(!0,t)}};var fn=({media:i,fullscreenElement:t,documentElement:e,stateMediator:r=it,requestMap:o=bn,options:n={},monitorStateOwnersOnlyWithSubscriptions:d=!0})=>{let u=[],c={options:{...n}},h=Object.freeze({mediaPreviewTime:void 0,mediaPreviewImage:void 0,mediaPreviewCoords:void 0,mediaPreviewChapter:void 0}),A=k=>{k!=null&&(zi(k,h)||(h=Object.freeze({...h,...k}),u.forEach(x=>x(h))))},M=()=>{let k=Object.entries(r).reduce((x,[C,{get:K}])=>(x[C]=K(c),x),{});A(k)},T={},f,w=async(k,x)=>{var fo,vo,To,Ao,Io,So,yo,Mo,_o,Lo,Ro,ko,xo,Do,wo,Co;let C=!!f;if(f={...c,...f!=null?f:{},...k},C)return;await gn(...Object.values(k));let K=u.length>0&&x===0&&d,ce=c.media!==f.media,ge=((fo=c.media)==null?void 0:fo.textTracks)!==((vo=f.media)==null?void 0:vo.textTracks),Ct=((To=c.media)==null?void 0:To.videoRenditions)!==((Ao=f.media)==null?void 0:Ao.videoRenditions),Ye=((Io=c.media)==null?void 0:Io.audioTracks)!==((So=f.media)==null?void 0:So.audioTracks),to=((yo=c.media)==null?void 0:yo.remote)!==((Mo=f.media)==null?void 0:Mo.remote),io=c.documentElement!==f.documentElement,ro=!!c.media&&(ce||K),oo=!!((_o=c.media)!=null&&_o.textTracks)&&(ge||K),no=!!((Lo=c.media)!=null&&Lo.videoRenditions)&&(Ct||K),so=!!((Ro=c.media)!=null&&Ro.audioTracks)&&(Ye||K),ao=!!((ko=c.media)!=null&&ko.remote)&&(to||K),lo=!!c.documentElement&&(io||K),uo=ro||oo||no||so||ao||lo,Qe=u.length===0&&x===1&&d,co=!!f.media&&(ce||Qe),mo=!!((xo=f.media)!=null&&xo.textTracks)&&(ge||Qe),po=!!((Do=f.media)!=null&&Do.videoRenditions)&&(Ct||Qe),ho=!!((wo=f.media)!=null&&wo.audioTracks)&&(Ye||Qe),Eo=!!((Co=f.media)!=null&&Co.remote)&&(to||Qe),go=!!f.documentElement&&(io||Qe),bo=co||mo||po||ho||Eo||go;if(!(uo||bo)){Object.entries(f).forEach(([H,Pt])=>{c[H]=Pt}),M(),f=void 0;return}Object.entries(r).forEach(([H,{get:Pt,mediaEvents:ks=[],textTracksEvents:xs=[],videoRenditionsEvents:Ds=[],audioTracksEvents:ws=[],remoteEvents:Cs=[],rootEvents:Ps=[],stateOwnersUpdateHandlers:Us=[]}])=>{T[H]||(T[H]={});let Q=$=>{let j=Pt(c,$);A({[H]:j})},V;V=T[H].mediaEvents,ks.forEach($=>{V&&ro&&(c.media.removeEventListener($,V),T[H].mediaEvents=void 0),co&&(f.media.addEventListener($,Q),T[H].mediaEvents=Q)}),V=T[H].textTracksEvents,xs.forEach($=>{var j,J;V&&oo&&((j=c.media.textTracks)==null||j.removeEventListener($,V),T[H].textTracksEvents=void 0),mo&&((J=f.media.textTracks)==null||J.addEventListener($,Q),T[H].textTracksEvents=Q)}),V=T[H].videoRenditionsEvents,Ds.forEach($=>{var j,J;V&&no&&((j=c.media.videoRenditions)==null||j.removeEventListener($,V),T[H].videoRenditionsEvents=void 0),po&&((J=f.media.videoRenditions)==null||J.addEventListener($,Q),T[H].videoRenditionsEvents=Q)}),V=T[H].audioTracksEvents,ws.forEach($=>{var j,J;V&&so&&((j=c.media.audioTracks)==null||j.removeEventListener($,V),T[H].audioTracksEvents=void 0),ho&&((J=f.media.audioTracks)==null||J.addEventListener($,Q),T[H].audioTracksEvents=Q)}),V=T[H].remoteEvents,Cs.forEach($=>{var j,J;V&&ao&&((j=c.media.remote)==null||j.removeEventListener($,V),T[H].remoteEvents=void 0),Eo&&((J=f.media.remote)==null||J.addEventListener($,Q),T[H].remoteEvents=Q)}),V=T[H].rootEvents,Ps.forEach($=>{V&&lo&&(c.documentElement.removeEventListener($,V),T[H].rootEvents=void 0),go&&(f.documentElement.addEventListener($,Q),T[H].rootEvents=Q)});let Po=T[H].stateOwnersUpdateHandlers;Us.forEach($=>{Po&&uo&&Po(),bo&&(T[H].stateOwnersUpdateHandlers=$(Q,f))})}),Object.entries(f).forEach(([H,Pt])=>{c[H]=Pt}),M(),f=void 0};return w({media:i,fullscreenElement:t,documentElement:e,options:n}),{dispatch(k){let{type:x,detail:C}=k;if(o[x]&&h.mediaErrorCode==null){A(o[x](r,c,k));return}x==="mediaelementchangerequest"?w({media:C}):x==="fullscreenelementchangerequest"?w({fullscreenElement:C}):x==="documentelementchangerequest"?w({documentElement:C}):x==="optionschangerequest"&&Object.entries(C!=null?C:{}).forEach(([K,ce])=>{c.options[K]=ce})},getState(){return h},subscribe(k){return w({},u.length+1),u.push(k),k(h),()=>{let x=u.indexOf(k);x>=0&&(w({},u.length-1),u.splice(x,1))}}}};var vn=["ArrowLeft","ArrowRight","Enter"," ","f","m","k","c"],Tn=10,g={DEFAULT_SUBTITLES:"defaultsubtitles",DEFAULT_STREAM_TYPE:"defaultstreamtype",DEFAULT_DURATION:"defaultduration",FULLSCREEN_ELEMENT:"fullscreenelement",HOTKEYS:"hotkeys",KEYS_USED:"keysused",LIVE_EDGE_OFFSET:"liveedgeoffset",SEEK_TO_LIVE_OFFSET:"seektoliveoffset",NO_AUTO_SEEK_TO_LIVE:"noautoseektolive",NO_HOTKEYS:"nohotkeys",NO_VOLUME_PREF:"novolumepref",NO_SUBTITLES_LANG_PREF:"nosubtitleslangpref",NO_DEFAULT_STORE:"nodefaultstore",KEYBOARD_FORWARD_SEEK_OFFSET:"keyboardforwardseekoffset",KEYBOARD_BACKWARD_SEEK_OFFSET:"keyboardbackwardseekoffset",LANG:"lang"},Se,rt,N,ot,ee,qt,Yt,$r,Oe,Wt,Qt,Br,Xi=class extends Te{constructor(){super();m(this,Yt);m(this,Oe);m(this,Qt);this.mediaStateReceivers=[];this.associatedElementSubscriptions=new Map;m(this,Se,new et(this,g.HOTKEYS));m(this,rt,void 0);m(this,N,void 0);m(this,ot,void 0);m(this,ee,void 0);m(this,qt,e=>{var r;(r=a(this,N))==null||r.dispatch(e)});this.associateElement(this);let e={};p(this,ot,r=>{Object.entries(r).forEach(([o,n])=>{if(o in e&&e[o]===n)return;this.propagateMediaState(o,n);let d=o.toLowerCase(),u=new l.CustomEvent(Mr[d],{composed:!0,detail:n});this.dispatchEvent(u)}),e=r}),this.enableHotkeys()}static get observedAttributes(){return super.observedAttributes.concat(g.NO_HOTKEYS,g.HOTKEYS,g.DEFAULT_STREAM_TYPE,g.DEFAULT_SUBTITLES,g.DEFAULT_DURATION,g.LANG)}get mediaStore(){return a(this,N)}set mediaStore(e){var r,o;if(a(this,N)&&((r=a(this,ee))==null||r.call(this),p(this,ee,void 0)),p(this,N,e),!a(this,N)&&!this.hasAttribute(g.NO_DEFAULT_STORE)){v(this,Yt,$r).call(this);return}p(this,ee,(o=a(this,N))==null?void 0:o.subscribe(a(this,ot)))}get fullscreenElement(){var e;return(e=a(this,rt))!=null?e:this}set fullscreenElement(e){var r;this.hasAttribute(g.FULLSCREEN_ELEMENT)&&this.removeAttribute(g.FULLSCREEN_ELEMENT),p(this,rt,e),(r=a(this,N))==null||r.dispatch({type:"fullscreenelementchangerequest",detail:this.fullscreenElement})}get defaultSubtitles(){return S(this,g.DEFAULT_SUBTITLES)}set defaultSubtitles(e){y(this,g.DEFAULT_SUBTITLES,e)}get defaultStreamType(){return L(this,g.DEFAULT_STREAM_TYPE)}set defaultStreamType(e){R(this,g.DEFAULT_STREAM_TYPE,e)}get defaultDuration(){return D(this,g.DEFAULT_DURATION)}set defaultDuration(e){O(this,g.DEFAULT_DURATION,e)}get noHotkeys(){return S(this,g.NO_HOTKEYS)}set noHotkeys(e){y(this,g.NO_HOTKEYS,e)}get keysUsed(){return L(this,g.KEYS_USED)}set keysUsed(e){R(this,g.KEYS_USED,e)}get liveEdgeOffset(){return D(this,g.LIVE_EDGE_OFFSET)}set liveEdgeOffset(e){O(this,g.LIVE_EDGE_OFFSET,e)}get noAutoSeekToLive(){return S(this,g.NO_AUTO_SEEK_TO_LIVE)}set noAutoSeekToLive(e){y(this,g.NO_AUTO_SEEK_TO_LIVE,e)}get noVolumePref(){return S(this,g.NO_VOLUME_PREF)}set noVolumePref(e){y(this,g.NO_VOLUME_PREF,e)}get noSubtitlesLangPref(){return S(this,g.NO_SUBTITLES_LANG_PREF)}set noSubtitlesLangPref(e){y(this,g.NO_SUBTITLES_LANG_PREF,e)}get noDefaultStore(){return S(this,g.NO_DEFAULT_STORE)}set noDefaultStore(e){y(this,g.NO_DEFAULT_STORE,e)}attributeChangedCallback(e,r,o){var n,d,u,c,h,A,M,T;if(super.attributeChangedCallback(e,r,o),e===g.NO_HOTKEYS)o!==r&&o===""?(this.hasAttribute(g.HOTKEYS)&&console.warn("Media Chrome: Both `hotkeys` and `nohotkeys` have been set. All hotkeys will be disabled."),this.disableHotkeys()):o!==r&&o===null&&this.enableHotkeys();else if(e===g.HOTKEYS)a(this,Se).value=o;else if(e===g.DEFAULT_SUBTITLES&&o!==r)(n=a(this,N))==null||n.dispatch({type:"optionschangerequest",detail:{defaultSubtitles:this.hasAttribute(g.DEFAULT_SUBTITLES)}});else if(e===g.DEFAULT_STREAM_TYPE)(u=a(this,N))==null||u.dispatch({type:"optionschangerequest",detail:{defaultStreamType:(d=this.getAttribute(g.DEFAULT_STREAM_TYPE))!=null?d:void 0}});else if(e===g.LIVE_EDGE_OFFSET)(c=a(this,N))==null||c.dispatch({type:"optionschangerequest",detail:{liveEdgeOffset:this.hasAttribute(g.LIVE_EDGE_OFFSET)?+this.getAttribute(g.LIVE_EDGE_OFFSET):void 0,seekToLiveOffset:this.hasAttribute(g.SEEK_TO_LIVE_OFFSET)?void 0:+this.getAttribute(g.LIVE_EDGE_OFFSET)}});else if(e===g.SEEK_TO_LIVE_OFFSET)(h=a(this,N))==null||h.dispatch({type:"optionschangerequest",detail:{seekToLiveOffset:this.hasAttribute(g.SEEK_TO_LIVE_OFFSET)?+this.getAttribute(g.SEEK_TO_LIVE_OFFSET):void 0}});else if(e===g.NO_AUTO_SEEK_TO_LIVE)(A=a(this,N))==null||A.dispatch({type:"optionschangerequest",detail:{noAutoSeekToLive:this.hasAttribute(g.NO_AUTO_SEEK_TO_LIVE)}});else if(e===g.FULLSCREEN_ELEMENT){let f=o?(M=this.getRootNode())==null?void 0:M.getElementById(o):void 0;p(this,rt,f),(T=a(this,N))==null||T.dispatch({type:"fullscreenelementchangerequest",detail:this.fullscreenElement})}else e===g.LANG&&o!==r&&Ko(o)}connectedCallback(){var e,r;!a(this,N)&&!this.hasAttribute(g.NO_DEFAULT_STORE)&&v(this,Yt,$r).call(this),(e=a(this,N))==null||e.dispatch({type:"documentelementchangerequest",detail:B}),super.connectedCallback(),a(this,N)&&!a(this,ee)&&p(this,ee,(r=a(this,N))==null?void 0:r.subscribe(a(this,ot))),this.enableHotkeys()}disconnectedCallback(){var e,r,o,n;(e=super.disconnectedCallback)==null||e.call(this),a(this,N)&&((r=a(this,N))==null||r.dispatch({type:"documentelementchangerequest",detail:void 0}),(o=a(this,N))==null||o.dispatch({type:E.MEDIA_TOGGLE_SUBTITLES_REQUEST,detail:!1})),a(this,ee)&&((n=a(this,ee))==null||n.call(this),p(this,ee,void 0))}mediaSetCallback(e){var r;super.mediaSetCallback(e),(r=a(this,N))==null||r.dispatch({type:"mediaelementchangerequest",detail:e}),e.hasAttribute("tabindex")||(e.tabIndex=-1)}mediaUnsetCallback(e){var r;super.mediaUnsetCallback(e),(r=a(this,N))==null||r.dispatch({type:"mediaelementchangerequest",detail:void 0})}propagateMediaState(e,r){In(this.mediaStateReceivers,e,r)}associateElement(e){if(!e)return;let{associatedElementSubscriptions:r}=this;if(r.has(e))return;let o=this.registerMediaStateReceiver.bind(this),n=this.unregisterMediaStateReceiver.bind(this),d=La(e,o,n);Object.values(E).forEach(u=>{e.addEventListener(u,a(this,qt))}),r.set(e,d)}unassociateElement(e){if(!e)return;let{associatedElementSubscriptions:r}=this;if(!r.has(e))return;r.get(e)(),r.delete(e),Object.values(E).forEach(n=>{e.removeEventListener(n,a(this,qt))})}registerMediaStateReceiver(e){if(!e)return;let r=this.mediaStateReceivers;r.indexOf(e)>-1||(r.push(e),a(this,N)&&Object.entries(a(this,N).getState()).forEach(([n,d])=>{In([e],n,d)}))}unregisterMediaStateReceiver(e){let r=this.mediaStateReceivers,o=r.indexOf(e);o<0||r.splice(o,1)}enableHotkeys(){this.addEventListener("keydown",v(this,Qt,Br))}disableHotkeys(){this.removeEventListener("keydown",v(this,Qt,Br)),this.removeEventListener("keyup",v(this,Oe,Wt))}get hotkeys(){return L(this,g.HOTKEYS)}set hotkeys(e){R(this,g.HOTKEYS,e)}keyboardShortcutHandler(e){var c,h,A,M,T;let r=e.target;if(((A=(h=(c=r.getAttribute(g.KEYS_USED))==null?void 0:c.split(" "))!=null?h:r==null?void 0:r.keysUsed)!=null?A:[]).map(f=>f==="Space"?" ":f).filter(Boolean).includes(e.key))return;let n,d,u;if(!a(this,Se).contains(`no${e.key.toLowerCase()}`)&&!(e.key===" "&&a(this,Se).contains("nospace")))switch(e.key){case" ":case"k":n=a(this,N).getState().mediaPaused?E.MEDIA_PLAY_REQUEST:E.MEDIA_PAUSE_REQUEST,this.dispatchEvent(new l.CustomEvent(n,{composed:!0,bubbles:!0}));break;case"m":n=this.mediaStore.getState().mediaVolumeLevel==="off"?E.MEDIA_UNMUTE_REQUEST:E.MEDIA_MUTE_REQUEST,this.dispatchEvent(new l.CustomEvent(n,{composed:!0,bubbles:!0}));break;case"f":n=this.mediaStore.getState().mediaIsFullscreen?E.MEDIA_EXIT_FULLSCREEN_REQUEST:E.MEDIA_ENTER_FULLSCREEN_REQUEST,this.dispatchEvent(new l.CustomEvent(n,{composed:!0,bubbles:!0}));break;case"c":this.dispatchEvent(new l.CustomEvent(E.MEDIA_TOGGLE_SUBTITLES_REQUEST,{composed:!0,bubbles:!0}));break;case"ArrowLeft":{let f=this.hasAttribute(g.KEYBOARD_BACKWARD_SEEK_OFFSET)?+this.getAttribute(g.KEYBOARD_BACKWARD_SEEK_OFFSET):Tn;d=Math.max(((M=this.mediaStore.getState().mediaCurrentTime)!=null?M:0)-f,0),u=new l.CustomEvent(E.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:d}),this.dispatchEvent(u);break}case"ArrowRight":{let f=this.hasAttribute(g.KEYBOARD_FORWARD_SEEK_OFFSET)?+this.getAttribute(g.KEYBOARD_FORWARD_SEEK_OFFSET):Tn;d=Math.max(((T=this.mediaStore.getState().mediaCurrentTime)!=null?T:0)+f,0),u=new l.CustomEvent(E.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:d}),this.dispatchEvent(u);break}default:break}}};Se=new WeakMap,rt=new WeakMap,N=new WeakMap,ot=new WeakMap,ee=new WeakMap,qt=new WeakMap,Yt=new WeakSet,$r=function(){var e;this.mediaStore=fn({media:this.media,fullscreenElement:this.fullscreenElement,options:{defaultSubtitles:this.hasAttribute(g.DEFAULT_SUBTITLES),defaultDuration:this.hasAttribute(g.DEFAULT_DURATION)?+this.getAttribute(g.DEFAULT_DURATION):void 0,defaultStreamType:(e=this.getAttribute(g.DEFAULT_STREAM_TYPE))!=null?e:void 0,liveEdgeOffset:this.hasAttribute(g.LIVE_EDGE_OFFSET)?+this.getAttribute(g.LIVE_EDGE_OFFSET):void 0,seekToLiveOffset:this.hasAttribute(g.SEEK_TO_LIVE_OFFSET)?+this.getAttribute(g.SEEK_TO_LIVE_OFFSET):this.hasAttribute(g.LIVE_EDGE_OFFSET)?+this.getAttribute(g.LIVE_EDGE_OFFSET):void 0,noAutoSeekToLive:this.hasAttribute(g.NO_AUTO_SEEK_TO_LIVE),noVolumePref:this.hasAttribute(g.NO_VOLUME_PREF),noSubtitlesLangPref:this.hasAttribute(g.NO_SUBTITLES_LANG_PREF)}})},Oe=new WeakSet,Wt=function(e){let{key:r}=e;if(!vn.includes(r)){this.removeEventListener("keyup",v(this,Oe,Wt));return}this.keyboardShortcutHandler(e)},Qt=new WeakSet,Br=function(e){let{metaKey:r,altKey:o,key:n}=e;if(r||o||!vn.includes(n)){this.removeEventListener("keyup",v(this,Oe,Wt));return}[" ","ArrowLeft","ArrowRight"].includes(n)&&!(a(this,Se).contains(`no${n.toLowerCase()}`)||n===" "&&a(this,Se).contains("nospace"))&&e.preventDefault(),this.addEventListener("keyup",v(this,Oe,Wt),{once:!0})};var Ia=Object.values(s),Sa=Object.values(Di),Sn=i=>{var r,o,n,d;let{observedAttributes:t}=i.constructor;!t&&((r=i.nodeName)!=null&&r.includes("-"))&&(l.customElements.upgrade(i),{observedAttributes:t}=i.constructor);let e=(d=(n=(o=i==null?void 0:i.getAttribute)==null?void 0:o.call(i,_.MEDIA_CHROME_ATTRIBUTES))==null?void 0:n.split)==null?void 0:d.call(n,/\s+/);return Array.isArray(t||e)?(t||e).filter(u=>Ia.includes(u)):[]},ya=i=>{var t,e;return(t=i.nodeName)!=null&&t.includes("-")&&l.customElements.get((e=i.nodeName)==null?void 0:e.toLowerCase())&&!(i instanceof l.customElements.get(i.nodeName.toLowerCase()))&&l.customElements.upgrade(i),Sa.some(r=>r in i)},Vr=i=>ya(i)||!!Sn(i).length,An=i=>{var t;return(t=i==null?void 0:i.join)==null?void 0:t.call(i,":")},Fr={[s.MEDIA_SUBTITLES_LIST]:Kt,[s.MEDIA_SUBTITLES_SHOWING]:Kt,[s.MEDIA_SEEKABLE]:An,[s.MEDIA_BUFFERED]:i=>i==null?void 0:i.map(An).join(" "),[s.MEDIA_PREVIEW_COORDS]:i=>i==null?void 0:i.join(" "),[s.MEDIA_RENDITION_LIST]:No,[s.MEDIA_AUDIO_TRACK_LIST]:Ho},Ma=async(i,t,e)=>{var o,n;if(i.isConnected||await Pi(0),typeof e=="boolean"||e==null)return y(i,t,e);if(typeof e=="number")return O(i,t,e);if(typeof e=="string")return R(i,t,e);if(Array.isArray(e)&&!e.length)return i.removeAttribute(t);let r=(n=(o=Fr[t])==null?void 0:o.call(Fr,e))!=null?n:e;return i.setAttribute(t,r)},_a=i=>{var t;return!!((t=i.closest)!=null&&t.call(i,'*[slot="media"]'))},Ue=(i,t)=>{if(_a(i))return;let e=(o,n)=>{var h,A;Vr(o)&&n(o);let{children:d=[]}=o!=null?o:{},u=(A=(h=o==null?void 0:o.shadowRoot)==null?void 0:h.children)!=null?A:[];[...d,...u].forEach(M=>Ue(M,n))},r=i==null?void 0:i.nodeName.toLowerCase();if(r.includes("-")&&!Vr(i)){l.customElements.whenDefined(r).then(()=>{e(i,t)});return}e(i,t)},In=(i,t,e)=>{i.forEach(r=>{if(t in r){r[t]=e;return}let o=Sn(r),n=t.toLowerCase();o.includes(n)&&Ma(r,n,e)})},La=(i,t,e)=>{Ue(i,t);let r=A=>{var T;let M=(T=A==null?void 0:A.composedPath()[0])!=null?T:A.target;t(M)},o=A=>{var T;let M=(T=A==null?void 0:A.composedPath()[0])!=null?T:A.target;e(M)};i.addEventListener(E.REGISTER_MEDIA_STATE_RECEIVER,r),i.addEventListener(E.UNREGISTER_MEDIA_STATE_RECEIVER,o);let n=A=>{A.forEach(M=>{let{addedNodes:T=[],removedNodes:f=[],type:w,target:k,attributeName:x}=M;w==="childList"?(Array.prototype.forEach.call(T,C=>Ue(C,t)),Array.prototype.forEach.call(f,C=>Ue(C,e))):w==="attributes"&&x===_.MEDIA_CHROME_ATTRIBUTES&&(Vr(k)?t(k):e(k))})},d=[],u=A=>{let M=A.target;M.name!=="media"&&(d.forEach(T=>Ue(T,e)),d=[...M.assignedElements({flatten:!0})],d.forEach(T=>Ue(T,t)))};i.addEventListener("slotchange",u);let c=new MutationObserver(n);return c.observe(i,{childList:!0,attributes:!0,subtree:!0}),()=>{Ue(i,e),i.removeEventListener("slotchange",u),c.disconnect(),i.removeEventListener(E.REGISTER_MEDIA_STATE_RECEIVER,r),i.removeEventListener(E.UNREGISTER_MEDIA_STATE_RECEIVER,o)}};l.customElements.get("media-controller")||l.customElements.define("media-controller",Xi);var yn=Xi;var nt={PLACEMENT:"placement",BOUNDS:"bounds"};function Ra(i){return`
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
  `}var st=class extends l.HTMLElement{constructor(){super();this.updateXOffset=()=>{var C;if(!Ki(this,{checkOpacity:!1,checkVisibilityCSS:!1}))return;let e=this.placement;if(e==="left"||e==="right"){this.style.removeProperty("--media-tooltip-offset-x");return}let r=getComputedStyle(this),o=(C=ve(this,"#"+this.bounds))!=null?C:jo(this);if(!o)return;let{x:n,width:d}=o.getBoundingClientRect(),{x:u,width:c}=this.getBoundingClientRect(),h=u+c,A=n+d,M=r.getPropertyValue("--media-tooltip-offset-x"),T=M?parseFloat(M.replace("px","")):0,f=r.getPropertyValue("--media-tooltip-container-margin"),w=f?parseFloat(f.replace("px","")):0,k=u-n+T-w,x=h-A+T+w;if(k<0){this.style.setProperty("--media-tooltip-offset-x",`${k}px`);return}if(x>0){this.style.setProperty("--media-tooltip-offset-x",`${x}px`);return}this.style.removeProperty("--media-tooltip-offset-x")};if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=F(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}if(this.arrowEl=this.shadowRoot.querySelector("#arrow"),Object.prototype.hasOwnProperty.call(this,"placement")){let e=this.placement;delete this.placement,this.placement=e}}static get observedAttributes(){return[nt.PLACEMENT,nt.BOUNDS]}get placement(){return L(this,nt.PLACEMENT)}set placement(e){R(this,nt.PLACEMENT,e)}get bounds(){return L(this,nt.BOUNDS)}set bounds(e){R(this,nt.BOUNDS,e)}};st.shadowRootOptions={mode:"open"},st.getTemplateHTML=Ra;l.customElements.get("media-tooltip")||l.customElements.define("media-tooltip",st);var jt=st;var ye={TOOLTIP_PLACEMENT:"tooltipplacement",DISABLED:"disabled",NO_TOOLTIP:"notooltip"};function ka(i,t={}){return`
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

    ${this.getSlotTemplateHTML(i,t)}

    <slot name="tooltip">
      <media-tooltip part="tooltip" aria-hidden="true">
        <template shadowrootmode="${jt.shadowRootOptions.mode}">
          ${jt.getTemplateHTML({})}
        </template>
        <slot name="tooltip-content">
          ${this.getTooltipContentHTML(i)}
        </slot>
      </media-tooltip>
    </slot>
  `}function xa(i,t){return`
    <slot></slot>
  `}function Da(){return""}var te,Ne,pe,He,zt,Ji,Mn,P=class extends l.HTMLElement{constructor(){super();m(this,Ji);m(this,te,void 0);this.preventClick=!1;this.tooltipEl=null;m(this,Ne,e=>{this.preventClick||this.handleClick(e),setTimeout(a(this,pe),0)});m(this,pe,()=>{var e,r;(r=(e=this.tooltipEl)==null?void 0:e.updateXOffset)==null||r.call(e)});m(this,He,e=>{let{key:r}=e;if(!this.keysUsed.includes(r)){this.removeEventListener("keyup",a(this,He));return}this.preventClick||this.handleClick(e)});m(this,zt,e=>{let{metaKey:r,altKey:o,key:n}=e;if(r||o||!this.keysUsed.includes(n)){this.removeEventListener("keyup",a(this,He));return}this.addEventListener("keyup",a(this,He),{once:!0})});if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=F(this.attributes),r=this.constructor.getTemplateHTML(e);this.shadowRoot.setHTMLUnsafe?this.shadowRoot.setHTMLUnsafe(r):this.shadowRoot.innerHTML=r}this.tooltipEl=this.shadowRoot.querySelector("media-tooltip")}static get observedAttributes(){return["disabled",ye.TOOLTIP_PLACEMENT,_.MEDIA_CONTROLLER]}enable(){this.addEventListener("click",a(this,Ne)),this.addEventListener("keydown",a(this,zt)),this.tabIndex=0}disable(){this.removeEventListener("click",a(this,Ne)),this.removeEventListener("keydown",a(this,zt)),this.removeEventListener("keyup",a(this,He)),this.tabIndex=-1}attributeChangedCallback(e,r,o){var n,d,u,c,h;e===_.MEDIA_CONTROLLER?(r&&((d=(n=a(this,te))==null?void 0:n.unassociateElement)==null||d.call(n,this),p(this,te,null)),o&&this.isConnected&&(p(this,te,(u=this.getRootNode())==null?void 0:u.getElementById(o)),(h=(c=a(this,te))==null?void 0:c.associateElement)==null||h.call(c,this))):e==="disabled"&&o!==r?o==null?this.enable():this.disable():e===ye.TOOLTIP_PLACEMENT&&this.tooltipEl&&o!==r&&(this.tooltipEl.placement=o),a(this,pe).call(this)}connectedCallback(){var o,n,d;let{style:e}=U(this.shadowRoot,":host");e.setProperty("display",`var(--media-control-display, var(--${this.localName}-display, inline-flex))`),this.hasAttribute("disabled")?this.disable():this.enable(),this.setAttribute("role","button");let r=this.getAttribute(_.MEDIA_CONTROLLER);r&&(p(this,te,(o=this.getRootNode())==null?void 0:o.getElementById(r)),(d=(n=a(this,te))==null?void 0:n.associateElement)==null||d.call(n,this)),l.customElements.whenDefined("media-tooltip").then(()=>v(this,Ji,Mn).call(this))}disconnectedCallback(){var e,r;this.disable(),(r=(e=a(this,te))==null?void 0:e.unassociateElement)==null||r.call(e,this),p(this,te,null),this.removeEventListener("mouseenter",a(this,pe)),this.removeEventListener("focus",a(this,pe)),this.removeEventListener("click",a(this,Ne))}get keysUsed(){return["Enter"," "]}get tooltipPlacement(){return L(this,ye.TOOLTIP_PLACEMENT)}set tooltipPlacement(e){R(this,ye.TOOLTIP_PLACEMENT,e)}get mediaController(){return L(this,_.MEDIA_CONTROLLER)}set mediaController(e){R(this,_.MEDIA_CONTROLLER,e)}get disabled(){return S(this,ye.DISABLED)}set disabled(e){y(this,ye.DISABLED,e)}get noTooltip(){return S(this,ye.NO_TOOLTIP)}set noTooltip(e){y(this,ye.NO_TOOLTIP,e)}handleClick(e){}};te=new WeakMap,Ne=new WeakMap,pe=new WeakMap,He=new WeakMap,zt=new WeakMap,Ji=new WeakSet,Mn=function(){this.addEventListener("mouseenter",a(this,pe)),this.addEventListener("focus",a(this,pe)),this.addEventListener("click",a(this,Ne));let e=this.tooltipPlacement;e&&this.tooltipEl&&(this.tooltipEl.placement=e)},P.shadowRootOptions={mode:"open"},P.getTemplateHTML=ka,P.getSlotTemplateHTML=xa,P.getTooltipContentHTML=Da;l.customElements.get("media-chrome-button")||l.customElements.define("media-chrome-button",P);var _n=P;var Ln=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M22.13 3H3.87a.87.87 0 0 0-.87.87v13.26a.87.87 0 0 0 .87.87h3.4L9 16H5V5h16v11h-4l1.72 2h3.4a.87.87 0 0 0 .87-.87V3.87a.87.87 0 0 0-.86-.87Zm-8.75 11.44a.5.5 0 0 0-.76 0l-4.91 5.73a.5.5 0 0 0 .38.83h9.82a.501.501 0 0 0 .38-.83l-4.91-5.73Z"/>
</svg>
`;function wa(i){return`
    <style>
      :host([${s.MEDIA_IS_AIRPLAYING}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      
      :host(:not([${s.MEDIA_IS_AIRPLAYING}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${s.MEDIA_IS_AIRPLAYING}]) slot[name=tooltip-enter],
      :host(:not([${s.MEDIA_IS_AIRPLAYING}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${Ln}</slot>
      <slot name="exit">${Ln}</slot>
    </slot>
  `}function Ca(){return`
    <slot name="tooltip-enter">${b("start airplay")}</slot>
    <slot name="tooltip-exit">${b("stop airplay")}</slot>
  `}var Rn=i=>{let t=i.mediaIsAirplaying?b("stop airplay"):b("start airplay");i.setAttribute("aria-label",t)},at=class extends P{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_IS_AIRPLAYING,s.MEDIA_AIRPLAY_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),Rn(this)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),t===s.MEDIA_IS_AIRPLAYING&&Rn(this)}get mediaIsAirplaying(){return S(this,s.MEDIA_IS_AIRPLAYING)}set mediaIsAirplaying(t){y(this,s.MEDIA_IS_AIRPLAYING,t)}get mediaAirplayUnavailable(){return L(this,s.MEDIA_AIRPLAY_UNAVAILABLE)}set mediaAirplayUnavailable(t){R(this,s.MEDIA_AIRPLAY_UNAVAILABLE,t)}handleClick(){let t=new l.CustomEvent(E.MEDIA_AIRPLAY_REQUEST,{composed:!0,bubbles:!0});this.dispatchEvent(t)}};at.getSlotTemplateHTML=wa,at.getTooltipContentHTML=Ca;l.customElements.get("media-airplay-button")||l.customElements.define("media-airplay-button",at);var kn=at;var Pa=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M22.83 5.68a2.58 2.58 0 0 0-2.3-2.5c-3.62-.24-11.44-.24-15.06 0a2.58 2.58 0 0 0-2.3 2.5c-.23 4.21-.23 8.43 0 12.64a2.58 2.58 0 0 0 2.3 2.5c3.62.24 11.44.24 15.06 0a2.58 2.58 0 0 0 2.3-2.5c.23-4.21.23-8.43 0-12.64Zm-11.39 9.45a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.92 3.92 0 0 1 .92-2.77 3.18 3.18 0 0 1 2.43-1 2.94 2.94 0 0 1 2.13.78c.364.359.62.813.74 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.17 1.61 1.61 0 0 0-1.29.58 2.79 2.79 0 0 0-.5 1.89 3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.48 1.48 0 0 0 1-.37 2.1 2.1 0 0 0 .59-1.14l1.4.44a3.23 3.23 0 0 1-1.07 1.69Zm7.22 0a3.07 3.07 0 0 1-1.91.57 3.06 3.06 0 0 1-2.34-1 3.75 3.75 0 0 1-.92-2.67 3.88 3.88 0 0 1 .93-2.77 3.14 3.14 0 0 1 2.42-1 3 3 0 0 1 2.16.82 2.8 2.8 0 0 1 .73 1.31l-1.43.35a1.49 1.49 0 0 0-1.51-1.21 1.61 1.61 0 0 0-1.29.58A2.79 2.79 0 0 0 15 12a3 3 0 0 0 .49 1.93 1.61 1.61 0 0 0 1.27.58 1.44 1.44 0 0 0 1-.37 2.1 2.1 0 0 0 .6-1.15l1.4.44a3.17 3.17 0 0 1-1.1 1.7Z"/>
</svg>`,Ua=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M17.73 14.09a1.4 1.4 0 0 1-1 .37 1.579 1.579 0 0 1-1.27-.58A3 3 0 0 1 15 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34A2.89 2.89 0 0 0 19 9.07a3 3 0 0 0-2.14-.78 3.14 3.14 0 0 0-2.42 1 3.91 3.91 0 0 0-.93 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.17 3.17 0 0 0 1.07-1.74l-1.4-.45c-.083.43-.3.822-.62 1.12Zm-7.22 0a1.43 1.43 0 0 1-1 .37 1.58 1.58 0 0 1-1.27-.58A3 3 0 0 1 7.76 12a2.8 2.8 0 0 1 .5-1.85 1.63 1.63 0 0 1 1.29-.57 1.47 1.47 0 0 1 1.51 1.2l1.43-.34a2.81 2.81 0 0 0-.74-1.32 2.94 2.94 0 0 0-2.13-.78 3.18 3.18 0 0 0-2.43 1 4 4 0 0 0-.92 2.78 3.74 3.74 0 0 0 .92 2.66 3.07 3.07 0 0 0 2.34 1 3.07 3.07 0 0 0 1.91-.57 3.23 3.23 0 0 0 1.07-1.74l-1.4-.45a2.06 2.06 0 0 1-.6 1.07Zm12.32-8.41a2.59 2.59 0 0 0-2.3-2.51C18.72 3.05 15.86 3 13 3c-2.86 0-5.72.05-7.53.17a2.59 2.59 0 0 0-2.3 2.51c-.23 4.207-.23 8.423 0 12.63a2.57 2.57 0 0 0 2.3 2.5c1.81.13 4.67.19 7.53.19 2.86 0 5.72-.06 7.53-.19a2.57 2.57 0 0 0 2.3-2.5c.23-4.207.23-8.423 0-12.63Zm-1.49 12.53a1.11 1.11 0 0 1-.91 1.11c-1.67.11-4.45.18-7.43.18-2.98 0-5.76-.07-7.43-.18a1.11 1.11 0 0 1-.91-1.11c-.21-4.14-.21-8.29 0-12.43a1.11 1.11 0 0 1 .91-1.11C7.24 4.56 10 4.49 13 4.49s5.76.07 7.43.18a1.11 1.11 0 0 1 .91 1.11c.21 4.14.21 8.29 0 12.43Z"/>
</svg>`;function Oa(i){return`
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
      <slot name="on">${Pa}</slot>
      <slot name="off">${Ua}</slot>
    </slot>
  `}function Na(){return`
    <slot name="tooltip-enable">${b("Enable captions")}</slot>
    <slot name="tooltip-disable">${b("Disable captions")}</slot>
  `}var xn=i=>{i.setAttribute("aria-checked",nn(i).toString())},dt=class extends P{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_SUBTITLES_LIST,s.MEDIA_SUBTITLES_SHOWING]}connectedCallback(){super.connectedCallback(),this.setAttribute("role","switch"),this.setAttribute("aria-label",b("closed captions")),xn(this)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),t===s.MEDIA_SUBTITLES_SHOWING&&xn(this)}get mediaSubtitlesList(){return Dn(this,s.MEDIA_SUBTITLES_LIST)}set mediaSubtitlesList(t){wn(this,s.MEDIA_SUBTITLES_LIST,t)}get mediaSubtitlesShowing(){return Dn(this,s.MEDIA_SUBTITLES_SHOWING)}set mediaSubtitlesShowing(t){wn(this,s.MEDIA_SUBTITLES_SHOWING,t)}handleClick(){this.dispatchEvent(new l.CustomEvent(E.MEDIA_TOGGLE_SUBTITLES_REQUEST,{composed:!0,bubbles:!0}))}};dt.getSlotTemplateHTML=Oa,dt.getTooltipContentHTML=Na;var Dn=(i,t)=>{let e=i.getAttribute(t);return e?Cr(e):[]},wn=(i,t,e)=>{if(!(e!=null&&e.length)){i.removeAttribute(t);return}let r=Kt(e);i.getAttribute(t)!==r&&i.setAttribute(t,r)};l.customElements.get("media-captions-button")||l.customElements.define("media-captions-button",dt);var Cn=dt;var Ha='<svg aria-hidden="true" viewBox="0 0 24 24"><g><path class="cast_caf_icon_arch0" d="M1,18 L1,21 L4,21 C4,19.3 2.66,18 1,18 L1,18 Z"/><path class="cast_caf_icon_arch1" d="M1,14 L1,16 C3.76,16 6,18.2 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z"/><path class="cast_caf_icon_arch2" d="M1,10 L1,12 C5.97,12 10,16.0 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z"/><path class="cast_caf_icon_box" d="M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"/></g></svg>',Fa='<svg aria-hidden="true" viewBox="0 0 24 24"><g><path class="cast_caf_icon_arch0" d="M1,18 L1,21 L4,21 C4,19.3 2.66,18 1,18 L1,18 Z"/><path class="cast_caf_icon_arch1" d="M1,14 L1,16 C3.76,16 6,18.2 6,21 L8,21 C8,17.13 4.87,14 1,14 L1,14 Z"/><path class="cast_caf_icon_arch2" d="M1,10 L1,12 C5.97,12 10,16.0 10,21 L12,21 C12,14.92 7.07,10 1,10 L1,10 Z"/><path class="cast_caf_icon_box" d="M21,3 L3,3 C1.9,3 1,3.9 1,5 L1,8 L3,8 L3,5 L21,5 L21,19 L14,19 L14,21 L21,21 C22.1,21 23,20.1 23,19 L23,5 C23,3.9 22.1,3 21,3 L21,3 Z"/><path class="cast_caf_icon_boxfill" d="M5,7 L5,8.63 C8,8.6 13.37,14 13.37,17 L19,17 L19,7 Z"/></g></svg>';function $a(i){return`
    <style>
      :host([${s.MEDIA_IS_CASTING}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      
      :host(:not([${s.MEDIA_IS_CASTING}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${s.MEDIA_IS_CASTING}]) slot[name=tooltip-enter],
      :host(:not([${s.MEDIA_IS_CASTING}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${Ha}</slot>
      <slot name="exit">${Fa}</slot>
    </slot>
  `}function Ba(){return`
    <slot name="tooltip-enter">${b("Start casting")}</slot>
    <slot name="tooltip-exit">${b("Stop casting")}</slot>
  `}var Pn=i=>{let t=i.mediaIsCasting?b("stop casting"):b("start casting");i.setAttribute("aria-label",t)},lt=class extends P{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_IS_CASTING,s.MEDIA_CAST_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),Pn(this)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),t===s.MEDIA_IS_CASTING&&Pn(this)}get mediaIsCasting(){return S(this,s.MEDIA_IS_CASTING)}set mediaIsCasting(t){y(this,s.MEDIA_IS_CASTING,t)}get mediaCastUnavailable(){return L(this,s.MEDIA_CAST_UNAVAILABLE)}set mediaCastUnavailable(t){R(this,s.MEDIA_CAST_UNAVAILABLE,t)}handleClick(){let t=this.mediaIsCasting?E.MEDIA_EXIT_CAST_REQUEST:E.MEDIA_ENTER_CAST_REQUEST;this.dispatchEvent(new l.CustomEvent(t,{composed:!0,bubbles:!0}))}};lt.getSlotTemplateHTML=$a,lt.getTooltipContentHTML=Ba;l.customElements.get("media-cast-button")||l.customElements.define("media-cast-button",lt);var Un=lt;function Va(i){return`
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
    ${this.getSlotTemplateHTML(i)}
  `}function Ka(i){return`
    <slot id="content"></slot>
  `}var Zt={OPEN:"open",ANCHOR:"anchor"},Xt,ut,Me,Jt,Kr,er,On,tr,Nn,ir,Hn,rr,Fn,or,$n,he=class extends l.HTMLElement{constructor(){super();m(this,Jt);m(this,er);m(this,tr);m(this,ir);m(this,rr);m(this,or);m(this,Xt,!1);m(this,ut,null);m(this,Me,null);this.addEventListener("invoke",this),this.addEventListener("focusout",this),this.addEventListener("keydown",this)}static get observedAttributes(){return[Zt.OPEN,Zt.ANCHOR]}get open(){return S(this,Zt.OPEN)}set open(e){y(this,Zt.OPEN,e)}handleEvent(e){switch(e.type){case"invoke":v(this,ir,Hn).call(this,e);break;case"focusout":v(this,rr,Fn).call(this,e);break;case"keydown":v(this,or,$n).call(this,e);break}}connectedCallback(){v(this,Jt,Kr).call(this),this.role||(this.role="dialog")}attributeChangedCallback(e,r,o){v(this,Jt,Kr).call(this),e===Zt.OPEN&&o!==r&&(this.open?v(this,er,On).call(this):v(this,tr,Nn).call(this))}focus(){p(this,ut,Dr());let e=!this.dispatchEvent(new Event("focus",{composed:!0,cancelable:!0})),r=!this.dispatchEvent(new Event("focusin",{composed:!0,bubbles:!0,cancelable:!0}));if(e||r)return;let o=this.querySelector('[autofocus], [tabindex]:not([tabindex="-1"]), [role="menu"]');o==null||o.focus()}get keysUsed(){return["Escape","Tab"]}};Xt=new WeakMap,ut=new WeakMap,Me=new WeakMap,Jt=new WeakSet,Kr=function(){if(!a(this,Xt)&&(p(this,Xt,!0),!this.shadowRoot)){this.attachShadow(this.constructor.shadowRootOptions);let e=F(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e),queueMicrotask(()=>{let{style:r}=U(this.shadowRoot,":host");r.setProperty("transition","display .15s, visibility .15s, opacity .15s ease-in, transform .15s ease-in")})}},er=new WeakSet,On=function(){var e;(e=a(this,Me))==null||e.setAttribute("aria-expanded","true"),this.dispatchEvent(new Event("open",{composed:!0,bubbles:!0})),this.addEventListener("transitionend",()=>this.focus(),{once:!0})},tr=new WeakSet,Nn=function(){var e;(e=a(this,Me))==null||e.setAttribute("aria-expanded","false"),this.dispatchEvent(new Event("close",{composed:!0,bubbles:!0}))},ir=new WeakSet,Hn=function(e){p(this,Me,e.relatedTarget),de(this,e.relatedTarget)||(this.open=!this.open)},rr=new WeakSet,Fn=function(e){var r;de(this,e.relatedTarget)||((r=a(this,ut))==null||r.focus(),a(this,Me)&&a(this,Me)!==e.relatedTarget&&this.open&&(this.open=!1))},or=new WeakSet,$n=function(e){var u,c,h,A,M;let{key:r,ctrlKey:o,altKey:n,metaKey:d}=e;o||n||d||this.keysUsed.includes(r)&&(e.preventDefault(),e.stopPropagation(),r==="Tab"?(e.shiftKey?(c=(u=this.previousElementSibling)==null?void 0:u.focus)==null||c.call(u):(A=(h=this.nextElementSibling)==null?void 0:h.focus)==null||A.call(h),this.blur()):r==="Escape"&&((M=a(this,ut))==null||M.focus(),this.open=!1))},he.shadowRootOptions={mode:"open"},he.getTemplateHTML=Va,he.getSlotTemplateHTML=Ka;l.customElements.get("media-chrome-dialog")||l.customElements.define("media-chrome-dialog",he);var Bn=he;function Ga(i){return`
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
  `}var ie,ei,ti,ii,X,ri,oi,ni,si,nr,Vn,ai,Gr,di,Wr,li,qr,sr,Kn,ar,Gn,dr,Wn,lr,qn,re=class extends l.HTMLElement{constructor(){super();m(this,nr);m(this,ai);m(this,di);m(this,li);m(this,sr);m(this,ar);m(this,dr);m(this,lr);m(this,ie,void 0);m(this,ei,void 0);m(this,ti,void 0);m(this,ii,void 0);m(this,X,{});m(this,ri,[]);m(this,oi,()=>{if(this.range.matches(":focus-visible")){let{style:e}=U(this.shadowRoot,":host");e.setProperty("--_focus-visible-box-shadow","var(--_focus-box-shadow)")}});m(this,ni,()=>{let{style:e}=U(this.shadowRoot,":host");e.removeProperty("--_focus-visible-box-shadow")});m(this,si,()=>{let e=this.shadowRoot.querySelector("#segments-clipping");e&&e.parentNode.append(e)});if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=F(this.attributes),r=this.constructor.getTemplateHTML(e);this.shadowRoot.setHTMLUnsafe?this.shadowRoot.setHTMLUnsafe(r):this.shadowRoot.innerHTML=r}this.container=this.shadowRoot.querySelector("#container"),p(this,ti,this.shadowRoot.querySelector("#startpoint")),p(this,ii,this.shadowRoot.querySelector("#endpoint")),this.range=this.shadowRoot.querySelector("#range"),this.appearance=this.shadowRoot.querySelector("#appearance")}static get observedAttributes(){return["disabled","aria-disabled",_.MEDIA_CONTROLLER]}attributeChangedCallback(e,r,o){var n,d,u,c,h;e===_.MEDIA_CONTROLLER?(r&&((d=(n=a(this,ie))==null?void 0:n.unassociateElement)==null||d.call(n,this),p(this,ie,null)),o&&this.isConnected&&(p(this,ie,(u=this.getRootNode())==null?void 0:u.getElementById(o)),(h=(c=a(this,ie))==null?void 0:c.associateElement)==null||h.call(c,this))):(e==="disabled"||e==="aria-disabled"&&r!==o)&&(o==null?(this.range.removeAttribute(e),v(this,ai,Gr).call(this)):(this.range.setAttribute(e,o),v(this,di,Wr).call(this)))}connectedCallback(){var o,n,d;let{style:e}=U(this.shadowRoot,":host");e.setProperty("display",`var(--media-control-display, var(--${this.localName}-display, inline-flex))`),a(this,X).pointer=U(this.shadowRoot,"#pointer"),a(this,X).progress=U(this.shadowRoot,"#progress"),a(this,X).thumb=U(this.shadowRoot,'#thumb, ::slotted([slot="thumb"])'),a(this,X).activeSegment=U(this.shadowRoot,"#segments-clipping rect:nth-child(0)");let r=this.getAttribute(_.MEDIA_CONTROLLER);r&&(p(this,ie,(o=this.getRootNode())==null?void 0:o.getElementById(r)),(d=(n=a(this,ie))==null?void 0:n.associateElement)==null||d.call(n,this)),this.updateBar(),this.shadowRoot.addEventListener("focusin",a(this,oi)),this.shadowRoot.addEventListener("focusout",a(this,ni)),v(this,ai,Gr).call(this),Fi(this.container,a(this,si))}disconnectedCallback(){var e,r;v(this,di,Wr).call(this),(r=(e=a(this,ie))==null?void 0:e.unassociateElement)==null||r.call(e,this),p(this,ie,null),this.shadowRoot.removeEventListener("focusin",a(this,oi)),this.shadowRoot.removeEventListener("focusout",a(this,ni)),$i(this.container,a(this,si))}updatePointerBar(e){var r;(r=a(this,X).pointer)==null||r.style.setProperty("width",`${this.getPointerRatio(e)*100}%`)}updateBar(){var r,o;let e=this.range.valueAsNumber*100;(r=a(this,X).progress)==null||r.style.setProperty("width",`${e}%`),(o=a(this,X).thumb)==null||o.style.setProperty("left",`${e}%`)}updateSegments(e){let r=this.shadowRoot.querySelector("#segments-clipping");if(r.textContent="",this.container.classList.toggle("segments",!!(e!=null&&e.length)),!(e!=null&&e.length))return;let o=[...new Set([+this.range.min,...e.flatMap(d=>[d.start,d.end]),+this.range.max])];p(this,ri,[...o]);let n=o.pop();for(let[d,u]of o.entries()){let[c,h]=[d===0,d===o.length-1],A=c?"calc(var(--segments-gap) / -1)":`${u*100}%`,T=`calc(${((h?n:o[d+1])-u)*100}%${c||h?"":" - var(--segments-gap)"})`,f=B.createElementNS("http://www.w3.org/2000/svg","rect"),w=U(this.shadowRoot,`#segments-clipping rect:nth-child(${d+1})`);w.style.setProperty("x",A),w.style.setProperty("width",T),r.append(f)}}getPointerRatio(e){return zo(e.clientX,e.clientY,a(this,ti).getBoundingClientRect(),a(this,ii).getBoundingClientRect())}get dragging(){return this.hasAttribute("dragging")}handleEvent(e){switch(e.type){case"pointermove":v(this,lr,qn).call(this,e);break;case"input":this.updateBar();break;case"pointerenter":v(this,sr,Kn).call(this,e);break;case"pointerdown":v(this,li,qr).call(this,e);break;case"pointerup":v(this,ar,Gn).call(this);break;case"pointerleave":v(this,dr,Wn).call(this);break}}get keysUsed(){return["ArrowUp","ArrowRight","ArrowDown","ArrowLeft"]}};ie=new WeakMap,ei=new WeakMap,ti=new WeakMap,ii=new WeakMap,X=new WeakMap,ri=new WeakMap,oi=new WeakMap,ni=new WeakMap,si=new WeakMap,nr=new WeakSet,Vn=function(e){let r=a(this,X).activeSegment;if(!r)return;let o=this.getPointerRatio(e),d=`#segments-clipping rect:nth-child(${a(this,ri).findIndex((u,c,h)=>{let A=h[c+1];return A!=null&&o>=u&&o<=A})+1})`;(r.selectorText!=d||!r.style.transform)&&(r.selectorText=d,r.style.setProperty("transform","var(--media-range-segment-hover-transform, scaleY(2))"))},ai=new WeakSet,Gr=function(){this.hasAttribute("disabled")||(this.addEventListener("input",this),this.addEventListener("pointerdown",this),this.addEventListener("pointerenter",this))},di=new WeakSet,Wr=function(){var e,r;this.removeEventListener("input",this),this.removeEventListener("pointerdown",this),this.removeEventListener("pointerenter",this),(e=l.window)==null||e.removeEventListener("pointerup",this),(r=l.window)==null||r.removeEventListener("pointermove",this)},li=new WeakSet,qr=function(e){var r;p(this,ei,e.composedPath().includes(this.range)),(r=l.window)==null||r.addEventListener("pointerup",this)},sr=new WeakSet,Kn=function(e){var r;e.pointerType!=="mouse"&&v(this,li,qr).call(this,e),this.addEventListener("pointerleave",this),(r=l.window)==null||r.addEventListener("pointermove",this)},ar=new WeakSet,Gn=function(){var e;(e=l.window)==null||e.removeEventListener("pointerup",this),this.toggleAttribute("dragging",!1),this.range.disabled=this.hasAttribute("disabled")},dr=new WeakSet,Wn=function(){var e,r;this.removeEventListener("pointerleave",this),(e=l.window)==null||e.removeEventListener("pointermove",this),this.toggleAttribute("dragging",!1),this.range.disabled=this.hasAttribute("disabled"),(r=a(this,X).activeSegment)==null||r.style.removeProperty("transform")},lr=new WeakSet,qn=function(e){this.toggleAttribute("dragging",e.buttons===1||e.pointerType!=="mouse"),this.updatePointerBar(e),v(this,nr,Vn).call(this,e),this.dragging&&(e.pointerType!=="mouse"||!a(this,ei))&&(this.range.disabled=!0,this.range.valueAsNumber=this.getPointerRatio(e),this.range.dispatchEvent(new Event("input",{bubbles:!0,composed:!0})))},re.shadowRootOptions={mode:"open"},re.getTemplateHTML=Ga;l.customElements.get("media-chrome-range")||l.customElements.define("media-chrome-range",re);var Yn=re;function Wa(i){return`
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
  `}var oe,ct=class extends l.HTMLElement{constructor(){super();m(this,oe,void 0);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=F(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER]}attributeChangedCallback(e,r,o){var n,d,u,c,h;e===_.MEDIA_CONTROLLER&&(r&&((d=(n=a(this,oe))==null?void 0:n.unassociateElement)==null||d.call(n,this),p(this,oe,null)),o&&this.isConnected&&(p(this,oe,(u=this.getRootNode())==null?void 0:u.getElementById(o)),(h=(c=a(this,oe))==null?void 0:c.associateElement)==null||h.call(c,this)))}connectedCallback(){var r,o,n;let e=this.getAttribute(_.MEDIA_CONTROLLER);e&&(p(this,oe,(r=this.getRootNode())==null?void 0:r.getElementById(e)),(n=(o=a(this,oe))==null?void 0:o.associateElement)==null||n.call(o,this))}disconnectedCallback(){var e,r;(r=(e=a(this,oe))==null?void 0:e.unassociateElement)==null||r.call(e,this),p(this,oe,null)}};oe=new WeakMap,ct.shadowRootOptions={mode:"open"},ct.getTemplateHTML=Wa;l.customElements.get("media-control-bar")||l.customElements.define("media-control-bar",ct);var Qn=ct;function qa(i,t={}){return`
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

    ${this.getSlotTemplateHTML(i,t)}
  `}function Ya(i,t){return`
    <slot></slot>
  `}var ne,q=class extends l.HTMLElement{constructor(){super();m(this,ne,void 0);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=F(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER]}attributeChangedCallback(e,r,o){var n,d,u,c,h;e===_.MEDIA_CONTROLLER&&(r&&((d=(n=a(this,ne))==null?void 0:n.unassociateElement)==null||d.call(n,this),p(this,ne,null)),o&&this.isConnected&&(p(this,ne,(u=this.getRootNode())==null?void 0:u.getElementById(o)),(h=(c=a(this,ne))==null?void 0:c.associateElement)==null||h.call(c,this)))}connectedCallback(){var o,n,d;let{style:e}=U(this.shadowRoot,":host");e.setProperty("display",`var(--media-control-display, var(--${this.localName}-display, inline-flex))`);let r=this.getAttribute(_.MEDIA_CONTROLLER);r&&(p(this,ne,(o=this.getRootNode())==null?void 0:o.getElementById(r)),(d=(n=a(this,ne))==null?void 0:n.associateElement)==null||d.call(n,this))}disconnectedCallback(){var e,r;(r=(e=a(this,ne))==null?void 0:e.unassociateElement)==null||r.call(e,this),p(this,ne,null)}};ne=new WeakMap,q.shadowRootOptions={mode:"open"},q.getTemplateHTML=qa,q.getSlotTemplateHTML=Ya;l.customElements.get("media-text-display")||l.customElements.define("media-text-display",q);var jn=q;function Qa(i,t){return`
    <slot>${Z(t.mediaDuration)}</slot>
  `}var mt,ui=class extends q{constructor(){var e;super();m(this,mt,void 0);p(this,mt,this.shadowRoot.querySelector("slot")),a(this,mt).textContent=Z((e=this.mediaDuration)!=null?e:0)}static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_DURATION]}attributeChangedCallback(e,r,o){e===s.MEDIA_DURATION&&(a(this,mt).textContent=Z(+o)),super.attributeChangedCallback(e,r,o)}get mediaDuration(){return D(this,s.MEDIA_DURATION)}set mediaDuration(e){O(this,s.MEDIA_DURATION,e)}};mt=new WeakMap,ui.getSlotTemplateHTML=Qa;l.customElements.get("media-duration-display")||l.customElements.define("media-duration-display",ui);var zn=ui;var ja={2:b("Network Error"),3:b("Decode Error"),4:b("Source Not Supported"),5:b("Encryption Error")},za={2:b("A network error caused the media download to fail."),3:b("A media error caused playback to be aborted. The media could be corrupt or your browser does not support this format."),4:b("An unsupported error occurred. The server or network failed, or your browser does not support this format."),5:b("The media is encrypted and there are no keys to decrypt it.")},Yr=i=>{var t,e;return i.code===1?null:{title:(t=ja[i.code])!=null?t:`Error ${i.code}`,message:(e=za[i.code])!=null?e:i.message}};function Za(i){return`
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
    <slot name="error-${i.mediaerrorcode}" id="content">
      ${Xn({code:+i.mediaerrorcode,message:i.mediaerrormessage})}
    </slot>
  `}function Xa(i){return i.code&&Yr(i)!==null}function Xn(i){var o;let{title:t,message:e}=(o=Yr(i))!=null?o:{},r="";return t&&(r+=`<slot name="error-${i.code}-title"><h3>${t}</h3></slot>`),e&&(r+=`<slot name="error-${i.code}-message"><p>${e}</p></slot>`),r}var Zn=[s.MEDIA_ERROR_CODE,s.MEDIA_ERROR_MESSAGE],ci,pt=class extends he{constructor(){super(...arguments);m(this,ci,null)}static get observedAttributes(){return[...super.observedAttributes,...Zn]}formatErrorMessage(e){return this.constructor.formatErrorMessage(e)}attributeChangedCallback(e,r,o){var d;if(super.attributeChangedCallback(e,r,o),!Zn.includes(e))return;let n=(d=this.mediaError)!=null?d:{code:this.mediaErrorCode,message:this.mediaErrorMessage};this.open=Xa(n),this.open&&(this.shadowRoot.querySelector("slot").name=`error-${this.mediaErrorCode}`,this.shadowRoot.querySelector("#content").innerHTML=this.formatErrorMessage(n))}get mediaError(){return a(this,ci)}set mediaError(e){p(this,ci,e)}get mediaErrorCode(){return D(this,"mediaerrorcode")}set mediaErrorCode(e){O(this,"mediaerrorcode",e)}get mediaErrorMessage(){return L(this,"mediaerrormessage")}set mediaErrorMessage(e){R(this,"mediaerrormessage",e)}};ci=new WeakMap,pt.getSlotTemplateHTML=Za,pt.formatErrorMessage=Xn;l.customElements.get("media-error-dialog")||l.customElements.define("media-error-dialog",pt);var Jn=pt;var Ja=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M16 3v2.5h3.5V9H22V3h-6ZM4 9h2.5V5.5H10V3H4v6Zm15.5 9.5H16V21h6v-6h-2.5v3.5ZM6.5 15H4v6h6v-2.5H6.5V15Z"/>
</svg>`,ed=`<svg aria-hidden="true" viewBox="0 0 26 24">
  <path d="M18.5 6.5V3H16v6h6V6.5h-3.5ZM16 21h2.5v-3.5H22V15h-6v6ZM4 17.5h3.5V21H10v-6H4v2.5Zm3.5-11H4V9h6V3H7.5v3.5Z"/>
</svg>`;function td(i){return`
    <style>
      :host([${s.MEDIA_IS_FULLSCREEN}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      
      :host(:not([${s.MEDIA_IS_FULLSCREEN}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${s.MEDIA_IS_FULLSCREEN}]) slot[name=tooltip-enter],
      :host(:not([${s.MEDIA_IS_FULLSCREEN}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${Ja}</slot>
      <slot name="exit">${ed}</slot>
    </slot>
  `}function id(){return`
    <slot name="tooltip-enter">${b("Enter fullscreen mode")}</slot>
    <slot name="tooltip-exit">${b("Exit fullscreen mode")}</slot>
  `}var es=i=>{let t=i.mediaIsFullscreen?b("exit fullscreen mode"):b("enter fullscreen mode");i.setAttribute("aria-label",t)},ht=class extends P{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_IS_FULLSCREEN,s.MEDIA_FULLSCREEN_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),es(this)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),t===s.MEDIA_IS_FULLSCREEN&&es(this)}get mediaFullscreenUnavailable(){return L(this,s.MEDIA_FULLSCREEN_UNAVAILABLE)}set mediaFullscreenUnavailable(t){R(this,s.MEDIA_FULLSCREEN_UNAVAILABLE,t)}get mediaIsFullscreen(){return S(this,s.MEDIA_IS_FULLSCREEN)}set mediaIsFullscreen(t){y(this,s.MEDIA_IS_FULLSCREEN,t)}handleClick(){let t=this.mediaIsFullscreen?E.MEDIA_EXIT_FULLSCREEN_REQUEST:E.MEDIA_ENTER_FULLSCREEN_REQUEST;this.dispatchEvent(new l.CustomEvent(t,{composed:!0,bubbles:!0}))}};ht.getSlotTemplateHTML=td,ht.getTooltipContentHTML=id;l.customElements.get("media-fullscreen-button")||l.customElements.define("media-fullscreen-button",ht);var ts=ht;var{MEDIA_TIME_IS_LIVE:ur,MEDIA_PAUSED:mi}=s,{MEDIA_SEEK_TO_LIVE_REQUEST:rd,MEDIA_PLAY_REQUEST:od}=E,nd='<svg viewBox="0 0 6 12"><circle cx="3" cy="6" r="2"></circle></svg>';function sd(i){return`
    <style>
      :host { --media-tooltip-display: none; }
      
      slot[name=indicator] > *,
      :host ::slotted([slot=indicator]) {
        
        min-width: auto;
        fill: var(--media-live-button-icon-color, rgb(140, 140, 140));
        color: var(--media-live-button-icon-color, rgb(140, 140, 140));
      }

      :host([${ur}]:not([${mi}])) slot[name=indicator] > *,
      :host([${ur}]:not([${mi}])) ::slotted([slot=indicator]) {
        fill: var(--media-live-button-indicator-color, rgb(255, 0, 0));
        color: var(--media-live-button-indicator-color, rgb(255, 0, 0));
      }

      :host([${ur}]:not([${mi}])) {
        cursor: var(--media-cursor, not-allowed);
      }

      slot[name=text]{
        text-transform: uppercase;
      }

    </style>

    <slot name="indicator">${nd}</slot>
    
    <slot name="spacer">&nbsp;</slot><slot name="text">${b("live")}</slot>
  `}var is=i=>{let t=i.mediaPaused||!i.mediaTimeIsLive,e=t?b("seek to live"):b("playing live");i.setAttribute("aria-label",e),t?i.removeAttribute("aria-disabled"):i.setAttribute("aria-disabled","true")},pi=class extends P{static get observedAttributes(){return[...super.observedAttributes,ur,mi]}connectedCallback(){super.connectedCallback(),is(this)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),is(this)}get mediaPaused(){return S(this,s.MEDIA_PAUSED)}set mediaPaused(t){y(this,s.MEDIA_PAUSED,t)}get mediaTimeIsLive(){return S(this,s.MEDIA_TIME_IS_LIVE)}set mediaTimeIsLive(t){y(this,s.MEDIA_TIME_IS_LIVE,t)}handleClick(){!this.mediaPaused&&this.mediaTimeIsLive||(this.dispatchEvent(new l.CustomEvent(rd,{composed:!0,bubbles:!0})),this.hasAttribute(mi)&&this.dispatchEvent(new l.CustomEvent(od,{composed:!0,bubbles:!0})))}};pi.getSlotTemplateHTML=sd;l.customElements.get("media-live-button")||l.customElements.define("media-live-button",pi);var rs=pi;var cr={LOADING_DELAY:"loadingdelay",NO_AUTOHIDE:"noautohide"},os=500,ad=`
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
`;function dd(i){return`
    <style>
      :host {
        display: var(--media-control-display, var(--media-loading-indicator-display, inline-block));
        vertical-align: middle;
        box-sizing: border-box;
        --_loading-indicator-delay: var(--media-loading-indicator-transition-delay, ${os}ms);
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

      :host([${s.MEDIA_LOADING}]:not([${s.MEDIA_PAUSED}])) slot[name=icon] > *,
      :host([${s.MEDIA_LOADING}]:not([${s.MEDIA_PAUSED}])) ::slotted([slot=icon]) {
        opacity: var(--media-loading-indicator-opacity, 1);
        transition: opacity 0.15s var(--_loading-indicator-delay);
      }

      :host #status {
        visibility: var(--media-loading-indicator-opacity, hidden);
        transition: visibility 0.15s;
      }

      :host([${s.MEDIA_LOADING}]:not([${s.MEDIA_PAUSED}])) #status {
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

    <slot name="icon">${ad}</slot>
    <div id="status" role="status" aria-live="polite">${b("media loading")}</div>
  `}var se,hi,Et=class extends l.HTMLElement{constructor(){super();m(this,se,void 0);m(this,hi,os);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=F(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER,s.MEDIA_PAUSED,s.MEDIA_LOADING,cr.LOADING_DELAY]}attributeChangedCallback(e,r,o){var n,d,u,c,h;e===cr.LOADING_DELAY&&r!==o?this.loadingDelay=Number(o):e===_.MEDIA_CONTROLLER&&(r&&((d=(n=a(this,se))==null?void 0:n.unassociateElement)==null||d.call(n,this),p(this,se,null)),o&&this.isConnected&&(p(this,se,(u=this.getRootNode())==null?void 0:u.getElementById(o)),(h=(c=a(this,se))==null?void 0:c.associateElement)==null||h.call(c,this)))}connectedCallback(){var r,o,n;let e=this.getAttribute(_.MEDIA_CONTROLLER);e&&(p(this,se,(r=this.getRootNode())==null?void 0:r.getElementById(e)),(n=(o=a(this,se))==null?void 0:o.associateElement)==null||n.call(o,this))}disconnectedCallback(){var e,r;(r=(e=a(this,se))==null?void 0:e.unassociateElement)==null||r.call(e,this),p(this,se,null)}get loadingDelay(){return a(this,hi)}set loadingDelay(e){p(this,hi,e);let{style:r}=U(this.shadowRoot,":host");r.setProperty("--_loading-indicator-delay",`var(--media-loading-indicator-transition-delay, ${e}ms)`)}get mediaPaused(){return S(this,s.MEDIA_PAUSED)}set mediaPaused(e){y(this,s.MEDIA_PAUSED,e)}get mediaLoading(){return S(this,s.MEDIA_LOADING)}set mediaLoading(e){y(this,s.MEDIA_LOADING,e)}get mediaController(){return L(this,_.MEDIA_CONTROLLER)}set mediaController(e){R(this,_.MEDIA_CONTROLLER,e)}get noAutohide(){return S(this,cr.NO_AUTOHIDE)}set noAutohide(e){y(this,cr.NO_AUTOHIDE,e)}};se=new WeakMap,hi=new WeakMap,Et.shadowRootOptions={mode:"open"},Et.getTemplateHTML=dd;l.customElements.get("media-loading-indicator")||l.customElements.define("media-loading-indicator",Et);var ns=Et;var ld=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M16.5 12A4.5 4.5 0 0 0 14 8v2.18l2.45 2.45a4.22 4.22 0 0 0 .05-.63Zm2.5 0a6.84 6.84 0 0 1-.54 2.64L20 16.15A8.8 8.8 0 0 0 21 12a9 9 0 0 0-7-8.77v2.06A7 7 0 0 1 19 12ZM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25A6.92 6.92 0 0 1 14 18.7v2.06A9 9 0 0 0 17.69 19l2 2.05L21 19.73l-9-9L4.27 3ZM12 4 9.91 6.09 12 8.18V4Z"/>
</svg>`,ss=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M3 9v6h4l5 5V4L7 9H3Zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4Z"/>
</svg>`,ud=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M3 9v6h4l5 5V4L7 9H3Zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.47 4.47 0 0 0 2.5-4ZM14 3.23v2.06a7 7 0 0 1 0 13.42v2.06a9 9 0 0 0 0-17.54Z"/>
</svg>`;function cd(i){return`
    <style>
      :host(:not([${s.MEDIA_VOLUME_LEVEL}])) slot[name=icon] slot:not([name=high]),
      :host([${s.MEDIA_VOLUME_LEVEL}=high]) slot[name=icon] slot:not([name=high]) {
        display: none !important;
      }

      :host([${s.MEDIA_VOLUME_LEVEL}=off]) slot[name=icon] slot:not([name=off]) {
        display: none !important;
      }

      :host([${s.MEDIA_VOLUME_LEVEL}=low]) slot[name=icon] slot:not([name=low]) {
        display: none !important;
      }

      :host([${s.MEDIA_VOLUME_LEVEL}=medium]) slot[name=icon] slot:not([name=medium]) {
        display: none !important;
      }

      :host(:not([${s.MEDIA_VOLUME_LEVEL}=off])) slot[name=tooltip-unmute],
      :host([${s.MEDIA_VOLUME_LEVEL}=off]) slot[name=tooltip-mute] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="off">${ld}</slot>
      <slot name="low">${ss}</slot>
      <slot name="medium">${ss}</slot>
      <slot name="high">${ud}</slot>
    </slot>
  `}function md(){return`
    <slot name="tooltip-mute">${b("Mute")}</slot>
    <slot name="tooltip-unmute">${b("Unmute")}</slot>
  `}var as=i=>{let e=i.mediaVolumeLevel==="off"?b("unmute"):b("mute");i.setAttribute("aria-label",e)},gt=class extends P{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_VOLUME_LEVEL]}connectedCallback(){super.connectedCallback(),as(this)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),t===s.MEDIA_VOLUME_LEVEL&&as(this)}get mediaVolumeLevel(){return L(this,s.MEDIA_VOLUME_LEVEL)}set mediaVolumeLevel(t){R(this,s.MEDIA_VOLUME_LEVEL,t)}handleClick(){let t=this.mediaVolumeLevel==="off"?E.MEDIA_UNMUTE_REQUEST:E.MEDIA_MUTE_REQUEST;this.dispatchEvent(new l.CustomEvent(t,{composed:!0,bubbles:!0}))}};gt.getSlotTemplateHTML=cd,gt.getTooltipContentHTML=md;l.customElements.get("media-mute-button")||l.customElements.define("media-mute-button",gt);var ds=gt;var ls=`<svg aria-hidden="true" viewBox="0 0 28 24">
  <path d="M24 3H4a1 1 0 0 0-1 1v16a1 1 0 0 0 1 1h20a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1Zm-1 16H5V5h18v14Zm-3-8h-7v5h7v-5Z"/>
</svg>`;function pd(i){return`
    <style>
      :host([${s.MEDIA_IS_PIP}]) slot[name=icon] slot:not([name=exit]) {
        display: none !important;
      }

      :host(:not([${s.MEDIA_IS_PIP}])) slot[name=icon] slot:not([name=enter]) {
        display: none !important;
      }

      :host([${s.MEDIA_IS_PIP}]) slot[name=tooltip-enter],
      :host(:not([${s.MEDIA_IS_PIP}])) slot[name=tooltip-exit] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="enter">${ls}</slot>
      <slot name="exit">${ls}</slot>
    </slot>
  `}function hd(){return`
    <slot name="tooltip-enter">${b("Enter picture in picture mode")}</slot>
    <slot name="tooltip-exit">${b("Exit picture in picture mode")}</slot>
  `}var us=i=>{let t=i.mediaIsPip?b("exit picture in picture mode"):b("enter picture in picture mode");i.setAttribute("aria-label",t)},bt=class extends P{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_IS_PIP,s.MEDIA_PIP_UNAVAILABLE]}connectedCallback(){super.connectedCallback(),us(this)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),t===s.MEDIA_IS_PIP&&us(this)}get mediaPipUnavailable(){return L(this,s.MEDIA_PIP_UNAVAILABLE)}set mediaPipUnavailable(t){R(this,s.MEDIA_PIP_UNAVAILABLE,t)}get mediaIsPip(){return S(this,s.MEDIA_IS_PIP)}set mediaIsPip(t){y(this,s.MEDIA_IS_PIP,t)}handleClick(){let t=this.mediaIsPip?E.MEDIA_EXIT_PIP_REQUEST:E.MEDIA_ENTER_PIP_REQUEST;this.dispatchEvent(new l.CustomEvent(t,{composed:!0,bubbles:!0}))}};bt.getSlotTemplateHTML=pd,bt.getTooltipContentHTML=hd;l.customElements.get("media-pip-button")||l.customElements.define("media-pip-button",bt);var cs=bt;var Qr={RATES:"rates"},Ed=[1,1.2,1.5,1.7,2],Ei=1;function gd(i){return`
    <style>
      :host {
        min-width: 5ch;
        padding: var(--media-button-padding, var(--media-control-padding, 10px 5px));
      }
    </style>
    <slot name="icon">${i.mediaplaybackrate||Ei}x</slot>
  `}function bd(){return b("Playback rate")}var Ee,ft=class extends P{constructor(){var e;super();m(this,Ee,new et(this,Qr.RATES,{defaultValue:Ed}));this.container=this.shadowRoot.querySelector('slot[name="icon"]'),this.container.innerHTML=`${(e=this.mediaPlaybackRate)!=null?e:Ei}x`}static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_PLAYBACK_RATE,Qr.RATES]}attributeChangedCallback(e,r,o){if(super.attributeChangedCallback(e,r,o),e===Qr.RATES&&(a(this,Ee).value=o),e===s.MEDIA_PLAYBACK_RATE){let n=o?+o:Number.NaN,d=Number.isNaN(n)?Ei:n;this.container.innerHTML=`${d}x`,this.setAttribute("aria-label",b("Playback rate {playbackRate}",{playbackRate:d}))}}get rates(){return a(this,Ee)}set rates(e){e?Array.isArray(e)?a(this,Ee).value=e.join(" "):typeof e=="string"&&(a(this,Ee).value=e):a(this,Ee).value=""}get mediaPlaybackRate(){return D(this,s.MEDIA_PLAYBACK_RATE,Ei)}set mediaPlaybackRate(e){O(this,s.MEDIA_PLAYBACK_RATE,e)}handleClick(){var n,d;let e=Array.from(a(this,Ee).values(),u=>+u).sort((u,c)=>u-c),r=(d=(n=e.find(u=>u>this.mediaPlaybackRate))!=null?n:e[0])!=null?d:Ei,o=new l.CustomEvent(E.MEDIA_PLAYBACK_RATE_REQUEST,{composed:!0,bubbles:!0,detail:r});this.dispatchEvent(o)}};Ee=new WeakMap,ft.getSlotTemplateHTML=gd,ft.getTooltipContentHTML=bd;l.customElements.get("media-playback-rate-button")||l.customElements.define("media-playback-rate-button",ft);var ms=ft;var fd=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="m6 21 15-9L6 3v18Z"/>
</svg>`,vd=`<svg aria-hidden="true" viewBox="0 0 24 24">
  <path d="M6 20h4V4H6v16Zm8-16v16h4V4h-4Z"/>
</svg>`;function Td(i){return`
    <style>
      :host([${s.MEDIA_PAUSED}]) slot[name=pause],
      :host(:not([${s.MEDIA_PAUSED}])) slot[name=play] {
        display: none !important;
      }

      :host([${s.MEDIA_PAUSED}]) slot[name=tooltip-pause],
      :host(:not([${s.MEDIA_PAUSED}])) slot[name=tooltip-play] {
        display: none;
      }
    </style>

    <slot name="icon">
      <slot name="play">${fd}</slot>
      <slot name="pause">${vd}</slot>
    </slot>
  `}function Ad(){return`
    <slot name="tooltip-play">${b("Play")}</slot>
    <slot name="tooltip-pause">${b("Pause")}</slot>
  `}var ps=i=>{let t=i.mediaPaused?b("play"):b("pause");i.setAttribute("aria-label",t)},vt=class extends P{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_PAUSED,s.MEDIA_ENDED]}connectedCallback(){super.connectedCallback(),ps(this)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),t===s.MEDIA_PAUSED&&ps(this)}get mediaPaused(){return S(this,s.MEDIA_PAUSED)}set mediaPaused(t){y(this,s.MEDIA_PAUSED,t)}handleClick(){let t=this.mediaPaused?E.MEDIA_PLAY_REQUEST:E.MEDIA_PAUSE_REQUEST;this.dispatchEvent(new l.CustomEvent(t,{composed:!0,bubbles:!0}))}};vt.getSlotTemplateHTML=Td,vt.getTooltipContentHTML=Ad;l.customElements.get("media-play-button")||l.customElements.define("media-play-button",vt);var hs=vt;var ue={PLACEHOLDER_SRC:"placeholdersrc",SRC:"src"};function Id(i){return`
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
  `}var Sd=i=>{i.style.removeProperty("background-image")},yd=(i,t)=>{i.style["background-image"]=`url('${t}')`},Tt=class extends l.HTMLElement{static get observedAttributes(){return[ue.PLACEHOLDER_SRC,ue.SRC]}constructor(){if(super(),!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let t=F(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}this.image=this.shadowRoot.querySelector("#image")}attributeChangedCallback(t,e,r){t===ue.SRC&&(r==null?this.image.removeAttribute(ue.SRC):this.image.setAttribute(ue.SRC,r)),t===ue.PLACEHOLDER_SRC&&(r==null?Sd(this.image):yd(this.image,r))}get placeholderSrc(){return L(this,ue.PLACEHOLDER_SRC)}set placeholderSrc(t){R(this,ue.SRC,t)}get src(){return L(this,ue.SRC)}set src(t){R(this,ue.SRC,t)}};Tt.shadowRootOptions={mode:"open"},Tt.getTemplateHTML=Id;l.customElements.get("media-poster-image")||l.customElements.define("media-poster-image",Tt);var Es=Tt;var gi,mr=class extends q{constructor(){super();m(this,gi,void 0);p(this,gi,this.shadowRoot.querySelector("slot"))}static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_PREVIEW_CHAPTER]}attributeChangedCallback(e,r,o){super.attributeChangedCallback(e,r,o),e===s.MEDIA_PREVIEW_CHAPTER&&o!==r&&o!=null&&(a(this,gi).textContent=o,o!==""?this.setAttribute("aria-valuetext",`chapter: ${o}`):this.removeAttribute("aria-valuetext"))}get mediaPreviewChapter(){return L(this,s.MEDIA_PREVIEW_CHAPTER)}set mediaPreviewChapter(e){R(this,s.MEDIA_PREVIEW_CHAPTER,e)}};gi=new WeakMap;l.customElements.get("media-preview-chapter-display")||l.customElements.define("media-preview-chapter-display",mr);var gs=mr;function Md(i){return`
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
  `}var ae,At=class extends l.HTMLElement{constructor(){super();m(this,ae,void 0);if(!this.shadowRoot){this.attachShadow(this.constructor.shadowRootOptions);let e=F(this.attributes);this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(e)}}static get observedAttributes(){return[_.MEDIA_CONTROLLER,s.MEDIA_PREVIEW_IMAGE,s.MEDIA_PREVIEW_COORDS]}connectedCallback(){var r,o,n;let e=this.getAttribute(_.MEDIA_CONTROLLER);e&&(p(this,ae,(r=this.getRootNode())==null?void 0:r.getElementById(e)),(n=(o=a(this,ae))==null?void 0:o.associateElement)==null||n.call(o,this))}disconnectedCallback(){var e,r;(r=(e=a(this,ae))==null?void 0:e.unassociateElement)==null||r.call(e,this),p(this,ae,null)}attributeChangedCallback(e,r,o){var n,d,u,c,h;[s.MEDIA_PREVIEW_IMAGE,s.MEDIA_PREVIEW_COORDS].includes(e)&&this.update(),e===_.MEDIA_CONTROLLER&&(r&&((d=(n=a(this,ae))==null?void 0:n.unassociateElement)==null||d.call(n,this),p(this,ae,null)),o&&this.isConnected&&(p(this,ae,(u=this.getRootNode())==null?void 0:u.getElementById(o)),(h=(c=a(this,ae))==null?void 0:c.associateElement)==null||h.call(c,this)))}get mediaPreviewImage(){return L(this,s.MEDIA_PREVIEW_IMAGE)}set mediaPreviewImage(e){R(this,s.MEDIA_PREVIEW_IMAGE,e)}get mediaPreviewCoords(){let e=this.getAttribute(s.MEDIA_PREVIEW_COORDS);if(e)return e.split(/\s+/).map(r=>+r)}set mediaPreviewCoords(e){if(!e){this.removeAttribute(s.MEDIA_PREVIEW_COORDS);return}this.setAttribute(s.MEDIA_PREVIEW_COORDS,e.join(" "))}update(){let e=this.mediaPreviewCoords,r=this.mediaPreviewImage;if(!(e&&r))return;let[o,n,d,u]=e,c=r.split("#")[0],h=getComputedStyle(this),{maxWidth:A,maxHeight:M,minWidth:T,minHeight:f}=h,w=Math.min(parseInt(A)/d,parseInt(M)/u),k=Math.max(parseInt(T)/d,parseInt(f)/u),x=w<1,C=x?w:k>1?k:1,{style:K}=U(this.shadowRoot,":host"),ce=U(this.shadowRoot,"img").style,ge=this.shadowRoot.querySelector("img"),Ct=x?"min":"max";K.setProperty(`${Ct}-width`,"initial","important"),K.setProperty(`${Ct}-height`,"initial","important"),K.width=`${d*C}px`,K.height=`${u*C}px`;let Ye=()=>{ce.width=`${this.imgWidth*C}px`,ce.height=`${this.imgHeight*C}px`,ce.display="block"};ge.src!==c&&(ge.onload=()=>{this.imgWidth=ge.naturalWidth,this.imgHeight=ge.naturalHeight,Ye()},ge.src=c,Ye()),Ye(),ce.transform=`translate(-${o*C}px, -${n*C}px)`}};ae=new WeakMap,At.shadowRootOptions={mode:"open"},At.getTemplateHTML=Md;l.customElements.get("media-preview-thumbnail")||l.customElements.define("media-preview-thumbnail",At);var bi=At;var It,pr=class extends q{constructor(){super();m(this,It,void 0);p(this,It,this.shadowRoot.querySelector("slot")),a(this,It).textContent=Z(0)}static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_PREVIEW_TIME]}attributeChangedCallback(e,r,o){super.attributeChangedCallback(e,r,o),e===s.MEDIA_PREVIEW_TIME&&o!=null&&(a(this,It).textContent=Z(parseFloat(o)))}get mediaPreviewTime(){return D(this,s.MEDIA_PREVIEW_TIME)}set mediaPreviewTime(e){O(this,s.MEDIA_PREVIEW_TIME,e)}};It=new WeakMap;l.customElements.get("media-preview-time-display")||l.customElements.define("media-preview-time-display",pr);var bs=pr;var St={SEEK_OFFSET:"seekoffset"},jr=30,_d=i=>`
  <svg aria-hidden="true" viewBox="0 0 20 24">
    <defs>
      <style>.text{font-size:8px;font-family:Arial-BoldMT, Arial;font-weight:700;}</style>
    </defs>
    <text class="text value" transform="translate(2.18 19.87)">${i}</text>
    <path d="M10 6V3L4.37 7 10 10.94V8a5.54 5.54 0 0 1 1.9 10.48v2.12A7.5 7.5 0 0 0 10 6Z"/>
  </svg>`;function Ld(i,t){return`
    <slot name="icon">${_d(t.seekOffset)}</slot>
  `}function Rd(){return b("Seek backward")}var kd=0,yt=class extends P{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_CURRENT_TIME,St.SEEK_OFFSET]}connectedCallback(){super.connectedCallback(),this.seekOffset=D(this,St.SEEK_OFFSET,jr)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),t===St.SEEK_OFFSET&&(this.seekOffset=D(this,St.SEEK_OFFSET,jr))}get seekOffset(){return D(this,St.SEEK_OFFSET,jr)}set seekOffset(t){O(this,St.SEEK_OFFSET,t),this.setAttribute("aria-label",b("seek back {seekOffset} seconds",{seekOffset:this.seekOffset})),Bi(Vi(this,"icon"),this.seekOffset)}get mediaCurrentTime(){return D(this,s.MEDIA_CURRENT_TIME,kd)}set mediaCurrentTime(t){O(this,s.MEDIA_CURRENT_TIME,t)}handleClick(){let t=Math.max(this.mediaCurrentTime-this.seekOffset,0),e=new l.CustomEvent(E.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:t});this.dispatchEvent(e)}};yt.getSlotTemplateHTML=Ld,yt.getTooltipContentHTML=Rd;l.customElements.get("media-seek-backward-button")||l.customElements.define("media-seek-backward-button",yt);var fs=yt;var Mt={SEEK_OFFSET:"seekoffset"},zr=30,xd=i=>`
  <svg aria-hidden="true" viewBox="0 0 20 24">
    <defs>
      <style>.text{font-size:8px;font-family:Arial-BoldMT, Arial;font-weight:700;}</style>
    </defs>
    <text class="text value" transform="translate(8.9 19.87)">${i}</text>
    <path d="M10 6V3l5.61 4L10 10.94V8a5.54 5.54 0 0 0-1.9 10.48v2.12A7.5 7.5 0 0 1 10 6Z"/>
  </svg>`;function Dd(i,t){return`
    <slot name="icon">${xd(t.seekOffset)}</slot>
  `}function wd(){return b("Seek forward")}var Cd=0,_t=class extends P{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_CURRENT_TIME,Mt.SEEK_OFFSET]}connectedCallback(){super.connectedCallback(),this.seekOffset=D(this,Mt.SEEK_OFFSET,zr)}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),t===Mt.SEEK_OFFSET&&(this.seekOffset=D(this,Mt.SEEK_OFFSET,zr))}get seekOffset(){return D(this,Mt.SEEK_OFFSET,zr)}set seekOffset(t){O(this,Mt.SEEK_OFFSET,t),this.setAttribute("aria-label",b("seek forward {seekOffset} seconds",{seekOffset:this.seekOffset})),Bi(Vi(this,"icon"),this.seekOffset)}get mediaCurrentTime(){return D(this,s.MEDIA_CURRENT_TIME,Cd)}set mediaCurrentTime(t){O(this,s.MEDIA_CURRENT_TIME,t)}handleClick(){let t=this.mediaCurrentTime+this.seekOffset,e=new l.CustomEvent(E.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:t});this.dispatchEvent(e)}};_t.getSlotTemplateHTML=Dd,_t.getTooltipContentHTML=wd;l.customElements.get("media-seek-forward-button")||l.customElements.define("media-seek-forward-button",_t);var vs=_t;var Fe={REMAINING:"remaining",SHOW_DURATION:"showduration",NO_TOGGLE:"notoggle"},Ts=[...Object.values(Fe),s.MEDIA_CURRENT_TIME,s.MEDIA_DURATION,s.MEDIA_SEEKABLE],As=["Enter"," "],Pd="&nbsp;/&nbsp;",Zr=(i,{timesSep:t=Pd}={})=>{var d,u;let e=(d=i.mediaCurrentTime)!=null?d:0,[,r]=(u=i.mediaSeekable)!=null?u:[],o=0;Number.isFinite(i.mediaDuration)?o=i.mediaDuration:Number.isFinite(r)&&(o=r);let n=i.remaining?Z(0-(o-e)):Z(e);return i.showDuration?`${n}${t}${Z(o)}`:n},Ud="video not loaded, unknown time.",Od=i=>{var u;let t=i.mediaCurrentTime,[,e]=(u=i.mediaSeekable)!=null?u:[],r=null;if(Number.isFinite(i.mediaDuration)?r=i.mediaDuration:Number.isFinite(e)&&(r=e),t==null||r===null){i.setAttribute("aria-valuetext",Ud);return}let o=i.remaining?fe(0-(r-t)):fe(t);if(!i.showDuration){i.setAttribute("aria-valuetext",o);return}let n=fe(r),d=`${o} of ${n}`;i.setAttribute("aria-valuetext",d)};function Nd(i,t){return`
    <slot>${Zr(t)}</slot>
  `}var $e,fi=class extends q{constructor(){super();m(this,$e,void 0);p(this,$e,this.shadowRoot.querySelector("slot")),a(this,$e).innerHTML=`${Zr(this)}`}static get observedAttributes(){return[...super.observedAttributes,...Ts,"disabled"]}connectedCallback(){let{style:e}=U(this.shadowRoot,":host(:hover:not([notoggle]))");e.setProperty("cursor","var(--media-cursor, pointer)"),e.setProperty("background","var(--media-control-hover-background, rgba(50 50 70 / .7))"),this.hasAttribute("disabled")||this.enable(),this.setAttribute("role","progressbar"),this.setAttribute("aria-label",b("playback time"));let r=o=>{let{key:n}=o;if(!As.includes(n)){this.removeEventListener("keyup",r);return}this.toggleTimeDisplay()};this.addEventListener("keydown",o=>{let{metaKey:n,altKey:d,key:u}=o;if(n||d||!As.includes(u)){this.removeEventListener("keyup",r);return}this.addEventListener("keyup",r)}),this.addEventListener("click",this.toggleTimeDisplay),super.connectedCallback()}toggleTimeDisplay(){this.noToggle||(this.hasAttribute("remaining")?this.removeAttribute("remaining"):this.setAttribute("remaining",""))}disconnectedCallback(){this.disable(),super.disconnectedCallback()}attributeChangedCallback(e,r,o){Ts.includes(e)?this.update():e==="disabled"&&o!==r&&(o==null?this.enable():this.disable()),super.attributeChangedCallback(e,r,o)}enable(){this.tabIndex=0}disable(){this.tabIndex=-1}get remaining(){return S(this,Fe.REMAINING)}set remaining(e){y(this,Fe.REMAINING,e)}get showDuration(){return S(this,Fe.SHOW_DURATION)}set showDuration(e){y(this,Fe.SHOW_DURATION,e)}get noToggle(){return S(this,Fe.NO_TOGGLE)}set noToggle(e){y(this,Fe.NO_TOGGLE,e)}get mediaDuration(){return D(this,s.MEDIA_DURATION)}set mediaDuration(e){O(this,s.MEDIA_DURATION,e)}get mediaCurrentTime(){return D(this,s.MEDIA_CURRENT_TIME)}set mediaCurrentTime(e){O(this,s.MEDIA_CURRENT_TIME,e)}get mediaSeekable(){let e=this.getAttribute(s.MEDIA_SEEKABLE);if(e)return e.split(":").map(r=>+r)}set mediaSeekable(e){if(e==null){this.removeAttribute(s.MEDIA_SEEKABLE);return}this.setAttribute(s.MEDIA_SEEKABLE,e.join(":"))}update(){let e=Zr(this);Od(this),e!==a(this,$e).innerHTML&&(a(this,$e).innerHTML=e)}};$e=new WeakMap,fi.getSlotTemplateHTML=Nd;l.customElements.get("media-time-display")||l.customElements.define("media-time-display",fi);var Is=fi;var Be,vi,Ve,Lt,Ti,Ai,Ii,Ke,_e,Si,hr=class{constructor(t,e,r){m(this,Be,void 0);m(this,vi,void 0);m(this,Ve,void 0);m(this,Lt,void 0);m(this,Ti,void 0);m(this,Ai,void 0);m(this,Ii,void 0);m(this,Ke,void 0);m(this,_e,0);m(this,Si,(t=performance.now())=>{p(this,_e,requestAnimationFrame(a(this,Si))),p(this,Lt,performance.now()-a(this,Ve));let e=1e3/this.fps;if(a(this,Lt)>e){p(this,Ve,t-a(this,Lt)%e);let r=1e3/((t-a(this,vi))/++Uo(this,Ti)._),o=(t-a(this,Ai))/1e3/this.duration,n=a(this,Ii)+o*this.playbackRate;n-a(this,Be).valueAsNumber>0?p(this,Ke,this.playbackRate/this.duration/r):(p(this,Ke,.995*a(this,Ke)),n=a(this,Be).valueAsNumber+a(this,Ke)),this.callback(n)}});p(this,Be,t),this.callback=e,this.fps=r}start(){a(this,_e)===0&&(p(this,Ve,performance.now()),p(this,vi,a(this,Ve)),p(this,Ti,0),a(this,Si).call(this))}stop(){a(this,_e)!==0&&(cancelAnimationFrame(a(this,_e)),p(this,_e,0))}update({start:t,duration:e,playbackRate:r}){let o=t-a(this,Be).valueAsNumber,n=Math.abs(e-this.duration);(o>0||o<-.03||n>=.5)&&this.callback(t),p(this,Ii,t),p(this,Ai,performance.now()),this.duration=e,this.playbackRate=r}};Be=new WeakMap,vi=new WeakMap,Ve=new WeakMap,Lt=new WeakMap,Ti=new WeakMap,Ai=new WeakMap,Ii=new WeakMap,Ke=new WeakMap,_e=new WeakMap,Si=new WeakMap;var Hd="video not loaded, unknown time.",Fd=i=>{let t=i.range,e=fe(+Ss(i)),r=fe(+i.mediaSeekableEnd),o=e&&r?`${e} of ${r}`:Hd;t.setAttribute("aria-valuetext",o)};function $d(i){return`
    ${re.getTemplateHTML(i)}
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

      :host(:is([${s.MEDIA_PREVIEW_IMAGE}], [${s.MEDIA_PREVIEW_TIME}])[dragging]) [part~="preview-box"] {
        transition-duration: var(--media-preview-transition-duration-in, .5s);
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        visibility: visible;
        opacity: 1;
      }

      @media (hover: hover) {
        :host(:is([${s.MEDIA_PREVIEW_IMAGE}], [${s.MEDIA_PREVIEW_TIME}]):hover) [part~="preview-box"] {
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

      :host([${s.MEDIA_PREVIEW_IMAGE}][dragging]) media-preview-thumbnail,
      :host([${s.MEDIA_PREVIEW_IMAGE}][dragging]) ::slotted(media-preview-thumbnail) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        visibility: visible;
      }

      @media (hover: hover) {
        :host([${s.MEDIA_PREVIEW_IMAGE}]:hover) media-preview-thumbnail,
        :host([${s.MEDIA_PREVIEW_IMAGE}]:hover) ::slotted(media-preview-thumbnail) {
          transition-delay: var(--media-preview-transition-delay-in, .25s);
          visibility: visible;
        }

        :host([${s.MEDIA_PREVIEW_TIME}]:hover) {
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

      :host([${s.MEDIA_PREVIEW_IMAGE}]) media-preview-chapter-display,
      :host([${s.MEDIA_PREVIEW_IMAGE}]) ::slotted(media-preview-chapter-display) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        border-radius: var(--media-preview-chapter-border-radius, 0);
        padding: var(--media-preview-chapter-padding, 3.5px 9px 0);
        margin: var(--media-preview-chapter-margin, 0);
        min-width: 100%;
      }

      media-preview-chapter-display[${s.MEDIA_PREVIEW_CHAPTER}],
      ::slotted(media-preview-chapter-display[${s.MEDIA_PREVIEW_CHAPTER}]) {
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

      :host([${s.MEDIA_PREVIEW_IMAGE}]) media-preview-time-display,
      :host([${s.MEDIA_PREVIEW_IMAGE}]) ::slotted(media-preview-time-display) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        border-radius: var(--media-preview-time-border-radius,
          0 0 var(--media-preview-border-radius) var(--media-preview-border-radius));
        min-width: 100%;
      }

      :host([${s.MEDIA_PREVIEW_TIME}]:hover) {
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
          <template shadowrootmode="${bi.shadowRootOptions.mode}">
            ${bi.getTemplateHTML({})}
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
  `}var Er=(i,t=i.mediaCurrentTime)=>{let e=Number.isFinite(i.mediaSeekableStart)?i.mediaSeekableStart:0,r=Number.isFinite(i.mediaDuration)?i.mediaDuration:i.mediaSeekableEnd;if(Number.isNaN(r))return 0;let o=(t-e)/(r-e);return Math.max(0,Math.min(o,1))},Ss=(i,t=i.range.valueAsNumber)=>{let e=Number.isFinite(i.mediaSeekableStart)?i.mediaSeekableStart:0,r=Number.isFinite(i.mediaDuration)?i.mediaDuration:i.mediaSeekableEnd;return Number.isNaN(r)?0:t*(r-e)+e},Ge,Le,Mi,kt,_i,Li,xt,Dt,We,qe,yi,br,ys,fr,Ri,Xr,ki,Jr,xi,eo,vr,Ms,wt,gr,Tr,_s,Rt=class extends re{constructor(){super();m(this,qe);m(this,br);m(this,Ri);m(this,ki);m(this,xi);m(this,vr);m(this,wt);m(this,Tr);m(this,Ge,void 0);m(this,Le,void 0);m(this,Mi,void 0);m(this,kt,void 0);m(this,_i,void 0);m(this,Li,void 0);m(this,xt,void 0);m(this,Dt,void 0);m(this,We,void 0);m(this,fr,e=>{this.dragging||(je(e)&&(this.range.valueAsNumber=e),this.updateBar())});this.shadowRoot.querySelector("#track").insertAdjacentHTML("afterbegin",'<div id="buffered" part="buffered"></div>'),p(this,Mi,this.shadowRoot.querySelectorAll('[part~="box"]')),p(this,_i,this.shadowRoot.querySelector('[part~="preview-box"]')),p(this,Li,this.shadowRoot.querySelector('[part~="current-box"]'));let r=getComputedStyle(this);p(this,xt,parseInt(r.getPropertyValue("--media-box-padding-left"))),p(this,Dt,parseInt(r.getPropertyValue("--media-box-padding-right"))),p(this,Le,new hr(this.range,a(this,fr),60))}static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_PAUSED,s.MEDIA_DURATION,s.MEDIA_SEEKABLE,s.MEDIA_CURRENT_TIME,s.MEDIA_PREVIEW_IMAGE,s.MEDIA_PREVIEW_TIME,s.MEDIA_PREVIEW_CHAPTER,s.MEDIA_BUFFERED,s.MEDIA_PLAYBACK_RATE,s.MEDIA_LOADING,s.MEDIA_ENDED]}connectedCallback(){var e;super.connectedCallback(),this.range.setAttribute("aria-label",b("seek")),v(this,qe,yi).call(this),p(this,Ge,this.getRootNode()),(e=a(this,Ge))==null||e.addEventListener("transitionstart",this)}disconnectedCallback(){var e;super.disconnectedCallback(),v(this,qe,yi).call(this),(e=a(this,Ge))==null||e.removeEventListener("transitionstart",this),p(this,Ge,null)}attributeChangedCallback(e,r,o){super.attributeChangedCallback(e,r,o),r!=o&&(e===s.MEDIA_CURRENT_TIME||e===s.MEDIA_PAUSED||e===s.MEDIA_ENDED||e===s.MEDIA_LOADING||e===s.MEDIA_DURATION||e===s.MEDIA_SEEKABLE?(a(this,Le).update({start:Er(this),duration:this.mediaSeekableEnd-this.mediaSeekableStart,playbackRate:this.mediaPlaybackRate}),v(this,qe,yi).call(this),Fd(this)):e===s.MEDIA_BUFFERED&&this.updateBufferedBar(),(e===s.MEDIA_DURATION||e===s.MEDIA_SEEKABLE)&&(this.mediaChaptersCues=a(this,We),this.updateBar()))}get mediaChaptersCues(){return a(this,We)}set mediaChaptersCues(e){var r;p(this,We,e),this.updateSegments((r=a(this,We))==null?void 0:r.map(o=>({start:Er(this,o.startTime),end:Er(this,o.endTime)})))}get mediaPaused(){return S(this,s.MEDIA_PAUSED)}set mediaPaused(e){y(this,s.MEDIA_PAUSED,e)}get mediaLoading(){return S(this,s.MEDIA_LOADING)}set mediaLoading(e){y(this,s.MEDIA_LOADING,e)}get mediaDuration(){return D(this,s.MEDIA_DURATION)}set mediaDuration(e){O(this,s.MEDIA_DURATION,e)}get mediaCurrentTime(){return D(this,s.MEDIA_CURRENT_TIME)}set mediaCurrentTime(e){O(this,s.MEDIA_CURRENT_TIME,e)}get mediaPlaybackRate(){return D(this,s.MEDIA_PLAYBACK_RATE,1)}set mediaPlaybackRate(e){O(this,s.MEDIA_PLAYBACK_RATE,e)}get mediaBuffered(){let e=this.getAttribute(s.MEDIA_BUFFERED);return e?e.split(" ").map(r=>r.split(":").map(o=>+o)):[]}set mediaBuffered(e){if(!e){this.removeAttribute(s.MEDIA_BUFFERED);return}let r=e.map(o=>o.join(":")).join(" ");this.setAttribute(s.MEDIA_BUFFERED,r)}get mediaSeekable(){let e=this.getAttribute(s.MEDIA_SEEKABLE);if(e)return e.split(":").map(r=>+r)}set mediaSeekable(e){if(e==null){this.removeAttribute(s.MEDIA_SEEKABLE);return}this.setAttribute(s.MEDIA_SEEKABLE,e.join(":"))}get mediaSeekableEnd(){var r;let[,e=this.mediaDuration]=(r=this.mediaSeekable)!=null?r:[];return e}get mediaSeekableStart(){var r;let[e=0]=(r=this.mediaSeekable)!=null?r:[];return e}get mediaPreviewImage(){return L(this,s.MEDIA_PREVIEW_IMAGE)}set mediaPreviewImage(e){R(this,s.MEDIA_PREVIEW_IMAGE,e)}get mediaPreviewTime(){return D(this,s.MEDIA_PREVIEW_TIME)}set mediaPreviewTime(e){O(this,s.MEDIA_PREVIEW_TIME,e)}get mediaEnded(){return S(this,s.MEDIA_ENDED)}set mediaEnded(e){y(this,s.MEDIA_ENDED,e)}updateBar(){super.updateBar(),this.updateBufferedBar(),this.updateCurrentBox()}updateBufferedBar(){var n;let e=this.mediaBuffered;if(!e.length)return;let r;if(this.mediaEnded)r=1;else{let d=this.mediaCurrentTime,[,u=this.mediaSeekableStart]=(n=e.find(([c,h])=>c<=d&&d<=h))!=null?n:[];r=Er(this,u)}let{style:o}=U(this.shadowRoot,"#buffered");o.setProperty("width",`${r*100}%`)}updateCurrentBox(){if(!this.shadowRoot.querySelector('slot[name="current"]').assignedElements().length)return;let r=U(this.shadowRoot,"#current-rail"),o=U(this.shadowRoot,'[part~="current-box"]'),n=v(this,Ri,Xr).call(this,a(this,Li)),d=v(this,ki,Jr).call(this,n,this.range.valueAsNumber),u=v(this,xi,eo).call(this,n,this.range.valueAsNumber);r.style.transform=`translateX(${d})`,r.style.setProperty("--_range-width",`${n.range.width}`),o.style.setProperty("--_box-shift",`${u}`),o.style.setProperty("--_box-width",`${n.box.width}px`),o.style.setProperty("visibility","initial")}handleEvent(e){switch(super.handleEvent(e),e.type){case"input":v(this,Tr,_s).call(this);break;case"pointermove":v(this,vr,Ms).call(this,e);break;case"pointerup":case"pointerleave":v(this,wt,gr).call(this,null);break;case"transitionstart":de(e.target,this)&&setTimeout(()=>v(this,qe,yi).call(this),0);break}}};Ge=new WeakMap,Le=new WeakMap,Mi=new WeakMap,kt=new WeakMap,_i=new WeakMap,Li=new WeakMap,xt=new WeakMap,Dt=new WeakMap,We=new WeakMap,qe=new WeakSet,yi=function(){v(this,br,ys).call(this)?a(this,Le).start():a(this,Le).stop()},br=new WeakSet,ys=function(){return this.isConnected&&!this.mediaPaused&&!this.mediaLoading&&!this.mediaEnded&&this.mediaSeekableEnd>0&&Ki(this)},fr=new WeakMap,Ri=new WeakSet,Xr=function(e){var h;let o=((h=this.getAttribute("bounds")?ve(this,`#${this.getAttribute("bounds")}`):this.parentElement)!=null?h:this).getBoundingClientRect(),n=this.range.getBoundingClientRect(),d=e.offsetWidth,u=-(n.left-o.left-d/2),c=o.right-n.left-d/2;return{box:{width:d,min:u,max:c},bounds:o,range:n}},ki=new WeakSet,Jr=function(e,r){let o=`${r*100}%`,{width:n,min:d,max:u}=e.box;if(!n)return o;if(Number.isNaN(d)||(o=`max(${`calc(1 / var(--_range-width) * 100 * ${d}% + var(--media-box-padding-left))`}, ${o})`),!Number.isNaN(u)){let h=`calc(1 / var(--_range-width) * 100 * ${u}% - var(--media-box-padding-right))`;o=`min(${o}, ${h})`}return o},xi=new WeakSet,eo=function(e,r){let{width:o,min:n,max:d}=e.box,u=r*e.range.width;if(u<n+a(this,xt)){let c=e.range.left-e.bounds.left-a(this,xt);return`${u-o/2+c}px`}if(u>d-a(this,Dt)){let c=e.bounds.right-e.range.right-a(this,Dt);return`${u+o/2-c-e.range.width}px`}return 0},vr=new WeakSet,Ms=function(e){let r=[...a(this,Mi)].some(T=>e.composedPath().includes(T));if(!this.dragging&&(r||!e.composedPath().includes(this))){v(this,wt,gr).call(this,null);return}let o=this.mediaSeekableEnd;if(!o)return;let n=U(this.shadowRoot,"#preview-rail"),d=U(this.shadowRoot,'[part~="preview-box"]'),u=v(this,Ri,Xr).call(this,a(this,_i)),c=(e.clientX-u.range.left)/u.range.width;c=Math.max(0,Math.min(1,c));let h=v(this,ki,Jr).call(this,u,c),A=v(this,xi,eo).call(this,u,c);n.style.transform=`translateX(${h})`,n.style.setProperty("--_range-width",`${u.range.width}`),d.style.setProperty("--_box-shift",`${A}`),d.style.setProperty("--_box-width",`${u.box.width}px`);let M=Math.round(a(this,kt))-Math.round(c*o);Math.abs(M)<1&&c>.01&&c<.99||(p(this,kt,c*o),v(this,wt,gr).call(this,a(this,kt)))},wt=new WeakSet,gr=function(e){this.dispatchEvent(new l.CustomEvent(E.MEDIA_PREVIEW_REQUEST,{composed:!0,bubbles:!0,detail:e}))},Tr=new WeakSet,_s=function(){a(this,Le).stop();let e=Ss(this);this.dispatchEvent(new l.CustomEvent(E.MEDIA_SEEK_REQUEST,{composed:!0,bubbles:!0,detail:e}))},Rt.shadowRootOptions={mode:"open"},Rt.getTemplateHTML=$d;l.customElements.get("media-time-range")||l.customElements.define("media-time-range",Rt);var Ls=Rt;var Bd=1,Vd=i=>i.mediaMuted?0:i.mediaVolume,Kd=i=>`${Math.round(i*100)}%`,Ar=class extends re{static get observedAttributes(){return[...super.observedAttributes,s.MEDIA_VOLUME,s.MEDIA_MUTED,s.MEDIA_VOLUME_UNAVAILABLE]}constructor(){super(),this.range.addEventListener("input",()=>{let t=this.range.value,e=new l.CustomEvent(E.MEDIA_VOLUME_REQUEST,{composed:!0,bubbles:!0,detail:t});this.dispatchEvent(e)})}connectedCallback(){super.connectedCallback(),this.range.setAttribute("aria-label",b("volume"))}attributeChangedCallback(t,e,r){super.attributeChangedCallback(t,e,r),(t===s.MEDIA_VOLUME||t===s.MEDIA_MUTED)&&(this.range.valueAsNumber=Vd(this),this.range.setAttribute("aria-valuetext",Kd(this.range.valueAsNumber)),this.updateBar())}get mediaVolume(){return D(this,s.MEDIA_VOLUME,Bd)}set mediaVolume(t){O(this,s.MEDIA_VOLUME,t)}get mediaMuted(){return S(this,s.MEDIA_MUTED)}set mediaMuted(t){y(this,s.MEDIA_MUTED,t)}get mediaVolumeUnavailable(){return L(this,s.MEDIA_VOLUME_UNAVAILABLE)}set mediaVolumeUnavailable(t){R(this,s.MEDIA_VOLUME_UNAVAILABLE,t)}};l.customElements.get("media-volume-range")||l.customElements.define("media-volume-range",Ar);var Rs=Ar;return $s(Gd);})();
//# sourceMappingURL=index.js.map
