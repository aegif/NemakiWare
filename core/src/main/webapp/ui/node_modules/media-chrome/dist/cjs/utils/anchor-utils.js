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
var anchor_utils_exports = {};
__export(anchor_utils_exports, {
  computePosition: () => computePosition
});
module.exports = __toCommonJS(anchor_utils_exports);
function computePosition({
  anchor,
  floating,
  placement
}) {
  const rects = getElementRects({ anchor, floating });
  const { x, y } = computeCoordsFromPlacement(rects, placement);
  return { x, y };
}
function getElementRects({
  anchor,
  floating
}) {
  return {
    anchor: getRectRelativeToOffsetParent(anchor, floating.offsetParent),
    floating: {
      x: 0,
      y: 0,
      width: floating.offsetWidth,
      height: floating.offsetHeight
    }
  };
}
function getRectRelativeToOffsetParent(element, offsetParent) {
  var _a;
  const rect = element.getBoundingClientRect();
  const offsetRect = (_a = offsetParent == null ? void 0 : offsetParent.getBoundingClientRect()) != null ? _a : { x: 0, y: 0 };
  return {
    x: rect.x - offsetRect.x,
    y: rect.y - offsetRect.y,
    width: rect.width,
    height: rect.height
  };
}
function computeCoordsFromPlacement({ anchor, floating }, placement) {
  const alignmentAxis = getSideAxis(placement) === "x" ? "y" : "x";
  const alignLength = alignmentAxis === "y" ? "height" : "width";
  const side = getSide(placement);
  const commonX = anchor.x + anchor.width / 2 - floating.width / 2;
  const commonY = anchor.y + anchor.height / 2 - floating.height / 2;
  const commonAlign = anchor[alignLength] / 2 - floating[alignLength] / 2;
  let coords;
  switch (side) {
    case "top":
      coords = { x: commonX, y: anchor.y - floating.height };
      break;
    case "bottom":
      coords = { x: commonX, y: anchor.y + anchor.height };
      break;
    case "right":
      coords = { x: anchor.x + anchor.width, y: commonY };
      break;
    case "left":
      coords = { x: anchor.x - floating.width, y: commonY };
      break;
    default:
      coords = { x: anchor.x, y: anchor.y };
  }
  switch (placement.split("-")[1]) {
    case "start":
      coords[alignmentAxis] -= commonAlign;
      break;
    case "end":
      coords[alignmentAxis] += commonAlign;
      break;
  }
  return coords;
}
function getSide(placement) {
  return placement.split("-")[0];
}
function getSideAxis(placement) {
  return ["top", "bottom"].includes(getSide(placement)) ? "y" : "x";
}
