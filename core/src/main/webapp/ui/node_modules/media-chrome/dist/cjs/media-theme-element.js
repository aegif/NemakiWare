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
var __reExport = (target, mod, secondTarget) => (__copyProps(target, mod, "default"), secondTarget && __copyProps(secondTarget, mod, "default"));
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
var media_theme_element_exports = {};
__export(media_theme_element_exports, {
  MediaThemeElement: () => MediaThemeElement
});
module.exports = __toCommonJS(media_theme_element_exports);
var import_constants = require("./constants.js");
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_template_parts = require("./utils/template-parts.js");
var import_template_processor = require("./utils/template-processor.js");
var import_utils = require("./utils/utils.js");
__reExport(media_theme_element_exports, require("./utils/template-parts.js"), module.exports);
var _template, _prevTemplate, _prevTemplateId, _upgradeProperty, upgradeProperty_fn, _updateTemplate, updateTemplate_fn;
const observedMediaAttributes = {
  mediatargetlivewindow: "targetlivewindow",
  mediastreamtype: "streamtype"
};
const prependTemplate = import_server_safe_globals.document.createElement("template");
prependTemplate.innerHTML = /*html*/
`
  <style>
    :host {
      display: inline-block;
      line-height: 0;
    }

    media-controller {
      width: 100%;
      height: 100%;
    }

    media-captions-button:not([mediasubtitleslist]),
    media-captions-menu:not([mediasubtitleslist]),
    media-captions-menu-button:not([mediasubtitleslist]),
    media-audio-track-menu[mediaaudiotrackunavailable],
    media-audio-track-menu-button[mediaaudiotrackunavailable],
    media-rendition-menu[mediarenditionunavailable],
    media-rendition-menu-button[mediarenditionunavailable],
    media-volume-range[mediavolumeunavailable],
    media-airplay-button[mediaairplayunavailable],
    media-fullscreen-button[mediafullscreenunavailable],
    media-cast-button[mediacastunavailable],
    media-pip-button[mediapipunavailable] {
      display: none;
    }
  </style>
`;
class MediaThemeElement extends import_server_safe_globals.globalThis.HTMLElement {
  constructor() {
    super();
    __privateAdd(this, _upgradeProperty);
    __privateAdd(this, _updateTemplate);
    __privateAdd(this, _template, void 0);
    __privateAdd(this, _prevTemplate, void 0);
    __privateAdd(this, _prevTemplateId, void 0);
    if (this.shadowRoot) {
      this.renderRoot = this.shadowRoot;
    } else {
      this.renderRoot = this.attachShadow({ mode: "open" });
      this.createRenderer();
    }
    const observer = new MutationObserver((mutationList) => {
      var _a;
      if (this.mediaController && !((_a = this.mediaController) == null ? void 0 : _a.breakpointsComputed))
        return;
      if (mutationList.some((mutation) => {
        const target = mutation.target;
        if (target === this)
          return true;
        if (target.localName !== "media-controller")
          return false;
        if (observedMediaAttributes[mutation.attributeName])
          return true;
        if (mutation.attributeName.startsWith("breakpoint"))
          return true;
        return false;
      })) {
        this.render();
      }
    });
    observer.observe(this, { attributes: true });
    observer.observe(this.renderRoot, {
      attributes: true,
      subtree: true
    });
    this.addEventListener(
      import_constants.MediaStateChangeEvents.BREAKPOINTS_COMPUTED,
      this.render
    );
    __privateMethod(this, _upgradeProperty, upgradeProperty_fn).call(this, "template");
  }
  /** @type {HTMLElement & { breakpointsComputed?: boolean }} */
  get mediaController() {
    return this.renderRoot.querySelector("media-controller");
  }
  get template() {
    var _a;
    return (_a = __privateGet(this, _template)) != null ? _a : this.constructor.template;
  }
  set template(element) {
    __privateSet(this, _prevTemplateId, null);
    __privateSet(this, _template, element);
    this.createRenderer();
  }
  get props() {
    var _a, _b, _c;
    const observedAttributes = [
      ...Array.from((_b = (_a = this.mediaController) == null ? void 0 : _a.attributes) != null ? _b : []).filter(
        ({ name }) => {
          return observedMediaAttributes[name] || name.startsWith("breakpoint");
        }
      ),
      ...Array.from(this.attributes)
    ];
    const props = {};
    for (const attr of observedAttributes) {
      const name = (_c = observedMediaAttributes[attr.name]) != null ? _c : (0, import_utils.camelCase)(attr.name);
      let { value } = attr;
      if (value != null) {
        if ((0, import_utils.isNumericString)(value)) {
          value = parseFloat(value);
        }
        props[name] = value === "" ? true : value;
      } else {
        props[name] = false;
      }
    }
    return props;
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    if (attrName === "template" && oldValue != newValue) {
      __privateMethod(this, _updateTemplate, updateTemplate_fn).call(this);
    }
  }
  connectedCallback() {
    __privateMethod(this, _updateTemplate, updateTemplate_fn).call(this);
  }
  createRenderer() {
    if (this.template && this.template !== __privateGet(this, _prevTemplate)) {
      __privateSet(this, _prevTemplate, this.template);
      this.renderer = new import_template_parts.TemplateInstance(
        this.template,
        this.props,
        // @ts-ignore
        this.constructor.processor
      );
      this.renderRoot.textContent = "";
      this.renderRoot.append(
        prependTemplate.content.cloneNode(true),
        this.renderer
      );
    }
  }
  render() {
    var _a;
    (_a = this.renderer) == null ? void 0 : _a.update(this.props);
  }
}
_template = new WeakMap();
_prevTemplate = new WeakMap();
_prevTemplateId = new WeakMap();
_upgradeProperty = new WeakSet();
upgradeProperty_fn = function(prop) {
  if (Object.prototype.hasOwnProperty.call(this, prop)) {
    const value = this[prop];
    delete this[prop];
    this[prop] = value;
  }
};
_updateTemplate = new WeakSet();
updateTemplate_fn = function() {
  var _a;
  const templateId = this.getAttribute("template");
  if (!templateId || templateId === __privateGet(this, _prevTemplateId))
    return;
  const rootNode = this.getRootNode();
  const template = (_a = rootNode == null ? void 0 : rootNode.getElementById) == null ? void 0 : _a.call(rootNode, templateId);
  if (template) {
    __privateSet(this, _prevTemplateId, templateId);
    __privateSet(this, _template, template);
    this.createRenderer();
    return;
  }
  if (isValidUrl(templateId)) {
    __privateSet(this, _prevTemplateId, templateId);
    request(templateId).then((data) => {
      const template2 = import_server_safe_globals.document.createElement("template");
      template2.innerHTML = data;
      __privateSet(this, _template, template2);
      this.createRenderer();
    }).catch(console.error);
  }
};
MediaThemeElement.observedAttributes = ["template"];
MediaThemeElement.processor = import_template_processor.processor;
function isValidUrl(url) {
  if (!/^(\/|\.\/|https?:\/\/)/.test(url))
    return false;
  const base = /^https?:\/\//.test(url) ? void 0 : location.origin;
  try {
    new URL(url, base);
  } catch (e) {
    return false;
  }
  return true;
}
async function request(resource) {
  const response = await fetch(resource);
  if (response.status !== 200) {
    throw new Error(
      `Failed to load resource: the server responded with a status of ${response.status}`
    );
  }
  return response.text();
}
if (!import_server_safe_globals.globalThis.customElements.get("media-theme")) {
  import_server_safe_globals.globalThis.customElements.define("media-theme", MediaThemeElement);
}
