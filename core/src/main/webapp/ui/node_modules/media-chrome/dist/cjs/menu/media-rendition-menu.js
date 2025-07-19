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
var media_rendition_menu_exports = {};
__export(media_rendition_menu_exports, {
  MediaRenditionMenu: () => MediaRenditionMenu,
  default: () => media_rendition_menu_default
});
module.exports = __toCommonJS(media_rendition_menu_exports);
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var import_constants = require("../constants.js");
var import_element_utils = require("../utils/element-utils.js");
var import_utils = require("../utils/utils.js");
var import_media_chrome_menu = require("./media-chrome-menu.js");
var import_i18n = require("../utils/i18n.js");
var _renditionList, _prevState, _render, render_fn, _onChange, onChange_fn;
class MediaRenditionMenu extends import_media_chrome_menu.MediaChromeMenu {
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
      import_constants.MediaUIAttributes.MEDIA_RENDITION_LIST,
      import_constants.MediaUIAttributes.MEDIA_RENDITION_SELECTED,
      import_constants.MediaUIAttributes.MEDIA_RENDITION_UNAVAILABLE,
      import_constants.MediaUIAttributes.MEDIA_HEIGHT
    ];
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (attrName === import_constants.MediaUIAttributes.MEDIA_RENDITION_SELECTED && oldValue !== newValue) {
      this.value = newValue != null ? newValue : "auto";
      __privateMethod(this, _render, render_fn).call(this);
    } else if (attrName === import_constants.MediaUIAttributes.MEDIA_RENDITION_LIST && oldValue !== newValue) {
      __privateSet(this, _renditionList, (0, import_utils.parseRenditionList)(newValue));
      __privateMethod(this, _render, render_fn).call(this);
    } else if (attrName === import_constants.MediaUIAttributes.MEDIA_HEIGHT && oldValue !== newValue) {
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
    return (0, import_element_utils.getMediaController)(this).querySelector(
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
    return (0, import_element_utils.getStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_RENDITION_SELECTED);
  }
  set mediaRenditionSelected(id) {
    (0, import_element_utils.setStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_RENDITION_SELECTED, id);
  }
  get mediaHeight() {
    return (0, import_element_utils.getNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_HEIGHT);
  }
  set mediaHeight(height) {
    (0, import_element_utils.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_HEIGHT, height);
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
    const item2 = (0, import_media_chrome_menu.createMenuItem)({
      type: "radio",
      text: text2,
      value: `${rendition.id}`,
      checked: rendition.selected && !isAuto
    });
    item2.prepend((0, import_media_chrome_menu.createIndicator)(this, "checked-indicator"));
    this.defaultSlot.append(item2);
  }
  const text = isAuto ? this.formatMenuItemText(`${(0, import_i18n.t)("Auto")} (${this.mediaHeight}p)`) : this.formatMenuItemText((0, import_i18n.t)("Auto"));
  const item = (0, import_media_chrome_menu.createMenuItem)({
    type: "radio",
    text,
    value: "auto",
    checked: isAuto
  });
  const autoDescription = this.mediaHeight > 0 ? `${(0, import_i18n.t)("Auto")} (${this.mediaHeight}p)` : (0, import_i18n.t)("Auto");
  item.dataset.description = autoDescription;
  item.prepend((0, import_media_chrome_menu.createIndicator)(this, "checked-indicator"));
  this.defaultSlot.append(item);
};
_onChange = new WeakSet();
onChange_fn = function() {
  if (this.value == null)
    return;
  const event = new import_server_safe_globals.globalThis.CustomEvent(
    import_constants.MediaUIEvents.MEDIA_RENDITION_REQUEST,
    {
      composed: true,
      bubbles: true,
      detail: this.value
    }
  );
  this.dispatchEvent(event);
};
if (!import_server_safe_globals.globalThis.customElements.get("media-rendition-menu")) {
  import_server_safe_globals.globalThis.customElements.define("media-rendition-menu", MediaRenditionMenu);
}
var media_rendition_menu_default = MediaRenditionMenu;
