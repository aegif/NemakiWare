var __accessCheck = (obj, member, msg) => {
  if (!member.has(obj))
    throw TypeError("Cannot " + msg);
};
var __privateGet = (obj, member, getter) => {
  __accessCheck(obj, member, "read from private field");
  return getter ? getter.call(obj) : member.get(obj);
};
var __privateAdd = (obj, member, value) => {
  if (member.has(obj))
    throw TypeError("Cannot add the same private member more than once");
  member instanceof WeakSet ? member.add(obj) : member.set(obj, value);
};
var __privateSet = (obj, member, value, setter) => {
  __accessCheck(obj, member, "write to private field");
  setter ? setter.call(obj, value) : member.set(obj, value);
  return value;
};
var _mediaController;
import { globalThis } from "./utils/server-safe-globals.js";
import {
  MediaUIAttributes,
  MediaStateReceiverAttributes
} from "./constants.js";
import {
  getOrInsertCSSRule,
  getStringAttr,
  namedNodeMapToObject,
  setStringAttr
} from "./utils/element-utils.js";
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
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
  `
  );
}
class MediaPreviewThumbnail extends globalThis.HTMLElement {
  constructor() {
    super();
    __privateAdd(this, _mediaController, void 0);
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = namedNodeMapToObject(this.attributes);
      this.shadowRoot.innerHTML = this.constructor.getTemplateHTML(attrs);
    }
  }
  static get observedAttributes() {
    return [
      MediaStateReceiverAttributes.MEDIA_CONTROLLER,
      MediaUIAttributes.MEDIA_PREVIEW_IMAGE,
      MediaUIAttributes.MEDIA_PREVIEW_COORDS
    ];
  }
  connectedCallback() {
    var _a, _b, _c;
    const mediaControllerId = this.getAttribute(
      MediaStateReceiverAttributes.MEDIA_CONTROLLER
    );
    if (mediaControllerId) {
      __privateSet(
        this,
        _mediaController,
        // @ts-ignore
        (_a = this.getRootNode()) == null ? void 0 : _a.getElementById(mediaControllerId)
      );
      (_c = (_b = __privateGet(this, _mediaController)) == null ? void 0 : _b.associateElement) == null ? void 0 : _c.call(_b, this);
    }
  }
  disconnectedCallback() {
    var _a, _b;
    (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.unassociateElement) == null ? void 0 : _b.call(_a, this);
    __privateSet(this, _mediaController, null);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    var _a, _b, _c, _d, _e;
    if ([
      MediaUIAttributes.MEDIA_PREVIEW_IMAGE,
      MediaUIAttributes.MEDIA_PREVIEW_COORDS
    ].includes(attrName)) {
      this.update();
    }
    if (attrName === MediaStateReceiverAttributes.MEDIA_CONTROLLER) {
      if (oldValue) {
        (_b = (_a = __privateGet(this, _mediaController)) == null ? void 0 : _a.unassociateElement) == null ? void 0 : _b.call(_a, this);
        __privateSet(this, _mediaController, null);
      }
      if (newValue && this.isConnected) {
        __privateSet(this, _mediaController, (_c = this.getRootNode()) == null ? void 0 : _c.getElementById(newValue));
        (_e = (_d = __privateGet(this, _mediaController)) == null ? void 0 : _d.associateElement) == null ? void 0 : _e.call(_d, this);
      }
    }
  }
  /**
   * @type {string | undefined} The url of the preview image
   */
  get mediaPreviewImage() {
    return getStringAttr(this, MediaUIAttributes.MEDIA_PREVIEW_IMAGE);
  }
  set mediaPreviewImage(value) {
    setStringAttr(this, MediaUIAttributes.MEDIA_PREVIEW_IMAGE, value);
  }
  /**
   * @type {Array<number> | undefined} Fixed length array [x, y, width, height] or undefined
   */
  get mediaPreviewCoords() {
    const attrVal = this.getAttribute(MediaUIAttributes.MEDIA_PREVIEW_COORDS);
    if (!attrVal)
      return void 0;
    return attrVal.split(/\s+/).map((coord) => +coord);
  }
  set mediaPreviewCoords(value) {
    if (!value) {
      this.removeAttribute(MediaUIAttributes.MEDIA_PREVIEW_COORDS);
      return;
    }
    this.setAttribute(MediaUIAttributes.MEDIA_PREVIEW_COORDS, value.join(" "));
  }
  update() {
    const coords = this.mediaPreviewCoords;
    const previewImage = this.mediaPreviewImage;
    if (!(coords && previewImage))
      return;
    const [x, y, w, h] = coords;
    const src = previewImage.split("#")[0];
    const computedStyle = getComputedStyle(this);
    const { maxWidth, maxHeight, minWidth, minHeight } = computedStyle;
    const maxRatio = Math.min(parseInt(maxWidth) / w, parseInt(maxHeight) / h);
    const minRatio = Math.max(parseInt(minWidth) / w, parseInt(minHeight) / h);
    const isScalingDown = maxRatio < 1;
    const scale = isScalingDown ? maxRatio : minRatio > 1 ? minRatio : 1;
    const { style } = getOrInsertCSSRule(this.shadowRoot, ":host");
    const imgStyle = getOrInsertCSSRule(this.shadowRoot, "img").style;
    const img = this.shadowRoot.querySelector("img");
    const extremum = isScalingDown ? "min" : "max";
    style.setProperty(`${extremum}-width`, "initial", "important");
    style.setProperty(`${extremum}-height`, "initial", "important");
    style.width = `${w * scale}px`;
    style.height = `${h * scale}px`;
    const resize = () => {
      imgStyle.width = `${this.imgWidth * scale}px`;
      imgStyle.height = `${this.imgHeight * scale}px`;
      imgStyle.display = "block";
    };
    if (img.src !== src) {
      img.onload = () => {
        this.imgWidth = img.naturalWidth;
        this.imgHeight = img.naturalHeight;
        resize();
      };
      img.src = src;
      resize();
    }
    resize();
    imgStyle.transform = `translate(-${x * scale}px, -${y * scale}px)`;
  }
}
_mediaController = new WeakMap();
MediaPreviewThumbnail.shadowRootOptions = { mode: "open" };
MediaPreviewThumbnail.getTemplateHTML = getTemplateHTML;
if (!globalThis.customElements.get("media-preview-thumbnail")) {
  globalThis.customElements.define(
    "media-preview-thumbnail",
    MediaPreviewThumbnail
  );
}
var media_preview_thumbnail_default = MediaPreviewThumbnail;
export {
  media_preview_thumbnail_default as default
};
