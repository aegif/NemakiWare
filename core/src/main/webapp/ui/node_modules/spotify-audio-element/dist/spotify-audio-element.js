const EMBED_BASE = "https://open.spotify.com";
const MATCH_SRC = /open\.spotify\.com\/(\w+)\/(\w+)/i;
const API_URL = "https://open.spotify.com/embed-podcast/iframe-api/v1";
const API_GLOBAL = "SpotifyIframeApi";
const API_GLOBAL_READY = "onSpotifyIframeApiReady";
function getTemplateHTML(attrs, props = {}) {
  const iframeAttrs = {
    src: serializeIframeUrl(attrs, props),
    scrolling: "no",
    frameborder: 0,
    width: "100%",
    height: "100%",
    allow: "accelerometer; fullscreen; autoplay; encrypted-media; gyroscope; picture-in-picture"
  };
  return (
    /*html*/
    `
    <style>
      :host {
        display: inline-block;
        min-width: 160px;
        min-height: 80px;
        position: relative;
      }
      iframe {
        position: absolute;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        overflow: hidden;
      }
      :host(:not([controls])) {
        display: none !important;
      }
    </style>
    <iframe${serializeAttributes(iframeAttrs)}></iframe>
  `
  );
}
function serializeIframeUrl(attrs, props) {
  var _a, _b, _c;
  if (!attrs.src) return;
  const matches = attrs.src.match(MATCH_SRC);
  const type = matches && matches[1];
  const metaId = matches && matches[2];
  const params = {
    t: (_a = props.config) == null ? void 0 : _a.startAt,
    theme: ((_b = props.config) == null ? void 0 : _b.theme) === "dark" ? "0" : null
  };
  const videoPath = ((_c = props.config) == null ? void 0 : _c.preferVideo) ? "/video" : "";
  return `${EMBED_BASE}/embed/${type}/${metaId}${videoPath}?${serialize(params)}`;
}
class SpotifyAudioElement extends (globalThis.HTMLElement ?? class {
}) {
  static getTemplateHTML = getTemplateHTML;
  static shadowRootOptions = { mode: "open" };
  static observedAttributes = [
    "controls",
    "loop",
    "src"
  ];
  loadComplete = new PublicPromise();
  #loadRequested;
  #hasLoaded;
  #isInit;
  #isWaiting = false;
  #closeToEnded = false;
  #paused = true;
  #currentTime = 0;
  #duration = NaN;
  #seeking = false;
  #config = null;
  constructor() {
    super();
    this.#upgradeProperty("config");
  }
  async load() {
    var _a, _b, _c;
    if (this.#loadRequested) return;
    if (this.#hasLoaded) this.loadComplete = new PublicPromise();
    this.#hasLoaded = true;
    await (this.#loadRequested = Promise.resolve());
    this.#loadRequested = null;
    this.#isWaiting = false;
    this.#closeToEnded = false;
    this.#currentTime = 0;
    this.#duration = NaN;
    this.#seeking = false;
    this.dispatchEvent(new Event("emptied"));
    let oldApi = this.api;
    this.api = null;
    if (!this.src) {
      return;
    }
    this.dispatchEvent(new Event("loadstart"));
    const options = {
      t: (_a = this.config) == null ? void 0 : _a.startAt,
      theme: ((_b = this.config) == null ? void 0 : _b.theme) === "dark" ? "0" : null,
      preferVideo: (_c = this.config) == null ? void 0 : _c.preferVideo
    };
    if (this.#isInit) {
      this.api = oldApi;
      this.api.iframeElement.src = serializeIframeUrl(namedNodeMapToObject(this.attributes), this);
    } else {
      this.#isInit = true;
      if (!this.shadowRoot) {
        this.attachShadow({ mode: "open" });
        this.shadowRoot.innerHTML = getTemplateHTML(namedNodeMapToObject(this.attributes), this);
      }
      let iframe = this.shadowRoot.querySelector("iframe");
      const Spotify = await loadScript(API_URL, API_GLOBAL, API_GLOBAL_READY);
      this.api = await new Promise((resolve) => Spotify.createController(iframe, options, resolve));
      this.api.iframeElement = iframe;
      this.api.addListener("ready", () => {
        this.dispatchEvent(new Event("loadedmetadata"));
        this.dispatchEvent(new Event("durationchange"));
        this.dispatchEvent(new Event("volumechange"));
      });
      this.api.addListener("playback_update", (event) => {
        if (this.#closeToEnded && this.#paused && (event.data.isBuffering || !event.data.isPaused)) {
          this.#closeToEnded = false;
          this.currentTime = 1;
          return;
        }
        if (event.data.duration / 1e3 !== this.#duration) {
          this.#closeToEnded = false;
          this.#duration = event.data.duration / 1e3;
          this.dispatchEvent(new Event("durationchange"));
        }
        if (event.data.position / 1e3 !== this.#currentTime) {
          this.#seeking = false;
          this.#closeToEnded = false;
          this.#currentTime = event.data.position / 1e3;
          this.dispatchEvent(new Event("timeupdate"));
        }
        if (!this.#isWaiting && !this.#paused && event.data.isPaused) {
          this.#paused = true;
          this.dispatchEvent(new Event("pause"));
          return;
        }
        if (this.#paused && (event.data.isBuffering || !event.data.isPaused)) {
          this.#paused = false;
          this.dispatchEvent(new Event("play"));
          this.#isWaiting = event.data.isBuffering;
          if (this.#isWaiting) {
            this.dispatchEvent(new Event("waiting"));
          } else {
            this.dispatchEvent(new Event("playing"));
          }
          return;
        }
        if (this.#isWaiting && !event.data.isPaused) {
          this.#isWaiting = false;
          this.dispatchEvent(new Event("playing"));
          return;
        }
        if (!this.paused && !this.seeking && !this.#closeToEnded && Math.ceil(this.currentTime) >= this.duration) {
          this.#closeToEnded = true;
          if (this.loop) {
            this.currentTime = 1;
            return;
          }
          if (!this.continuous) {
            this.pause();
            this.dispatchEvent(new Event("ended"));
          }
          return;
        }
      });
    }
    this.loadComplete.resolve();
    await this.loadComplete;
  }
  async attributeChangedCallback(attrName, oldValue, newValue) {
    if (oldValue === newValue) return;
    switch (attrName) {
      case "src": {
        this.load();
        return;
      }
    }
  }
  async play() {
    var _a;
    this.#paused = false;
    this.#isWaiting = true;
    this.dispatchEvent(new Event("play"));
    await this.loadComplete;
    return (_a = this.api) == null ? void 0 : _a.resume();
  }
  async pause() {
    var _a;
    await this.loadComplete;
    return (_a = this.api) == null ? void 0 : _a.pause();
  }
  get config() {
    return this.#config;
  }
  set config(value) {
    this.#config = value;
  }
  get paused() {
    return this.#paused ?? true;
  }
  get muted() {
    return false;
  }
  set muted(val) {
  }
  get volume() {
    return 1;
  }
  set volume(val) {
  }
  get ended() {
    return Math.ceil(this.currentTime) >= this.duration;
  }
  get seeking() {
    return this.#seeking;
  }
  get loop() {
    return this.hasAttribute("loop");
  }
  set loop(val) {
    if (this.loop == val) return;
    this.toggleAttribute("loop", Boolean(val));
  }
  get currentTime() {
    return this.#currentTime;
  }
  set currentTime(val) {
    if (this.currentTime == val) return;
    this.#seeking = true;
    let oldTime = this.#currentTime;
    this.#currentTime = val;
    this.dispatchEvent(new Event("timeupdate"));
    this.#currentTime = oldTime;
    this.loadComplete.then(() => {
      var _a;
      (_a = this.api) == null ? void 0 : _a.seek(val);
    });
  }
  get duration() {
    return this.#duration;
  }
  get src() {
    return this.getAttribute("src");
  }
  set src(val) {
    this.setAttribute("src", `${val}`);
  }
  // This is a pattern to update property values that are set before
  // the custom element is upgraded.
  // https://web.dev/custom-elements-best-practices/#make-properties-lazy
  #upgradeProperty(prop) {
    if (Object.prototype.hasOwnProperty.call(this, prop)) {
      const value = this[prop];
      delete this[prop];
      this[prop] = value;
    }
  }
}
function serializeAttributes(attrs) {
  let html = "";
  for (const key in attrs) {
    const value = attrs[key];
    if (value === "") html += ` ${key}`;
    else html += ` ${key}="${value}"`;
  }
  return html;
}
function serialize(props) {
  return String(new URLSearchParams(boolToBinary(props)));
}
function boolToBinary(props) {
  let p = {};
  for (let key in props) {
    let val = props[key];
    if (val === true || val === "") p[key] = 1;
    else if (val === false) p[key] = 0;
    else if (val != null) p[key] = val;
  }
  return p;
}
function namedNodeMapToObject(namedNodeMap) {
  let obj = {};
  for (let attr of namedNodeMap) {
    obj[attr.name] = attr.value;
  }
  return obj;
}
const loadScriptCache = {};
async function loadScript(src, globalName, readyFnName) {
  if (loadScriptCache[src]) return loadScriptCache[src];
  if (globalName && self[globalName]) {
    return Promise.resolve(self[globalName]);
  }
  return loadScriptCache[src] = new Promise(function(resolve, reject) {
    const script = document.createElement("script");
    script.src = src;
    const ready = (api) => resolve(api);
    if (readyFnName) self[readyFnName] = ready;
    script.onload = () => !readyFnName && ready();
    script.onerror = reject;
    document.head.append(script);
  });
}
class PublicPromise extends Promise {
  constructor(executor = () => {
  }) {
    let res, rej;
    super((resolve, reject) => {
      executor(resolve, reject);
      res = resolve;
      rej = reject;
    });
    this.resolve = res;
    this.reject = rej;
  }
}
if (globalThis.customElements && !globalThis.customElements.get("spotify-audio")) {
  globalThis.customElements.define("spotify-audio", SpotifyAudioElement);
}
var spotify_audio_element_default = SpotifyAudioElement;
export {
  spotify_audio_element_default as default
};
