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
var _slot;
import { MediaTextDisplay } from "./media-text-display.js";
import { globalThis } from "./utils/server-safe-globals.js";
import { MediaUIAttributes } from "./constants.js";
import { getStringAttr, setStringAttr } from "./utils/element-utils.js";
class MediaPreviewChapterDisplay extends MediaTextDisplay {
  constructor() {
    super();
    __privateAdd(this, _slot, void 0);
    __privateSet(this, _slot, this.shadowRoot.querySelector("slot"));
  }
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      MediaUIAttributes.MEDIA_PREVIEW_CHAPTER
    ];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === MediaUIAttributes.MEDIA_PREVIEW_CHAPTER) {
      if (newValue !== oldValue && newValue != null) {
        __privateGet(this, _slot).textContent = newValue;
        if (newValue !== "") {
          this.setAttribute("aria-valuetext", `chapter: ${newValue}`);
        } else {
          this.removeAttribute("aria-valuetext");
        }
      }
    }
  }
  /**
   * @type {string | undefined} Timeline preview chapter
   */
  get mediaPreviewChapter() {
    return getStringAttr(this, MediaUIAttributes.MEDIA_PREVIEW_CHAPTER);
  }
  set mediaPreviewChapter(value) {
    setStringAttr(this, MediaUIAttributes.MEDIA_PREVIEW_CHAPTER, value);
  }
}
_slot = new WeakMap();
if (!globalThis.customElements.get("media-preview-chapter-display")) {
  globalThis.customElements.define(
    "media-preview-chapter-display",
    MediaPreviewChapterDisplay
  );
}
var media_preview_chapter_display_default = MediaPreviewChapterDisplay;
export {
  media_preview_chapter_display_default as default
};
