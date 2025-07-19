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
var media_error_dialog_exports = {};
__export(media_error_dialog_exports, {
  MediaErrorDialog: () => MediaErrorDialog,
  default: () => media_error_dialog_default
});
module.exports = __toCommonJS(media_error_dialog_exports);
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_constants = require("./constants.js");
var import_labels = require("./labels/labels.js");
var import_media_chrome_dialog = require("./media-chrome-dialog.js");
var import_element_utils = require("./utils/element-utils.js");
var _mediaError;
function getSlotTemplateHTML(attrs) {
  return (
    /*html*/
    `
    <style>
      :host {
        background: rgb(20 20 30 / .8);
      }

      #content {
        display: block;
        padding: 1.2em 1.5em;
      }

      h3,
      p {
        margin-block: 0 .3em;
      }
    </style>
    <slot name="error-${attrs.mediaerrorcode}" id="content">
      ${formatErrorMessage({ code: +attrs.mediaerrorcode, message: attrs.mediaerrormessage })}
    </slot>
  `
  );
}
function shouldOpenErrorDialog(error) {
  return error.code && (0, import_labels.formatError)(error) !== null;
}
function formatErrorMessage(error) {
  var _a;
  const { title, message } = (_a = (0, import_labels.formatError)(error)) != null ? _a : {};
  let html = "";
  if (title)
    html += `<slot name="error-${error.code}-title"><h3>${title}</h3></slot>`;
  if (message)
    html += `<slot name="error-${error.code}-message"><p>${message}</p></slot>`;
  return html;
}
const observedAttributes = [
  import_constants.MediaUIAttributes.MEDIA_ERROR_CODE,
  import_constants.MediaUIAttributes.MEDIA_ERROR_MESSAGE
];
class MediaErrorDialog extends import_media_chrome_dialog.MediaChromeDialog {
  constructor() {
    super(...arguments);
    __privateAdd(this, _mediaError, null);
  }
  static get observedAttributes() {
    return [...super.observedAttributes, ...observedAttributes];
  }
  formatErrorMessage(error) {
    return this.constructor.formatErrorMessage(error);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    var _a;
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (!observedAttributes.includes(attrName))
      return;
    const mediaError = (_a = this.mediaError) != null ? _a : {
      code: this.mediaErrorCode,
      message: this.mediaErrorMessage
    };
    this.open = shouldOpenErrorDialog(mediaError);
    if (this.open) {
      this.shadowRoot.querySelector("slot").name = `error-${this.mediaErrorCode}`;
      this.shadowRoot.querySelector("#content").innerHTML = this.formatErrorMessage(mediaError);
    }
  }
  get mediaError() {
    return __privateGet(this, _mediaError);
  }
  set mediaError(value) {
    __privateSet(this, _mediaError, value);
  }
  get mediaErrorCode() {
    return (0, import_element_utils.getNumericAttr)(this, "mediaerrorcode");
  }
  set mediaErrorCode(value) {
    (0, import_element_utils.setNumericAttr)(this, "mediaerrorcode", value);
  }
  get mediaErrorMessage() {
    return (0, import_element_utils.getStringAttr)(this, "mediaerrormessage");
  }
  set mediaErrorMessage(value) {
    (0, import_element_utils.setStringAttr)(this, "mediaerrormessage", value);
  }
}
_mediaError = new WeakMap();
MediaErrorDialog.getSlotTemplateHTML = getSlotTemplateHTML;
MediaErrorDialog.formatErrorMessage = formatErrorMessage;
if (!import_server_safe_globals.globalThis.customElements.get("media-error-dialog")) {
  import_server_safe_globals.globalThis.customElements.define("media-error-dialog", MediaErrorDialog);
}
var media_error_dialog_default = MediaErrorDialog;
