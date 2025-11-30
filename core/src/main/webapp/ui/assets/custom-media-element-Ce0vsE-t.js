const f=["abort","canplay","canplaythrough","durationchange","emptied","encrypted","ended","error","loadeddata","loadedmetadata","loadstart","pause","play","playing","progress","ratechange","seeked","seeking","stalled","suspend","timeupdate","volumechange","waiting","waitingforkey","resize","enterpictureinpicture","leavepictureinpicture","webkitbeginfullscreen","webkitendfullscreen","webkitpresentationmodechanged"],u=["autopictureinpicture","disablepictureinpicture","disableremoteplayback","autoplay","controls","controlslist","crossorigin","loop","muted","playsinline","poster","preload","src"];function b(a){return`
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
      <audio${p(a)}></audio>
    </slot>
    <slot></slot>
  `}function m(a){return`
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
      <video${p(a)}></video>
    </slot>
    <slot></slot>
  `}function h(a,{tag:n,is:o}){const l=globalThis.document?.createElement?.(n,{is:o}),d=l?g(l):[];return class c extends a{static getTemplateHTML=n.endsWith("audio")?b:m;static shadowRootOptions={mode:"open"};static Events=f;static#i=!1;static get observedAttributes(){return c.#l(),[...l?.constructor?.observedAttributes??[],...u]}static#l(){if(this.#i)return;this.#i=!0;const t=new Set(this.observedAttributes);t.delete("muted");for(const i of d)if(!(i in this.prototype))if(typeof l[i]=="function")this.prototype[i]=function(...r){return this.#t(),this.call?this.call(i,...r):this.nativeEl?.[i]?.apply(this.nativeEl,r)};else{const r={get(){this.#t();const e=i.toLowerCase();if(t.has(e)){const s=this.getAttribute(e);return s===null?!1:s===""?!0:s}return this.get?.(i)??this.nativeEl?.[i]}};i!==i.toUpperCase()&&(r.set=function(e){this.#t();const s=i.toLowerCase();if(t.has(s)){e===!0||e===!1||e==null?this.toggleAttribute(s,!!e):this.setAttribute(s,e);return}if(this.set){this.set(i,e);return}this.nativeEl&&(this.nativeEl[i]=e)}),Object.defineProperty(this.prototype,i,r)}}#s=!1;#o=null;#e=new Map;#n;get;set;call;get nativeEl(){return this.#t(),this.#o??this.querySelector(":scope > [slot=media]")??this.querySelector(n)??this.shadowRoot?.querySelector(n)??null}set nativeEl(t){this.#o=t}get defaultMuted(){return this.hasAttribute("muted")}set defaultMuted(t){this.toggleAttribute("muted",t)}get src(){return this.getAttribute("src")}set src(t){this.setAttribute("src",`${t}`)}get preload(){return this.getAttribute("preload")??this.nativeEl?.preload}set preload(t){this.setAttribute("preload",`${t}`)}#t(){this.#s||(this.#s=!0,this.init())}init(){if(!this.shadowRoot){this.attachShadow({mode:"open"});const t=v(this.attributes);o&&(t.is=o),n&&(t.part=n),this.shadowRoot.innerHTML=this.constructor.getTemplateHTML(t)}this.nativeEl.muted=this.hasAttribute("muted");for(const t of d)this.#d(t);this.#n=new MutationObserver(this.#c.bind(this)),this.shadowRoot.addEventListener("slotchange",()=>this.#r()),this.#r();for(const t of this.constructor.Events)this.shadowRoot.addEventListener(t,this,!0)}handleEvent(t){t.target===this.nativeEl&&this.dispatchEvent(new CustomEvent(t.type,{detail:t.detail}))}#r(){const t=new Map(this.#e);(this.shadowRoot?.querySelector("slot:not([name])")?.assignedElements({flatten:!0}).filter(e=>["track","source"].includes(e.localName))).forEach(e=>{t.delete(e);let s=this.#e.get(e);s||(s=e.cloneNode(),this.#e.set(e,s),this.#n?.observe(e,{attributes:!0})),this.nativeEl?.append(s),this.#a(s)}),t.forEach((e,s)=>{e.remove(),this.#e.delete(s)})}#c(t){for(const i of t)if(i.type==="attributes"){const{target:r,attributeName:e}=i,s=this.#e.get(r);s&&e&&(s.setAttribute(e,r.getAttribute(e)??""),this.#a(s))}}#a(t){t&&t.localName==="track"&&t.default&&(t.kind==="chapters"||t.kind==="metadata")&&t.track.mode==="disabled"&&(t.track.mode="hidden")}#d(t){if(Object.prototype.hasOwnProperty.call(this,t)){const i=this[t];delete this[t],this[t]=i}}attributeChangedCallback(t,i,r){this.#t(),this.#u(t,i,r)}#u(t,i,r){["id","class"].includes(t)||!c.observedAttributes.includes(t)&&this.constructor.observedAttributes.includes(t)||(r===null?this.nativeEl?.removeAttribute(t):this.nativeEl?.getAttribute(t)!==r&&this.nativeEl?.setAttribute(t,r))}connectedCallback(){this.#t()}}}function g(a){const n=[];for(let o=Object.getPrototypeOf(a);o&&o!==HTMLElement.prototype;o=Object.getPrototypeOf(o)){const l=Object.getOwnPropertyNames(o);n.push(...l)}return n}function p(a){let n="";for(const o in a){if(!u.includes(o))continue;const l=a[o];l===""?n+=` ${o}`:n+=` ${o}="${l}"`}return n}function v(a){const n={};for(const o of a)n[o.name]=o.value;return n}const y=h(globalThis.HTMLElement??class{},{tag:"video"});h(globalThis.HTMLElement??class{},{tag:"audio"});export{y as C};
