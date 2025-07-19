var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);
var wistia_video_element_exports = {};
__export(wistia_video_element_exports, {
  default: () => wistia_video_element_default,
  uniqueId: () => uniqueId
});
module.exports = __toCommonJS(wistia_video_element_exports);
var import_super_media_element = require("super-media-element");
var _a, _b;
const templateLightDOM = (_a = globalThis.document) == null ? void 0 : _a.createElement("template");
if (templateLightDOM) {
  templateLightDOM.innerHTML = /*html*/
  `
  <div class="wistia_embed"></div>
  `;
}
const templateShadowDOM = (_b = globalThis.document) == null ? void 0 : _b.createElement("template");
if (templateShadowDOM) {
  templateShadowDOM.innerHTML = /*html*/
  `
  <style>
    :host {
      display: inline-block;
      min-width: 300px;
      min-height: 150px;
      position: relative;
    }
    ::slotted(.wistia_embed) {
      position: absolute;
      width: 100%;
      height: 100%;
    }
  </style>
  <slot></slot>
  `;
}
class WistiaVideoElement extends import_super_media_element.SuperVideoElement {
  static template = templateShadowDOM;
  static skipAttributes = ["src"];
  get nativeEl() {
    var _a2;
    return ((_a2 = this.api) == null ? void 0 : _a2.elem()) ?? this.querySelector("video");
  }
  async load() {
    var _a2;
    (_a2 = this.querySelector(".wistia_embed")) == null ? void 0 : _a2.remove();
    if (!this.src) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
    const MATCH_SRC = /(?:wistia\.com|wi\.st)\/(?:medias|embed)\/(.*)$/i;
    const id = this.src.match(MATCH_SRC)[1];
    const options = {
      autoPlay: this.autoplay,
      preload: this.preload ?? "metadata",
      playsinline: this.playsInline,
      endVideoBehavior: this.loop && "loop",
      chromeless: !this.controls,
      playButton: this.controls,
      muted: this.defaultMuted
    };
    this.append(templateLightDOM.content.cloneNode(true));
    const div = this.querySelector(".wistia_embed");
    if (!div.id) div.id = uniqueId(id);
    div.classList.add(`wistia_async_${id}`);
    const scriptUrl = "https://fast.wistia.com/assets/external/E-v1.js";
    await loadScript(scriptUrl, "Wistia");
    this.api = await new Promise((onReady) => {
      globalThis._wq.push({
        id: div.id,
        onReady,
        options
      });
    });
  }
  async attributeChangedCallback(attrName, oldValue, newValue) {
    if (attrName === "controls") {
      await this.loadComplete;
      switch (attrName) {
        case "controls":
          this.api.bigPlayButtonEnabled(this.controls);
          this.controls ? this.api.releaseChromeless() : this.api.requestChromeless();
          break;
      }
      return;
    }
    super.attributeChangedCallback(attrName, oldValue, newValue);
  }
  // Override some methods w/ defaults if the video element is not ready yet when called.
  // Some methods require the Wistia API instead of the native video element API.
  get duration() {
    var _a2;
    return (_a2 = this.api) == null ? void 0 : _a2.duration();
  }
  play() {
    this.api.play();
    return new Promise((resolve) => this.addEventListener("playing", resolve));
  }
}
const loadScriptCache = {};
async function loadScript(src, globalName) {
  if (!globalName) return import(
    /* webpackIgnore: true */
    src
  );
  if (loadScriptCache[src]) return loadScriptCache[src];
  if (self[globalName]) return self[globalName];
  return loadScriptCache[src] = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.defer = true;
    script.src = src;
    script.onload = () => resolve(self[globalName]);
    script.onerror = reject;
    document.head.append(script);
  });
}
let idCounter = 0;
function uniqueId(prefix) {
  const id = ++idCounter;
  return `${prefix}${id}`;
}
if (globalThis.customElements && !globalThis.customElements.get("wistia-video")) {
  globalThis.customElements.define("wistia-video", WistiaVideoElement);
}
var wistia_video_element_default = WistiaVideoElement;
// Annotate the CommonJS export names for ESM import in node:
0 && (module.exports = {
  uniqueId
});
