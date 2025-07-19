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
var media_poster_image_exports = {};
__export(media_poster_image_exports, {
  Attributes: () => Attributes,
  default: () => media_poster_image_default
});
module.exports = __toCommonJS(media_poster_image_exports);
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_element_utils = require("./utils/element-utils.js");
const Attributes = {
  PLACEHOLDER_SRC: "placeholdersrc",
  SRC: "src"
};
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
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
  `
  );
}
const unsetBackgroundImage = (el) => {
  el.style.removeProperty("background-image");
};
const setBackgroundImage = (el, image) => {
  el.style["background-image"] = `url('${image}')`;
};
class MediaPosterImage extends import_server_safe_globals.globalThis.HTMLElement {
  static get observedAttributes() {
    return [Attributes.PLACEHOLDER_SRC, Attributes.SRC];
  }
  constructor() {
    super();
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = (0, import_element_utils.namedNodeMapToObject)(this.attributes);
      this.shadowRoot.innerHTML = this.constructor.getTemplateHTML(attrs);
    }
    this.image = this.shadowRoot.querySelector("#image");
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    if (attrName === Attributes.SRC) {
      if (newValue == null) {
        this.image.removeAttribute(Attributes.SRC);
      } else {
        this.image.setAttribute(Attributes.SRC, newValue);
      }
    }
    if (attrName === Attributes.PLACEHOLDER_SRC) {
      if (newValue == null) {
        unsetBackgroundImage(this.image);
      } else {
        setBackgroundImage(this.image, newValue);
      }
    }
  }
  /**
   *
   */
  get placeholderSrc() {
    return (0, import_element_utils.getStringAttr)(this, Attributes.PLACEHOLDER_SRC);
  }
  set placeholderSrc(value) {
    (0, import_element_utils.setStringAttr)(this, Attributes.SRC, value);
  }
  /**
   *
   */
  get src() {
    return (0, import_element_utils.getStringAttr)(this, Attributes.SRC);
  }
  set src(value) {
    (0, import_element_utils.setStringAttr)(this, Attributes.SRC, value);
  }
}
MediaPosterImage.shadowRootOptions = { mode: "open" };
MediaPosterImage.getTemplateHTML = getTemplateHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-poster-image")) {
  import_server_safe_globals.globalThis.customElements.define("media-poster-image", MediaPosterImage);
}
var media_poster_image_default = MediaPosterImage;
