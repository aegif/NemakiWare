var B=Object.defineProperty;var H=i=>{throw TypeError(i)};var I=(i,e,s)=>e in i?B(i,e,{enumerable:!0,configurable:!0,writable:!0,value:s}):i[e]=s;var m=(i,e,s)=>I(i,typeof e!="symbol"?e+"":e,s),C=(i,e,s)=>e.has(i)||H("Cannot "+s);var p=(i,e,s)=>(C(i,e,"read from private field"),s?s.call(i):e.get(i)),b=(i,e,s)=>e.has(i)?H("Cannot add the same private member more than once"):e instanceof WeakSet?e.add(i):e.set(i,s),M=(i,e,s,u)=>(C(i,e,"write to private field"),u?u.call(i,s):e.set(i,s),s),d=(i,e,s)=>(C(i,e,"access private method"),s);const U=["abort","canplay","canplaythrough","durationchange","emptied","encrypted","ended","error","loadeddata","loadedmetadata","loadstart","pause","play","playing","progress","ratechange","seeked","seeking","stalled","suspend","timeupdate","volumechange","waiting","waitingforkey","resize","enterpictureinpicture","leavepictureinpicture","webkitbeginfullscreen","webkitendfullscreen","webkitpresentationmodechanged"],R=["autopictureinpicture","disablepictureinpicture","disableremoteplayback","autoplay","controls","controlslist","crossorigin","loop","muted","playsinline","poster","preload","src"];function V(i){return`
    <style>
      :host {
        display: inline-flex;
        line-height: 0;
        flex-direction: column;
        justify-content: end;
      }

      audio {
        width: 100%;
      }
    </style>
    <slot name="media">
      <audio${D(i)}></audio>
    </slot>
    <slot></slot>
  `}function W(i){return`
    <style>
      :host {
        display: inline-block;
        line-height: 0;
      }

      video {
        max-width: 100%;
        max-height: 100%;
        min-width: 100%;
        min-height: 100%;
        object-fit: var(--media-object-fit, contain);
        object-position: var(--media-object-position, 50% 50%);
      }

      video::-webkit-media-text-track-container {
        transform: var(--media-webkit-text-track-transform);
        transition: var(--media-webkit-text-track-transition);
      }
    </style>
    <slot name="media">
      <video${D(i)}></video>
    </slot>
    <slot></slot>
  `}function z(i,{tag:e,is:s}){var k,x,h,y,T,$,A,w,f,E,r,g,O,S,j,q,N;const u=(x=(k=globalThis.document)==null?void 0:k.createElement)==null?void 0:x.call(k,e,{is:s}),L=u?G(u):[];return h=class extends i{constructor(){super(...arguments);b(this,r);b(this,A,!1);b(this,w,null);b(this,f,new Map);b(this,E);m(this,"get");m(this,"set");m(this,"call")}static get observedAttributes(){var o,l;return d(o=h,T,$).call(o),[...((l=u==null?void 0:u.constructor)==null?void 0:l.observedAttributes)??[],...R]}get nativeEl(){var t;return d(this,r,g).call(this),p(this,w)??this.querySelector(":scope > [slot=media]")??this.querySelector(e)??((t=this.shadowRoot)==null?void 0:t.querySelector(e))??null}set nativeEl(t){M(this,w,t)}get defaultMuted(){return this.hasAttribute("muted")}set defaultMuted(t){this.toggleAttribute("muted",t)}get src(){return this.getAttribute("src")}set src(t){this.setAttribute("src",`${t}`)}get preload(){var t;return this.getAttribute("preload")??((t=this.nativeEl)==null?void 0:t.preload)}set preload(t){this.setAttribute("preload",`${t}`)}init(){if(!this.shadowRoot){this.attachShadow({mode:"open"});const t=J(this.attributes);s&&(t.is=s),e&&(t.part=e),this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}this.nativeEl.muted=this.hasAttribute("muted");for(const t of L)d(this,r,q).call(this,t);M(this,E,new MutationObserver(d(this,r,S).bind(this))),this.shadowRoot.addEventListener("slotchange",()=>d(this,r,O).call(this)),d(this,r,O).call(this);for(const t of this.constructor.Events)this.shadowRoot.addEventListener(t,this,!0)}handleEvent(t){t.target===this.nativeEl&&this.dispatchEvent(new CustomEvent(t.type,{detail:t.detail}))}attributeChangedCallback(t,o,l){d(this,r,g).call(this),d(this,r,N).call(this,t,o,l)}connectedCallback(){d(this,r,g).call(this)}},y=new WeakMap,T=new WeakSet,$=function(){if(p(this,y))return;M(this,y,!0);const t=new Set(this.observedAttributes);t.delete("muted");for(const o of L)if(!(o in this.prototype))if(typeof u[o]=="function")this.prototype[o]=function(...l){return d(this,r,g).call(this),(()=>{var c;if(this.call)return this.call(o,...l);const n=(c=this.nativeEl)==null?void 0:c[o];return n==null?void 0:n.apply(this.nativeEl,l)})()};else{const l={get(){var n,c;d(this,r,g).call(this);const a=o.toLowerCase();if(t.has(a)){const v=this.getAttribute(a);return v===null?!1:v===""?!0:v}return((n=this.get)==null?void 0:n.call(this,o))??((c=this.nativeEl)==null?void 0:c[o])}};o!==o.toUpperCase()&&(l.set=function(a){d(this,r,g).call(this);const n=o.toLowerCase();if(t.has(n)){a===!0||a===!1||a==null?this.toggleAttribute(n,!!a):this.setAttribute(n,a);return}if(this.set){this.set(o,a);return}this.nativeEl&&(this.nativeEl[o]=a)}),Object.defineProperty(this.prototype,o,l)}},A=new WeakMap,w=new WeakMap,f=new WeakMap,E=new WeakMap,r=new WeakSet,g=function(){p(this,A)||(M(this,A,!0),this.init())},O=function(){var a;const t=new Map(p(this,f)),o=(a=this.shadowRoot)==null?void 0:a.querySelector("slot:not([name])");(o==null?void 0:o.assignedElements({flatten:!0}).filter(n=>["track","source"].includes(n.localName))).forEach(n=>{var v,P;t.delete(n);let c=p(this,f).get(n);c||(c=n.cloneNode(),p(this,f).set(n,c),(v=p(this,E))==null||v.observe(n,{attributes:!0})),(P=this.nativeEl)==null||P.append(c),d(this,r,j).call(this,c)}),t.forEach((n,c)=>{n.remove(),p(this,f).delete(c)})},S=function(t){for(const o of t)if(o.type==="attributes"){const{target:l,attributeName:a}=o,n=p(this,f).get(l);n&&a&&(n.setAttribute(a,l.getAttribute(a)??""),d(this,r,j).call(this,n))}},j=function(t){t&&t.localName==="track"&&t.default&&(t.kind==="chapters"||t.kind==="metadata")&&t.track.mode==="disabled"&&(t.track.mode="hidden")},q=function(t){if(Object.prototype.hasOwnProperty.call(this,t)){const o=this[t];delete this[t],this[t]=o}},N=function(t,o,l){var a,n,c;["id","class"].includes(t)||!h.observedAttributes.includes(t)&&this.constructor.observedAttributes.includes(t)||(l===null?(a=this.nativeEl)==null||a.removeAttribute(t):((n=this.nativeEl)==null?void 0:n.getAttribute(t))!==l&&((c=this.nativeEl)==null||c.setAttribute(t,l)))},b(h,T),m(h,"getTemplateHTML",e.endsWith("audio")?V:W),m(h,"shadowRootOptions",{mode:"open"}),m(h,"Events",U),b(h,y,!1),h}function G(i){const e=[];for(let s=Object.getPrototypeOf(i);s&&s!==HTMLElement.prototype;s=Object.getPrototypeOf(s)){const u=Object.getOwnPropertyNames(s);e.push(...u)}return e}function D(i){let e="";for(const s in i){if(!R.includes(s))continue;const u=i[s];u===""?e+=` ${s}`:e+=` ${s}="${u}"`}return e}function J(i){const e={};for(const s of i)e[s.name]=s.value;return e}const X=z(globalThis.HTMLElement??class{},{tag:"video"});z(globalThis.HTMLElement??class{},{tag:"audio"});export{X as C};
