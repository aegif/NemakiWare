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
var utils_exports = {};
__export(utils_exports, {
  toNativeAttrName: () => toNativeAttrName,
  toNativeAttrValue: () => toNativeAttrValue,
  toNativeProps: () => toNativeProps
});
module.exports = __toCommonJS(utils_exports);
const ReactPropToAttrNameMap = {
  className: "class",
  classname: "class",
  htmlFor: "for",
  crossOrigin: "crossorigin",
  viewBox: "viewBox"
};
const toNativeAttrName = (propName, propValue) => {
  if (ReactPropToAttrNameMap[propName])
    return ReactPropToAttrNameMap[propName];
  if (typeof propValue == void 0)
    return void 0;
  if (typeof propValue === "boolean" && !propValue)
    return void 0;
  if (/[A-Z]/.test(propName))
    return propName.toLowerCase();
  return propName;
};
const toNativeAttrValue = (propValue, _propName) => {
  if (typeof propValue === "boolean")
    return "";
  if (Array.isArray(propValue))
    return propValue.join(" ");
  return propValue;
};
const toNativeProps = (props = {}) => {
  return Object.entries(props).reduce(
    (transformedProps, [propName, propValue]) => {
      const attrName = toNativeAttrName(propName, propValue);
      if (!attrName) {
        return transformedProps;
      }
      const attrValue = toNativeAttrValue(propValue, propName);
      transformedProps[attrName] = attrValue;
      return transformedProps;
    },
    {}
  );
};
