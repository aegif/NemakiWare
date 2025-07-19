var __create = Object.create;
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __getProtoOf = Object.getPrototypeOf;
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
var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
  // If the importer is in node compatibility mode or this is not an ESM
  // file that has been converted to a CommonJS file using a Babel-
  // compatible transform (i.e. "__esModule" has not been set), then set
  // "default" to the CommonJS "module.exports" for node compatibility.
  isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
  mod
));
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
var media_time_range_exports = {};
__export(media_time_range_exports, {
  default: () => media_time_range_default
});
module.exports = __toCommonJS(media_time_range_exports);
var import_server_safe_globals = require("./utils/server-safe-globals.js");
var import_media_chrome_range = require("./media-chrome-range.js");
var import_media_preview_thumbnail = require("./media-preview-thumbnail.js");
var import_media_preview_time_display = require("./media-preview-time-display.js");
var import_media_preview_chapter_display = require("./media-preview-chapter-display.js");
var import_constants = require("./constants.js");
var import_utils = require("./utils/utils.js");
var import_time = require("./utils/time.js");
var import_element_utils = require("./utils/element-utils.js");
var import_range_animation = require("./utils/range-animation.js");
var import_element_utils2 = require("./utils/element-utils.js");
var import_i18n = require("./utils/i18n.js");
var import_media_preview_thumbnail2 = __toESM(require("./media-preview-thumbnail.js"), 1);
var _rootNode, _animation, _boxes, _previewTime, _previewBox, _currentBox, _boxPaddingLeft, _boxPaddingRight, _mediaChaptersCues, _toggleRangeAnimation, toggleRangeAnimation_fn, _shouldRangeAnimate, shouldRangeAnimate_fn, _updateRange, _getElementRects, getElementRects_fn, _getBoxPosition, getBoxPosition_fn, _getBoxShiftPosition, getBoxShiftPosition_fn, _handlePointerMove, handlePointerMove_fn, _previewRequest, previewRequest_fn, _seekRequest, seekRequest_fn;
const DEFAULT_MISSING_TIME_PHRASE = "video not loaded, unknown time.";
const updateAriaValueText = (el) => {
  const range = el.range;
  const currentTimePhrase = (0, import_time.formatAsTimePhrase)(+calcTimeFromRangeValue(el));
  const totalTimePhrase = (0, import_time.formatAsTimePhrase)(+el.mediaSeekableEnd);
  const fullPhrase = !(currentTimePhrase && totalTimePhrase) ? DEFAULT_MISSING_TIME_PHRASE : `${currentTimePhrase} of ${totalTimePhrase}`;
  range.setAttribute("aria-valuetext", fullPhrase);
};
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    ${import_media_chrome_range.MediaChromeRange.getTemplateHTML(_attrs)}
    <style>
      :host {
        --media-box-border-radius: 4px;
        --media-box-padding-left: 10px;
        --media-box-padding-right: 10px;
        --media-preview-border-radius: var(--media-box-border-radius);
        --media-box-arrow-offset: var(--media-box-border-radius);
        --_control-background: var(--media-control-background, var(--media-secondary-color, rgb(20 20 30 / .7)));
        --_preview-background: var(--media-preview-background, var(--_control-background));

        ${/* 1% rail width trick was off in Safari, contain: layout seems to
    prevent the horizontal overflow as well. */
    ""}
        contain: layout;
      }

      #buffered {
        background: var(--media-time-range-buffered-color, rgb(255 255 255 / .4));
        position: absolute;
        height: 100%;
        will-change: width;
      }

      #preview-rail,
      #current-rail {
        width: 100%;
        position: absolute;
        left: 0;
        bottom: 100%;
        pointer-events: none;
        will-change: transform;
      }

      [part~="box"] {
        width: min-content;
        ${/* absolute position is needed here so the box doesn't overflow the bounds */
    ""}
        position: absolute;
        bottom: 100%;
        flex-direction: column;
        align-items: center;
        transform: translateX(-50%);
      }

      [part~="current-box"] {
        display: var(--media-current-box-display, var(--media-box-display, flex));
        margin: var(--media-current-box-margin, var(--media-box-margin, 0 0 5px));
        visibility: hidden;
      }

      [part~="preview-box"] {
        display: var(--media-preview-box-display, var(--media-box-display, flex));
        margin: var(--media-preview-box-margin, var(--media-box-margin, 0 0 5px));
        transition-property: var(--media-preview-transition-property, visibility, opacity);
        transition-duration: var(--media-preview-transition-duration-out, .25s);
        transition-delay: var(--media-preview-transition-delay-out, 0s);
        visibility: hidden;
        opacity: 0;
      }

      :host(:is([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}], [${import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME}])[dragging]) [part~="preview-box"] {
        transition-duration: var(--media-preview-transition-duration-in, .5s);
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        visibility: visible;
        opacity: 1;
      }

      @media (hover: hover) {
        :host(:is([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}], [${import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME}]):hover) [part~="preview-box"] {
          transition-duration: var(--media-preview-transition-duration-in, .5s);
          transition-delay: var(--media-preview-transition-delay-in, .25s);
          visibility: visible;
          opacity: 1;
        }
      }

      media-preview-thumbnail,
      ::slotted(media-preview-thumbnail) {
        visibility: hidden;
        ${/* delay changing these CSS props until the preview box transition is ended */
    ""}
        transition: visibility 0s .25s;
        transition-delay: calc(var(--media-preview-transition-delay-out, 0s) + var(--media-preview-transition-duration-out, .25s));
        background: var(--media-preview-thumbnail-background, var(--_preview-background));
        box-shadow: var(--media-preview-thumbnail-box-shadow, 0 0 4px rgb(0 0 0 / .2));
        max-width: var(--media-preview-thumbnail-max-width, 180px);
        max-height: var(--media-preview-thumbnail-max-height, 160px);
        min-width: var(--media-preview-thumbnail-min-width, 120px);
        min-height: var(--media-preview-thumbnail-min-height, 80px);
        border: var(--media-preview-thumbnail-border);
        border-radius: var(--media-preview-thumbnail-border-radius,
          var(--media-preview-border-radius) var(--media-preview-border-radius) 0 0);
      }

      :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}][dragging]) media-preview-thumbnail,
      :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}][dragging]) ::slotted(media-preview-thumbnail) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        visibility: visible;
      }

      @media (hover: hover) {
        :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}]:hover) media-preview-thumbnail,
        :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}]:hover) ::slotted(media-preview-thumbnail) {
          transition-delay: var(--media-preview-transition-delay-in, .25s);
          visibility: visible;
        }

        :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME}]:hover) {
          --media-time-range-hover-display: block;
        }
      }

      media-preview-chapter-display,
      ::slotted(media-preview-chapter-display) {
        font-size: var(--media-font-size, 13px);
        line-height: 17px;
        min-width: 0;
        visibility: hidden;
        ${/* delay changing these CSS props until the preview box transition is ended */
    ""}
        transition: min-width 0s, border-radius 0s, margin 0s, padding 0s, visibility 0s;
        transition-delay: calc(var(--media-preview-transition-delay-out, 0s) + var(--media-preview-transition-duration-out, .25s));
        background: var(--media-preview-chapter-background, var(--_preview-background));
        border-radius: var(--media-preview-chapter-border-radius,
          var(--media-preview-border-radius) var(--media-preview-border-radius)
          var(--media-preview-border-radius) var(--media-preview-border-radius));
        padding: var(--media-preview-chapter-padding, 3.5px 9px);
        margin: var(--media-preview-chapter-margin, 0 0 5px);
        text-shadow: var(--media-preview-chapter-text-shadow, 0 0 4px rgb(0 0 0 / .75));
      }

      :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}]) media-preview-chapter-display,
      :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}]) ::slotted(media-preview-chapter-display) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        border-radius: var(--media-preview-chapter-border-radius, 0);
        padding: var(--media-preview-chapter-padding, 3.5px 9px 0);
        margin: var(--media-preview-chapter-margin, 0);
        min-width: 100%;
      }

      media-preview-chapter-display[${import_constants.MediaUIAttributes.MEDIA_PREVIEW_CHAPTER}],
      ::slotted(media-preview-chapter-display[${import_constants.MediaUIAttributes.MEDIA_PREVIEW_CHAPTER}]) {
        visibility: visible;
      }

      media-preview-chapter-display:not([aria-valuetext]),
      ::slotted(media-preview-chapter-display:not([aria-valuetext])) {
        display: none;
      }

      media-preview-time-display,
      ::slotted(media-preview-time-display),
      media-time-display,
      ::slotted(media-time-display) {
        font-size: var(--media-font-size, 13px);
        line-height: 17px;
        min-width: 0;
        ${/* delay changing these CSS props until the preview box transition is ended */
    ""}
        transition: min-width 0s, border-radius 0s;
        transition-delay: calc(var(--media-preview-transition-delay-out, 0s) + var(--media-preview-transition-duration-out, .25s));
        background: var(--media-preview-time-background, var(--_preview-background));
        border-radius: var(--media-preview-time-border-radius,
          var(--media-preview-border-radius) var(--media-preview-border-radius)
          var(--media-preview-border-radius) var(--media-preview-border-radius));
        padding: var(--media-preview-time-padding, 3.5px 9px);
        margin: var(--media-preview-time-margin, 0);
        text-shadow: var(--media-preview-time-text-shadow, 0 0 4px rgb(0 0 0 / .75));
        transform: translateX(min(
          max(calc(50% - var(--_box-width) / 2),
          calc(var(--_box-shift, 0))),
          calc(var(--_box-width) / 2 - 50%)
        ));
      }

      :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}]) media-preview-time-display,
      :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE}]) ::slotted(media-preview-time-display) {
        transition-delay: var(--media-preview-transition-delay-in, .25s);
        border-radius: var(--media-preview-time-border-radius,
          0 0 var(--media-preview-border-radius) var(--media-preview-border-radius));
        min-width: 100%;
      }

      :host([${import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME}]:hover) {
        --media-time-range-hover-display: block;
      }

      [part~="arrow"],
      ::slotted([part~="arrow"]) {
        display: var(--media-box-arrow-display, inline-block);
        transform: translateX(min(
          max(calc(50% - var(--_box-width) / 2 + var(--media-box-arrow-offset)),
          calc(var(--_box-shift, 0))),
          calc(var(--_box-width) / 2 - 50% - var(--media-box-arrow-offset))
        ));
        ${/* border-color has to come before border-top-color! */
    ""}
        border-color: transparent;
        border-top-color: var(--media-box-arrow-background, var(--_control-background));
        border-width: var(--media-box-arrow-border-width,
          var(--media-box-arrow-height, 5px) var(--media-box-arrow-width, 6px) 0);
        border-style: solid;
        justify-content: center;
        height: 0;
      }
    </style>
    <div id="preview-rail">
      <slot name="preview" part="box preview-box">
        <media-preview-thumbnail>
          <template shadowrootmode="${import_media_preview_thumbnail2.default.shadowRootOptions.mode}">
            ${import_media_preview_thumbnail2.default.getTemplateHTML({})}
          </template>
        </media-preview-thumbnail>
        <media-preview-chapter-display></media-preview-chapter-display>
        <media-preview-time-display></media-preview-time-display>
        <slot name="preview-arrow"><div part="arrow"></div></slot>
      </slot>
    </div>
    <div id="current-rail">
      <slot name="current" part="box current-box">
        ${/* Example: add the current time w/ arrow to the playhead
    <media-time-display slot="current"></media-time-display>
    <div part="arrow" slot="current"></div> */
    ""}
      </slot>
    </div>
  `
  );
}
const calcRangeValueFromTime = (el, time = el.mediaCurrentTime) => {
  const startTime = Number.isFinite(el.mediaSeekableStart) ? el.mediaSeekableStart : 0;
  const endTime = Number.isFinite(el.mediaDuration) ? el.mediaDuration : el.mediaSeekableEnd;
  if (Number.isNaN(endTime))
    return 0;
  const value = (time - startTime) / (endTime - startTime);
  return Math.max(0, Math.min(value, 1));
};
const calcTimeFromRangeValue = (el, value = el.range.valueAsNumber) => {
  const startTime = Number.isFinite(el.mediaSeekableStart) ? el.mediaSeekableStart : 0;
  const endTime = Number.isFinite(el.mediaDuration) ? el.mediaDuration : el.mediaSeekableEnd;
  if (Number.isNaN(endTime))
    return 0;
  return value * (endTime - startTime) + startTime;
};
class MediaTimeRange extends import_media_chrome_range.MediaChromeRange {
  constructor() {
    super();
    __privateAdd(this, _toggleRangeAnimation);
    __privateAdd(this, _shouldRangeAnimate);
    __privateAdd(this, _getElementRects);
    /**
     * Get the position, max and min for the box in percentage.
     * It's important this is in percentage so when the player is resized
     * the box will move accordingly.
     */
    __privateAdd(this, _getBoxPosition);
    __privateAdd(this, _getBoxShiftPosition);
    __privateAdd(this, _handlePointerMove);
    __privateAdd(this, _previewRequest);
    __privateAdd(this, _seekRequest);
    __privateAdd(this, _rootNode, void 0);
    __privateAdd(this, _animation, void 0);
    __privateAdd(this, _boxes, void 0);
    __privateAdd(this, _previewTime, void 0);
    __privateAdd(this, _previewBox, void 0);
    __privateAdd(this, _currentBox, void 0);
    __privateAdd(this, _boxPaddingLeft, void 0);
    __privateAdd(this, _boxPaddingRight, void 0);
    __privateAdd(this, _mediaChaptersCues, void 0);
    __privateAdd(this, _updateRange, (value) => {
      if (this.dragging)
        return;
      if ((0, import_utils.isValidNumber)(value)) {
        this.range.valueAsNumber = value;
      }
      this.updateBar();
    });
    const track = this.shadowRoot.querySelector("#track");
    track.insertAdjacentHTML(
      "afterbegin",
      '<div id="buffered" part="buffered"></div>'
    );
    __privateSet(this, _boxes, this.shadowRoot.querySelectorAll('[part~="box"]'));
    __privateSet(this, _previewBox, this.shadowRoot.querySelector('[part~="preview-box"]'));
    __privateSet(this, _currentBox, this.shadowRoot.querySelector('[part~="current-box"]'));
    const computedStyle = getComputedStyle(this);
    __privateSet(this, _boxPaddingLeft, parseInt(
      computedStyle.getPropertyValue("--media-box-padding-left")
    ));
    __privateSet(this, _boxPaddingRight, parseInt(
      computedStyle.getPropertyValue("--media-box-padding-right")
    ));
    __privateSet(this, _animation, new import_range_animation.RangeAnimation(this.range, __privateGet(this, _updateRange), 60));
  }
  static get observedAttributes() {
    return [
      ...super.observedAttributes,
      import_constants.MediaUIAttributes.MEDIA_PAUSED,
      import_constants.MediaUIAttributes.MEDIA_DURATION,
      import_constants.MediaUIAttributes.MEDIA_SEEKABLE,
      import_constants.MediaUIAttributes.MEDIA_CURRENT_TIME,
      import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE,
      import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME,
      import_constants.MediaUIAttributes.MEDIA_PREVIEW_CHAPTER,
      import_constants.MediaUIAttributes.MEDIA_BUFFERED,
      import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE,
      import_constants.MediaUIAttributes.MEDIA_LOADING,
      import_constants.MediaUIAttributes.MEDIA_ENDED
    ];
  }
  connectedCallback() {
    var _a;
    super.connectedCallback();
    this.range.setAttribute("aria-label", (0, import_i18n.t)("seek"));
    __privateMethod(this, _toggleRangeAnimation, toggleRangeAnimation_fn).call(this);
    __privateSet(this, _rootNode, this.getRootNode());
    (_a = __privateGet(this, _rootNode)) == null ? void 0 : _a.addEventListener("transitionstart", this);
  }
  disconnectedCallback() {
    var _a;
    super.disconnectedCallback();
    __privateMethod(this, _toggleRangeAnimation, toggleRangeAnimation_fn).call(this);
    (_a = __privateGet(this, _rootNode)) == null ? void 0 : _a.removeEventListener("transitionstart", this);
    __privateSet(this, _rootNode, null);
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    super.attributeChangedCallback(attrName, oldValue, newValue);
    if (oldValue == newValue)
      return;
    if (attrName === import_constants.MediaUIAttributes.MEDIA_CURRENT_TIME || attrName === import_constants.MediaUIAttributes.MEDIA_PAUSED || attrName === import_constants.MediaUIAttributes.MEDIA_ENDED || attrName === import_constants.MediaUIAttributes.MEDIA_LOADING || attrName === import_constants.MediaUIAttributes.MEDIA_DURATION || attrName === import_constants.MediaUIAttributes.MEDIA_SEEKABLE) {
      __privateGet(this, _animation).update({
        start: calcRangeValueFromTime(this),
        duration: this.mediaSeekableEnd - this.mediaSeekableStart,
        playbackRate: this.mediaPlaybackRate
      });
      __privateMethod(this, _toggleRangeAnimation, toggleRangeAnimation_fn).call(this);
      updateAriaValueText(this);
    } else if (attrName === import_constants.MediaUIAttributes.MEDIA_BUFFERED) {
      this.updateBufferedBar();
    }
    if (attrName === import_constants.MediaUIAttributes.MEDIA_DURATION || attrName === import_constants.MediaUIAttributes.MEDIA_SEEKABLE) {
      this.mediaChaptersCues = __privateGet(this, _mediaChaptersCues);
      this.updateBar();
    }
  }
  get mediaChaptersCues() {
    return __privateGet(this, _mediaChaptersCues);
  }
  set mediaChaptersCues(value) {
    var _a;
    __privateSet(this, _mediaChaptersCues, value);
    this.updateSegments(
      (_a = __privateGet(this, _mediaChaptersCues)) == null ? void 0 : _a.map((c) => ({
        start: calcRangeValueFromTime(this, c.startTime),
        end: calcRangeValueFromTime(this, c.endTime)
      }))
    );
  }
  /**
   * Is the media paused
   */
  get mediaPaused() {
    return (0, import_element_utils2.getBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_PAUSED);
  }
  set mediaPaused(value) {
    (0, import_element_utils2.setBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_PAUSED, value);
  }
  /**
   * Is the media loading
   */
  get mediaLoading() {
    return (0, import_element_utils2.getBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_LOADING);
  }
  set mediaLoading(value) {
    (0, import_element_utils2.setBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_LOADING, value);
  }
  /**
   *
   */
  get mediaDuration() {
    return (0, import_element_utils2.getNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_DURATION);
  }
  set mediaDuration(value) {
    (0, import_element_utils2.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_DURATION, value);
  }
  /**
   *
   */
  get mediaCurrentTime() {
    return (0, import_element_utils2.getNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_CURRENT_TIME);
  }
  set mediaCurrentTime(value) {
    (0, import_element_utils2.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_CURRENT_TIME, value);
  }
  /**
   *
   */
  get mediaPlaybackRate() {
    return (0, import_element_utils2.getNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE, 1);
  }
  set mediaPlaybackRate(value) {
    (0, import_element_utils2.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_PLAYBACK_RATE, value);
  }
  /**
   * An array of ranges, each range being an array of two numbers.
   * e.g. [[1, 2], [3, 4]]
   */
  get mediaBuffered() {
    const buffered = this.getAttribute(import_constants.MediaUIAttributes.MEDIA_BUFFERED);
    if (!buffered)
      return [];
    return buffered.split(" ").map((timePair) => timePair.split(":").map((timeStr) => +timeStr));
  }
  set mediaBuffered(list) {
    if (!list) {
      this.removeAttribute(import_constants.MediaUIAttributes.MEDIA_BUFFERED);
      return;
    }
    const strVal = list.map((tuple) => tuple.join(":")).join(" ");
    this.setAttribute(import_constants.MediaUIAttributes.MEDIA_BUFFERED, strVal);
  }
  /**
   * Range of values that can be seeked to
   * An array of two numbers [start, end]
   */
  get mediaSeekable() {
    const seekable = this.getAttribute(import_constants.MediaUIAttributes.MEDIA_SEEKABLE);
    if (!seekable)
      return void 0;
    return seekable.split(":").map((time) => +time);
  }
  set mediaSeekable(range) {
    if (range == null) {
      this.removeAttribute(import_constants.MediaUIAttributes.MEDIA_SEEKABLE);
      return;
    }
    this.setAttribute(import_constants.MediaUIAttributes.MEDIA_SEEKABLE, range.join(":"));
  }
  /**
   *
   */
  get mediaSeekableEnd() {
    var _a;
    const [, end = this.mediaDuration] = (_a = this.mediaSeekable) != null ? _a : [];
    return end;
  }
  get mediaSeekableStart() {
    var _a;
    const [start = 0] = (_a = this.mediaSeekable) != null ? _a : [];
    return start;
  }
  /**
   * The url of the preview image
   */
  get mediaPreviewImage() {
    return (0, import_element_utils2.getStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE);
  }
  set mediaPreviewImage(value) {
    (0, import_element_utils2.setStringAttr)(this, import_constants.MediaUIAttributes.MEDIA_PREVIEW_IMAGE, value);
  }
  /**
   *
   */
  get mediaPreviewTime() {
    return (0, import_element_utils2.getNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME);
  }
  set mediaPreviewTime(value) {
    (0, import_element_utils2.setNumericAttr)(this, import_constants.MediaUIAttributes.MEDIA_PREVIEW_TIME, value);
  }
  /**
   *
   */
  get mediaEnded() {
    return (0, import_element_utils2.getBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_ENDED);
  }
  set mediaEnded(value) {
    (0, import_element_utils2.setBooleanAttr)(this, import_constants.MediaUIAttributes.MEDIA_ENDED, value);
  }
  /* Add a buffered progress bar */
  updateBar() {
    super.updateBar();
    this.updateBufferedBar();
    this.updateCurrentBox();
  }
  updateBufferedBar() {
    var _a;
    const buffered = this.mediaBuffered;
    if (!buffered.length) {
      return;
    }
    let relativeBufferedEnd;
    if (!this.mediaEnded) {
      const currentTime = this.mediaCurrentTime;
      const [, bufferedEnd = this.mediaSeekableStart] = (_a = buffered.find(
        ([start, end]) => start <= currentTime && currentTime <= end
      )) != null ? _a : [];
      relativeBufferedEnd = calcRangeValueFromTime(this, bufferedEnd);
    } else {
      relativeBufferedEnd = 1;
    }
    const { style } = (0, import_element_utils2.getOrInsertCSSRule)(this.shadowRoot, "#buffered");
    style.setProperty("width", `${relativeBufferedEnd * 100}%`);
  }
  updateCurrentBox() {
    const currentSlot = this.shadowRoot.querySelector(
      'slot[name="current"]'
    );
    if (!currentSlot.assignedElements().length)
      return;
    const currentRailRule = (0, import_element_utils2.getOrInsertCSSRule)(
      this.shadowRoot,
      "#current-rail"
    );
    const currentBoxRule = (0, import_element_utils2.getOrInsertCSSRule)(
      this.shadowRoot,
      '[part~="current-box"]'
    );
    const rects = __privateMethod(this, _getElementRects, getElementRects_fn).call(this, __privateGet(this, _currentBox));
    const boxPos = __privateMethod(this, _getBoxPosition, getBoxPosition_fn).call(this, rects, this.range.valueAsNumber);
    const boxShift = __privateMethod(this, _getBoxShiftPosition, getBoxShiftPosition_fn).call(this, rects, this.range.valueAsNumber);
    currentRailRule.style.transform = `translateX(${boxPos})`;
    currentRailRule.style.setProperty("--_range-width", `${rects.range.width}`);
    currentBoxRule.style.setProperty("--_box-shift", `${boxShift}`);
    currentBoxRule.style.setProperty("--_box-width", `${rects.box.width}px`);
    currentBoxRule.style.setProperty("visibility", "initial");
  }
  handleEvent(evt) {
    super.handleEvent(evt);
    switch (evt.type) {
      case "input":
        __privateMethod(this, _seekRequest, seekRequest_fn).call(this);
        break;
      case "pointermove":
        __privateMethod(this, _handlePointerMove, handlePointerMove_fn).call(this, evt);
        break;
      case "pointerup":
      case "pointerleave":
        __privateMethod(this, _previewRequest, previewRequest_fn).call(this, null);
        break;
      case "transitionstart":
        if ((0, import_element_utils2.containsComposedNode)(evt.target, this)) {
          setTimeout(() => __privateMethod(this, _toggleRangeAnimation, toggleRangeAnimation_fn).call(this), 0);
        }
        break;
    }
  }
}
_rootNode = new WeakMap();
_animation = new WeakMap();
_boxes = new WeakMap();
_previewTime = new WeakMap();
_previewBox = new WeakMap();
_currentBox = new WeakMap();
_boxPaddingLeft = new WeakMap();
_boxPaddingRight = new WeakMap();
_mediaChaptersCues = new WeakMap();
_toggleRangeAnimation = new WeakSet();
toggleRangeAnimation_fn = function() {
  if (__privateMethod(this, _shouldRangeAnimate, shouldRangeAnimate_fn).call(this)) {
    __privateGet(this, _animation).start();
  } else {
    __privateGet(this, _animation).stop();
  }
};
_shouldRangeAnimate = new WeakSet();
shouldRangeAnimate_fn = function() {
  return this.isConnected && !this.mediaPaused && !this.mediaLoading && !this.mediaEnded && this.mediaSeekableEnd > 0 && (0, import_element_utils.isElementVisible)(this);
};
_updateRange = new WeakMap();
_getElementRects = new WeakSet();
getElementRects_fn = function(box) {
  var _a;
  const bounds = (_a = this.getAttribute("bounds") ? (0, import_element_utils2.closestComposedNode)(this, `#${this.getAttribute("bounds")}`) : this.parentElement) != null ? _a : this;
  const boundsRect = bounds.getBoundingClientRect();
  const rangeRect = this.range.getBoundingClientRect();
  const width = box.offsetWidth;
  const min = -(rangeRect.left - boundsRect.left - width / 2);
  const max = boundsRect.right - rangeRect.left - width / 2;
  return {
    box: { width, min, max },
    bounds: boundsRect,
    range: rangeRect
  };
};
_getBoxPosition = new WeakSet();
getBoxPosition_fn = function(rects, ratio) {
  let position = `${ratio * 100}%`;
  const { width, min, max } = rects.box;
  if (!width)
    return position;
  if (!Number.isNaN(min)) {
    const pad = `var(--media-box-padding-left)`;
    const minPos = `calc(1 / var(--_range-width) * 100 * ${min}% + ${pad})`;
    position = `max(${minPos}, ${position})`;
  }
  if (!Number.isNaN(max)) {
    const pad = `var(--media-box-padding-right)`;
    const maxPos = `calc(1 / var(--_range-width) * 100 * ${max}% - ${pad})`;
    position = `min(${position}, ${maxPos})`;
  }
  return position;
};
_getBoxShiftPosition = new WeakSet();
getBoxShiftPosition_fn = function(rects, ratio) {
  const { width, min, max } = rects.box;
  const pointerX = ratio * rects.range.width;
  if (pointerX < min + __privateGet(this, _boxPaddingLeft)) {
    const offset = rects.range.left - rects.bounds.left - __privateGet(this, _boxPaddingLeft);
    return `${pointerX - width / 2 + offset}px`;
  }
  if (pointerX > max - __privateGet(this, _boxPaddingRight)) {
    const offset = rects.bounds.right - rects.range.right - __privateGet(this, _boxPaddingRight);
    return `${pointerX + width / 2 - offset - rects.range.width}px`;
  }
  return 0;
};
_handlePointerMove = new WeakSet();
handlePointerMove_fn = function(evt) {
  const isOverBoxes = [...__privateGet(this, _boxes)].some(
    (b) => evt.composedPath().includes(b)
  );
  if (!this.dragging && (isOverBoxes || !evt.composedPath().includes(this))) {
    __privateMethod(this, _previewRequest, previewRequest_fn).call(this, null);
    return;
  }
  const duration = this.mediaSeekableEnd;
  if (!duration)
    return;
  const previewRailRule = (0, import_element_utils2.getOrInsertCSSRule)(
    this.shadowRoot,
    "#preview-rail"
  );
  const previewBoxRule = (0, import_element_utils2.getOrInsertCSSRule)(
    this.shadowRoot,
    '[part~="preview-box"]'
  );
  const rects = __privateMethod(this, _getElementRects, getElementRects_fn).call(this, __privateGet(this, _previewBox));
  let pointerRatio = (evt.clientX - rects.range.left) / rects.range.width;
  pointerRatio = Math.max(0, Math.min(1, pointerRatio));
  const boxPos = __privateMethod(this, _getBoxPosition, getBoxPosition_fn).call(this, rects, pointerRatio);
  const boxShift = __privateMethod(this, _getBoxShiftPosition, getBoxShiftPosition_fn).call(this, rects, pointerRatio);
  previewRailRule.style.transform = `translateX(${boxPos})`;
  previewRailRule.style.setProperty("--_range-width", `${rects.range.width}`);
  previewBoxRule.style.setProperty("--_box-shift", `${boxShift}`);
  previewBoxRule.style.setProperty("--_box-width", `${rects.box.width}px`);
  const diff = Math.round(__privateGet(this, _previewTime)) - Math.round(pointerRatio * duration);
  if (Math.abs(diff) < 1 && pointerRatio > 0.01 && pointerRatio < 0.99)
    return;
  __privateSet(this, _previewTime, pointerRatio * duration);
  __privateMethod(this, _previewRequest, previewRequest_fn).call(this, __privateGet(this, _previewTime));
};
_previewRequest = new WeakSet();
previewRequest_fn = function(detail) {
  this.dispatchEvent(
    new import_server_safe_globals.globalThis.CustomEvent(import_constants.MediaUIEvents.MEDIA_PREVIEW_REQUEST, {
      composed: true,
      bubbles: true,
      detail
    })
  );
};
_seekRequest = new WeakSet();
seekRequest_fn = function() {
  __privateGet(this, _animation).stop();
  const detail = calcTimeFromRangeValue(this);
  this.dispatchEvent(
    new import_server_safe_globals.globalThis.CustomEvent(import_constants.MediaUIEvents.MEDIA_SEEK_REQUEST, {
      composed: true,
      bubbles: true,
      detail
    })
  );
};
MediaTimeRange.shadowRootOptions = { mode: "open" };
MediaTimeRange.getTemplateHTML = getTemplateHTML;
if (!import_server_safe_globals.globalThis.customElements.get("media-time-range")) {
  import_server_safe_globals.globalThis.customElements.define("media-time-range", MediaTimeRange);
}
var media_time_range_default = MediaTimeRange;
