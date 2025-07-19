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
var _mediaError;
import { globalThis } from "./utils/server-safe-globals.js";
import { MediaUIAttributes } from "./constants.js";
import { formatError } from "./labels/labels.js";
import { MediaChromeDialog } from "./media-chrome-dialog.js";
import {
  getNumericAttr,
  getStringAttr,
  setNumericAttr,
  setStringAttr
} from "./utils/element-utils.js";
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
  return error.code && formatError(error) !== null;
}
function formatErrorMessage(error) {
  var _a;
  const { title, message } = (_a = formatError(error)) != null ? _a : {};
  let html = "";
  if (title)
    html += `<slot name="error-${error.code}-title"><h3>${title}</h3></slot>`;
  if (message)
    html += `<slot name="error-${error.code}-message"><p>${message}</p></slot>`;
  return html;
}
const observedAttributes = [
  MediaUIAttributes.MEDIA_ERROR_CODE,
  MediaUIAttributes.MEDIA_ERROR_MESSAGE
];
class MediaErrorDialog extends MediaChromeDialog {
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
    return getNumericAttr(this, "mediaerrorcode");
  }
  set mediaErrorCode(value) {
    setNumericAttr(this, "mediaerrorcode", value);
  }
  get mediaErrorMessage() {
    return getStringAttr(this, "mediaerrormessage");
  }
  set mediaErrorMessage(value) {
    setStringAttr(this, "mediaerrormessage", value);
  }
}
_mediaError = new WeakMap();
MediaErrorDialog.getSlotTemplateHTML = getSlotTemplateHTML;
MediaErrorDialog.formatErrorMessage = formatErrorMessage;
if (!globalThis.customElements.get("media-error-dialog")) {
  globalThis.customElements.define("media-error-dialog", MediaErrorDialog);
}
var media_error_dialog_default = MediaErrorDialog;
export {
  MediaErrorDialog,
  media_error_dialog_default as default
};
