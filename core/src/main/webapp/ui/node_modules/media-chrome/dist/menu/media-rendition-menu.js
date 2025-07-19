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
var __privateMethod = (obj, member, method) => {
  __accessCheck(obj, member, "access private method");
  return method;
};
var _renditionList, _prevState, _render, render_fn, _onChange, onChange_fn;
import { globalThis } from "../utils/server-safe-globals.js";
import { MediaUIAttributes, MediaUIEvents } from "../constants.js";
import {
  getMediaController,
  getStringAttr,
  setStringAttr,
  getNumericAttr,
  setNumericAttr
} from "../utils/element-utils.js";
import { parseRenditionList } from "../utils/utils.js";
import {
  MediaChromeMenu,
  createMenuItem,
  createIndicator
} from "./media-chrome-menu.js";
import { t } from "../utils/i18n.js";
class MediaRenditionMenu extends MediaChromeMenu {
  constructor() {
    super(...arguments);
    __privateAdd(this, _render);
    __privateAdd(this, _onChange);
    __privateAdd(this, _renditionList, []);
    __privateAdd(this, _prevState, {});
  }
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      MediaUIAttributes.MEDIA_RENDITION_LIST,
      MediaUIAttributes.MEDIA_RENDITION_SELECTED,
      MediaUIAttributes.MEDIA_RENDITION_UNAVAILABLE,
      MediaUIAttributes.MEDIA_HEIGHT
    ];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === MediaUIAttributes.MEDIA_RENDITION_SELECTED && oldValue !== newValue) {
      this.value = newValue != null ? newValue : "auto";
      __privateMethod(this, _render, render_fn).call(this);
    } else if (attrName === MediaUIAttributes.MEDIA_RENDITION_LIST && oldValue !== newValue) {
      __privateSet(this, _renditionList, parseRenditionList(newValue));
      __privateMethod(this, _render, render_fn).call(this);
    } else if (attrName === MediaUIAttributes.MEDIA_HEIGHT && oldValue !== newValue) {
      __privateMethod(this, _render, render_fn).call(this);
    }
  }
  connectedCallback() {
    super.connectedCallback();
    this.addEventListener("change", __privateMethod(this, _onChange, onChange_fn));
  }
  disconnectedCallback() {
    super.disconnectedCallback();
    this.removeEventListener("change", __privateMethod(this, _onChange, onChange_fn));
  }
  /**
   * Returns the anchor element when it is a floating menu.
   */
  get anchorElement() {
    if (this.anchor !== "auto")
      return super.anchorElement;
    return getMediaController(this).querySelector(
      "media-rendition-menu-button"
    );
  }
  get mediaRenditionList() {
    return __privateGet(this, _renditionList);
  }
  set mediaRenditionList(list) {
    __privateSet(this, _renditionList, list);
    __privateMethod(this, _render, render_fn).call(this);
  }
  /**
   * Get selected rendition id.
   */
  get mediaRenditionSelected() {
    return getStringAttr(this, MediaUIAttributes.MEDIA_RENDITION_SELECTED);
  }
  set mediaRenditionSelected(id) {
    setStringAttr(this, MediaUIAttributes.MEDIA_RENDITION_SELECTED, id);
  }
  get mediaHeight() {
    return getNumericAttr(this, MediaUIAttributes.MEDIA_HEIGHT);
  }
  set mediaHeight(height) {
    setNumericAttr(this, MediaUIAttributes.MEDIA_HEIGHT, height);
  }
}
_renditionList = new WeakMap();
_prevState = new WeakMap();
_render = new WeakSet();
render_fn = function() {
  if (__privateGet(this, _prevState).mediaRenditionList === JSON.stringify(this.mediaRenditionList) && __privateGet(this, _prevState).mediaHeight === this.mediaHeight)
    return;
  __privateGet(this, _prevState).mediaRenditionList = JSON.stringify(this.mediaRenditionList);
  __privateGet(this, _prevState).mediaHeight = this.mediaHeight;
  const renditionList = this.mediaRenditionList.sort(
    (a, b) => b.height - a.height
  );
  for (const rendition of renditionList) {
    rendition.selected = rendition.id === this.mediaRenditionSelected;
  }
  this.defaultSlot.textContent = "";
  const isAuto = !this.mediaRenditionSelected;
  for (const rendition of renditionList) {
    const text2 = this.formatMenuItemText(
      `${Math.min(rendition.width, rendition.height)}p`,
      rendition
    );
    const item2 = createMenuItem({
      type: "radio",
      text: text2,
      value: `${rendition.id}`,
      checked: rendition.selected && !isAuto
    });
    item2.prepend(createIndicator(this, "checked-indicator"));
    this.defaultSlot.append(item2);
  }
  const text = isAuto ? this.formatMenuItemText(`${t("Auto")} (${this.mediaHeight}p)`) : this.formatMenuItemText(t("Auto"));
  const item = createMenuItem({
    type: "radio",
    text,
    value: "auto",
    checked: isAuto
  });
  const autoDescription = this.mediaHeight > 0 ? `${t("Auto")} (${this.mediaHeight}p)` : t("Auto");
  item.dataset.description = autoDescription;
  item.prepend(createIndicator(this, "checked-indicator"));
  this.defaultSlot.append(item);
};
_onChange = new WeakSet();
onChange_fn = function() {
  if (this.value == null)
    return;
  const event = new globalThis.CustomEvent(
    MediaUIEvents.MEDIA_RENDITION_REQUEST,
    {
      composed: true,
      bubbles: true,
      detail: this.value
    }
  );
  this.dispatchEvent(event);
};
if (!globalThis.customElements.get("media-rendition-menu")) {
  globalThis.customElements.define("media-rendition-menu", MediaRenditionMenu);
}
var media_rendition_menu_default = MediaRenditionMenu;
export {
  MediaRenditionMenu,
  media_rendition_menu_default as default
};
