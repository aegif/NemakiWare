import {
  closestComposedNode,
  getMediaController,
  getStringAttr,
  isElementVisible,
  namedNodeMapToObject,
  setStringAttr
} from "./utils/element-utils.js";
import { globalThis } from "./utils/server-safe-globals.js";
const Attributes = {
  PLACEMENT: "placement",
  BOUNDS: "bounds"
};
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host {
        --_tooltip-background-color: var(--media-tooltip-background-color, var(--media-secondary-color, rgba(20, 20, 30, .7)));
        --_tooltip-background: var(--media-tooltip-background, var(--_tooltip-background-color));
        --_tooltip-arrow-half-width: calc(var(--media-tooltip-arrow-width, 12px) / 2);
        --_tooltip-arrow-height: var(--media-tooltip-arrow-height, 5px);
        --_tooltip-arrow-background: var(--media-tooltip-arrow-color, var(--_tooltip-background-color));
        position: relative;
        pointer-events: none;
        display: var(--media-tooltip-display, inline-flex);
        justify-content: center;
        align-items: center;
        box-sizing: border-box;
        z-index: var(--media-tooltip-z-index, 1);
        background: var(--_tooltip-background);
        color: var(--media-text-color, var(--media-primary-color, rgb(238 238 238)));
        font: var(--media-font,
          var(--media-font-weight, 400)
          var(--media-font-size, 13px) /
          var(--media-text-content-height, var(--media-control-height, 18px))
          var(--media-font-family, helvetica neue, segoe ui, roboto, arial, sans-serif));
        padding: var(--media-tooltip-padding, .35em .7em);
        border: var(--media-tooltip-border, none);
        border-radius: var(--media-tooltip-border-radius, 5px);
        filter: var(--media-tooltip-filter, drop-shadow(0 0 4px rgba(0, 0, 0, .2)));
        white-space: var(--media-tooltip-white-space, nowrap);
      }

      :host([hidden]) {
        display: none;
      }

      img, svg {
        display: inline-block;
      }

      #arrow {
        position: absolute;
        width: 0px;
        height: 0px;
        border-style: solid;
        display: var(--media-tooltip-arrow-display, block);
      }

      :host(:not([placement])),
      :host([placement="top"]) {
        position: absolute;
        bottom: calc(100% + var(--media-tooltip-distance, 12px));
        left: 50%;
        transform: translate(calc(-50% - var(--media-tooltip-offset-x, 0px)), 0);
      }
      :host(:not([placement])) #arrow,
      :host([placement="top"]) #arrow {
        top: 100%;
        left: 50%;
        border-width: var(--_tooltip-arrow-height) var(--_tooltip-arrow-half-width) 0 var(--_tooltip-arrow-half-width);
        border-color: var(--_tooltip-arrow-background) transparent transparent transparent;
        transform: translate(calc(-50% + var(--media-tooltip-offset-x, 0px)), 0);
      }

      :host([placement="right"]) {
        position: absolute;
        left: calc(100% + var(--media-tooltip-distance, 12px));
        top: 50%;
        transform: translate(0, -50%);
      }
      :host([placement="right"]) #arrow {
        top: 50%;
        right: 100%;
        border-width: var(--_tooltip-arrow-half-width) var(--_tooltip-arrow-height) var(--_tooltip-arrow-half-width) 0;
        border-color: transparent var(--_tooltip-arrow-background) transparent transparent;
        transform: translate(0, -50%);
      }

      :host([placement="bottom"]) {
        position: absolute;
        top: calc(100% + var(--media-tooltip-distance, 12px));
        left: 50%;
        transform: translate(calc(-50% - var(--media-tooltip-offset-x, 0px)), 0);
      }
      :host([placement="bottom"]) #arrow {
        bottom: 100%;
        left: 50%;
        border-width: 0 var(--_tooltip-arrow-half-width) var(--_tooltip-arrow-height) var(--_tooltip-arrow-half-width);
        border-color: transparent transparent var(--_tooltip-arrow-background) transparent;
        transform: translate(calc(-50% + var(--media-tooltip-offset-x, 0px)), 0);
      }

      :host([placement="left"]) {
        position: absolute;
        right: calc(100% + var(--media-tooltip-distance, 12px));
        top: 50%;
        transform: translate(0, -50%);
      }
      :host([placement="left"]) #arrow {
        top: 50%;
        left: 100%;
        border-width: var(--_tooltip-arrow-half-width) 0 var(--_tooltip-arrow-half-width) var(--_tooltip-arrow-height);
        border-color: transparent transparent transparent var(--_tooltip-arrow-background);
        transform: translate(0, -50%);
      }
      
      :host([placement="none"]) #arrow {
        display: none;
      }
    </style>
    <slot></slot>
    <div id="arrow"></div>
  `
  );
}
class MediaTooltip extends globalThis.HTMLElement {
  constructor() {
    super();
    // Adjusts tooltip position relative to the closest specified container
    // such that it doesn't spill out of the left or right sides. Only applies
    // to 'top' and 'bottom' placed tooltips.
    this.updateXOffset = () => {
      var _a;
      if (!isElementVisible(this, { checkOpacity: false, checkVisibilityCSS: false }))
        return;
      const placement = this.placement;
      if (placement === "left" || placement === "right") {
        this.style.removeProperty("--media-tooltip-offset-x");
        return;
      }
      const tooltipStyle = getComputedStyle(this);
      const containingEl = (_a = closestComposedNode(this, "#" + this.bounds)) != null ? _a : getMediaController(this);
      if (!containingEl)
        return;
      const { x: containerX, width: containerWidth } = containingEl.getBoundingClientRect();
      const { x: tooltipX, width: tooltipWidth } = this.getBoundingClientRect();
      const tooltipRight = tooltipX + tooltipWidth;
      const containerRight = containerX + containerWidth;
      const offsetXVal = tooltipStyle.getPropertyValue(
        "--media-tooltip-offset-x"
      );
      const currOffsetX = offsetXVal ? parseFloat(offsetXVal.replace("px", "")) : 0;
      const marginVal = tooltipStyle.getPropertyValue(
        "--media-tooltip-container-margin"
      );
      const currMargin = marginVal ? parseFloat(marginVal.replace("px", "")) : 0;
      const leftDiff = tooltipX - containerX + currOffsetX - currMargin;
      const rightDiff = tooltipRight - containerRight + currOffsetX + currMargin;
      if (leftDiff < 0) {
        this.style.setProperty("--media-tooltip-offset-x", `${leftDiff}px`);
        return;
      }
      if (rightDiff > 0) {
        this.style.setProperty("--media-tooltip-offset-x", `${rightDiff}px`);
        return;
      }
      this.style.removeProperty("--media-tooltip-offset-x");
    };
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = namedNodeMapToObject(this.attributes);
      this.shadowRoot.innerHTML = this.constructor.getTemplateHTML(attrs);
    }
    this.arrowEl = this.shadowRoot.querySelector("#arrow");
    if (Object.prototype.hasOwnProperty.call(this, "placement")) {
      const placement = this.placement;
      delete this.placement;
      this.placement = placement;
    }
  }
  static get observedAttributes() {
    return [Attributes.PLACEMENT, Attributes.BOUNDS];
  }
  /**
   * Get or set tooltip placement
   */
  get placement() {
    return getStringAttr(this, Attributes.PLACEMENT);
  }
  set placement(value) {
    setStringAttr(this, Attributes.PLACEMENT, value);
  }
  /**
   * Get or set tooltip container ID selector that will constrain the tooltips
   * horizontal position.
   */
  get bounds() {
    return getStringAttr(this, Attributes.BOUNDS);
  }
  set bounds(value) {
    setStringAttr(this, Attributes.BOUNDS, value);
  }
}
MediaTooltip.shadowRootOptions = { mode: "open" };
MediaTooltip.getTemplateHTML = getTemplateHTML;
if (!globalThis.customElements.get("media-tooltip")) {
  globalThis.customElements.define("media-tooltip", MediaTooltip);
}
var media_tooltip_default = MediaTooltip;
export {
  Attributes,
  media_tooltip_default as default
};
