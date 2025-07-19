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
var element_utils_exports = {};
__export(element_utils_exports, {
  closestComposedNode: () => closestComposedNode,
  containsComposedNode: () => containsComposedNode,
  distance: () => distance,
  getActiveElement: () => getActiveElement,
  getAllSlotted: () => getAllSlotted,
  getAttributeMediaController: () => getAttributeMediaController,
  getBooleanAttr: () => getBooleanAttr,
  getCSSRule: () => getCSSRule,
  getDocumentOrShadowRoot: () => getDocumentOrShadowRoot,
  getMediaController: () => getMediaController,
  getNumericAttr: () => getNumericAttr,
  getOrInsertCSSRule: () => getOrInsertCSSRule,
  getPointProgressOnLine: () => getPointProgressOnLine,
  getSlotted: () => getSlotted,
  getStringAttr: () => getStringAttr,
  insertCSSRule: () => insertCSSRule,
  isElementVisible: () => isElementVisible,
  namedNodeMapToObject: () => namedNodeMapToObject,
  setBooleanAttr: () => setBooleanAttr,
  setNumericAttr: () => setNumericAttr,
  setStringAttr: () => setStringAttr,
  updateIconText: () => updateIconText
});
module.exports = __toCommonJS(element_utils_exports);
var import_constants = require("../constants.js");
function namedNodeMapToObject(namedNodeMap) {
  const obj = {};
  for (const attr of namedNodeMap) {
    obj[attr.name] = attr.value;
  }
  return obj;
}
function getMediaController(host) {
  var _a;
  return (_a = getAttributeMediaController(host)) != null ? _a : closestComposedNode(host, "media-controller");
}
function getAttributeMediaController(host) {
  var _a;
  const { MEDIA_CONTROLLER } = import_constants.MediaStateReceiverAttributes;
  const mediaControllerId = host.getAttribute(MEDIA_CONTROLLER);
  if (mediaControllerId) {
    return (_a = getDocumentOrShadowRoot(host)) == null ? void 0 : _a.getElementById(
      mediaControllerId
    );
  }
}
const updateIconText = (svg, value, selector = ".value") => {
  const node = svg.querySelector(selector);
  if (!node)
    return;
  node.textContent = value;
};
const getAllSlotted = (el, name) => {
  const slotSelector = `slot[name="${name}"]`;
  const slot = el.shadowRoot.querySelector(slotSelector);
  if (!slot)
    return [];
  return slot.children;
};
const getSlotted = (el, name) => getAllSlotted(el, name)[0];
const containsComposedNode = (rootNode, childNode) => {
  if (!rootNode || !childNode)
    return false;
  if (rootNode == null ? void 0 : rootNode.contains(childNode))
    return true;
  return containsComposedNode(
    rootNode,
    childNode.getRootNode().host
  );
};
const closestComposedNode = (childNode, selector) => {
  if (!childNode)
    return null;
  const closest = childNode.closest(selector);
  if (closest)
    return closest;
  return closestComposedNode(
    childNode.getRootNode().host,
    selector
  );
};
function getActiveElement(root = document) {
  var _a;
  const activeEl = root == null ? void 0 : root.activeElement;
  if (!activeEl)
    return null;
  return (_a = getActiveElement(activeEl.shadowRoot)) != null ? _a : activeEl;
}
function getDocumentOrShadowRoot(node) {
  var _a;
  const rootNode = (_a = node == null ? void 0 : node.getRootNode) == null ? void 0 : _a.call(node);
  if (rootNode instanceof ShadowRoot || rootNode instanceof Document) {
    return rootNode;
  }
  return null;
}
function isElementVisible(element, { depth = 3, checkOpacity = true, checkVisibilityCSS = true } = {}) {
  if (element.checkVisibility) {
    return element.checkVisibility({
      checkOpacity,
      checkVisibilityCSS
    });
  }
  let el = element;
  while (el && depth > 0) {
    const style = getComputedStyle(el);
    if (checkOpacity && style.opacity === "0" || checkVisibilityCSS && style.visibility === "hidden" || style.display === "none") {
      return false;
    }
    el = el.parentElement;
    depth--;
  }
  return true;
}
function getPointProgressOnLine(x, y, p1, p2) {
  const dx = p2.x - p1.x;
  const dy = p2.y - p1.y;
  const lengthSquared = dx * dx + dy * dy;
  if (lengthSquared === 0)
    return 0;
  const projection = ((x - p1.x) * dx + (y - p1.y) * dy) / lengthSquared;
  return Math.max(0, Math.min(1, projection));
}
function distance(p1, p2) {
  return Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
}
function getOrInsertCSSRule(styleParent, selectorText) {
  const cssRule = getCSSRule(styleParent, (st) => st === selectorText);
  if (cssRule)
    return cssRule;
  return insertCSSRule(styleParent, selectorText);
}
function getCSSRule(styleParent, predicate) {
  var _a, _b;
  let style;
  for (style of (_a = styleParent.querySelectorAll("style:not([media])")) != null ? _a : []) {
    let cssRules;
    try {
      cssRules = (_b = style.sheet) == null ? void 0 : _b.cssRules;
    } catch {
      continue;
    }
    for (const rule of cssRules != null ? cssRules : []) {
      if (predicate(rule.selectorText))
        return rule;
    }
  }
}
function insertCSSRule(styleParent, selectorText) {
  var _a, _b;
  const styles = (_a = styleParent.querySelectorAll("style:not([media])")) != null ? _a : [];
  const style = styles == null ? void 0 : styles[styles.length - 1];
  if (!(style == null ? void 0 : style.sheet)) {
    console.warn(
      "Media Chrome: No style sheet found on style tag of",
      styleParent
    );
    return {
      // @ts-ignore
      style: {
        setProperty: () => {
        },
        removeProperty: () => "",
        getPropertyValue: () => ""
      }
    };
  }
  style == null ? void 0 : style.sheet.insertRule(`${selectorText}{}`, style.sheet.cssRules.length);
  return (
    /** @type {CSSStyleRule} */
    (_b = style.sheet.cssRules) == null ? void 0 : _b[style.sheet.cssRules.length - 1]
  );
}
function getNumericAttr(el, attrName, defaultValue = Number.NaN) {
  const attrVal = el.getAttribute(attrName);
  return attrVal != null ? +attrVal : defaultValue;
}
function setNumericAttr(el, attrName, value) {
  const nextNumericValue = +value;
  if (value == null || Number.isNaN(nextNumericValue)) {
    if (el.hasAttribute(attrName)) {
      el.removeAttribute(attrName);
    }
    return;
  }
  if (getNumericAttr(el, attrName, void 0) === nextNumericValue)
    return;
  el.setAttribute(attrName, `${nextNumericValue}`);
}
function getBooleanAttr(el, attrName) {
  return el.hasAttribute(attrName);
}
function setBooleanAttr(el, attrName, value) {
  if (value == null) {
    if (el.hasAttribute(attrName)) {
      el.removeAttribute(attrName);
    }
    return;
  }
  if (getBooleanAttr(el, attrName) == value)
    return;
  el.toggleAttribute(attrName, value);
}
function getStringAttr(el, attrName, defaultValue = null) {
  var _a;
  return (_a = el.getAttribute(attrName)) != null ? _a : defaultValue;
}
function setStringAttr(el, attrName, value) {
  if (value == null) {
    if (el.hasAttribute(attrName)) {
      el.removeAttribute(attrName);
    }
    return;
  }
  const nextValue = `${value}`;
  if (getStringAttr(el, attrName, void 0) === nextValue)
    return;
  el.setAttribute(attrName, nextValue);
}
