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
var template_parts_exports = {};
__export(template_parts_exports, {
  AttrPart: () => AttrPart,
  AttrPartList: () => AttrPartList,
  ChildNodePart: () => ChildNodePart,
  InnerTemplatePart: () => InnerTemplatePart,
  Part: () => Part,
  TemplateInstance: () => TemplateInstance,
  defaultProcessor: () => defaultProcessor,
  parse: () => parse,
  tokenize: () => tokenize
});
module.exports = __toCommonJS(template_parts_exports);
var import_server_safe_globals = require("../utils/server-safe-globals.js");
var _parts, _processor, _items, _value, _element, _attributeName, _namespaceURI, _list, list_get, _parentNode, _nodes;
const ELEMENT = 1;
const STRING = 0;
const PART = 1;
const defaultProcessor = {
  processCallback(instance, parts, state) {
    if (!state)
      return;
    for (const [expression, part] of parts) {
      if (expression in state) {
        const value = state[expression];
        if (typeof value === "boolean" && part instanceof AttrPart && typeof part.element[part.attributeName] === "boolean") {
          part.booleanValue = value;
        } else if (typeof value === "function" && part instanceof AttrPart) {
          part.element[part.attributeName] = value;
        } else {
          part.value = value;
        }
      }
    }
  }
};
class TemplateInstance extends import_server_safe_globals.globalThis.DocumentFragment {
  constructor(template, state, processor = defaultProcessor) {
    var _a;
    super();
    __privateAdd(this, _parts, void 0);
    __privateAdd(this, _processor, void 0);
    this.append(template.content.cloneNode(true));
    __privateSet(this, _parts, parse(this));
    __privateSet(this, _processor, processor);
    (_a = processor.createCallback) == null ? void 0 : _a.call(processor, this, __privateGet(this, _parts), state);
    processor.processCallback(this, __privateGet(this, _parts), state);
  }
  update(state) {
    __privateGet(this, _processor).processCallback(this, __privateGet(this, _parts), state);
  }
}
_parts = new WeakMap();
_processor = new WeakMap();
const parse = (element, parts = []) => {
  let type, value;
  for (const attr of element.attributes || []) {
    if (attr.value.includes("{{")) {
      const list = new AttrPartList();
      for ([type, value] of tokenize(attr.value)) {
        if (!type)
          list.append(value);
        else {
          const part = new AttrPart(element, attr.name, attr.namespaceURI);
          list.append(part);
          parts.push([value, part]);
        }
      }
      attr.value = list.toString();
    }
  }
  for (const node of element.childNodes) {
    if (node.nodeType === ELEMENT && !(node instanceof HTMLTemplateElement)) {
      parse(node, parts);
    } else {
      const data = node.data;
      if (node.nodeType === ELEMENT || data.includes("{{")) {
        const items = [];
        if (data) {
          for ([type, value] of tokenize(data))
            if (!type)
              items.push(new Text(value));
            else {
              const part = new ChildNodePart(element);
              items.push(part);
              parts.push([value, part]);
            }
        } else if (node instanceof HTMLTemplateElement) {
          const part = new InnerTemplatePart(element, node);
          items.push(part);
          parts.push([part.expression, part]);
        }
        node.replaceWith(
          ...items.flatMap((part) => part.replacementNodes || [part])
        );
      }
    }
  }
  return parts;
};
const mem = {};
const tokenize = (text) => {
  let value = "", open = 0, tokens = mem[text], i = 0, c;
  if (tokens)
    return tokens;
  else
    tokens = [];
  for (; c = text[i]; i++) {
    if (c === "{" && text[i + 1] === "{" && text[i - 1] !== "\\" && text[i + 2] && ++open == 1) {
      if (value)
        tokens.push([STRING, value]);
      value = "";
      i++;
    } else if (c === "}" && text[i + 1] === "}" && text[i - 1] !== "\\" && !--open) {
      tokens.push([PART, value.trim()]);
      value = "";
      i++;
    } else
      value += c || "";
  }
  if (value)
    tokens.push([STRING, (open > 0 ? "{{" : "") + value]);
  return mem[text] = tokens;
};
const FRAGMENT = 11;
class Part {
  get value() {
    return "";
  }
  set value(val) {
  }
  toString() {
    return this.value;
  }
}
const attrPartToList = /* @__PURE__ */ new WeakMap();
class AttrPartList {
  constructor() {
    __privateAdd(this, _items, []);
  }
  [Symbol.iterator]() {
    return __privateGet(this, _items).values();
  }
  get length() {
    return __privateGet(this, _items).length;
  }
  item(index) {
    return __privateGet(this, _items)[index];
  }
  append(...items) {
    for (const item of items) {
      if (item instanceof AttrPart) {
        attrPartToList.set(item, this);
      }
      __privateGet(this, _items).push(item);
    }
  }
  toString() {
    return __privateGet(this, _items).join("");
  }
}
_items = new WeakMap();
class AttrPart extends Part {
  constructor(element, attributeName, namespaceURI) {
    super();
    __privateAdd(this, _list);
    __privateAdd(this, _value, "");
    __privateAdd(this, _element, void 0);
    __privateAdd(this, _attributeName, void 0);
    __privateAdd(this, _namespaceURI, void 0);
    __privateSet(this, _element, element);
    __privateSet(this, _attributeName, attributeName);
    __privateSet(this, _namespaceURI, namespaceURI);
  }
  get attributeName() {
    return __privateGet(this, _attributeName);
  }
  get attributeNamespace() {
    return __privateGet(this, _namespaceURI);
  }
  get element() {
    return __privateGet(this, _element);
  }
  get value() {
    return __privateGet(this, _value);
  }
  set value(newValue) {
    if (__privateGet(this, _value) === newValue)
      return;
    __privateSet(this, _value, newValue);
    if (!__privateGet(this, _list, list_get) || __privateGet(this, _list, list_get).length === 1) {
      if (newValue == null) {
        __privateGet(this, _element).removeAttributeNS(
          __privateGet(this, _namespaceURI),
          __privateGet(this, _attributeName)
        );
      } else {
        __privateGet(this, _element).setAttributeNS(
          __privateGet(this, _namespaceURI),
          __privateGet(this, _attributeName),
          newValue
        );
      }
    } else {
      __privateGet(this, _element).setAttributeNS(
        __privateGet(this, _namespaceURI),
        __privateGet(this, _attributeName),
        __privateGet(this, _list, list_get).toString()
      );
    }
  }
  get booleanValue() {
    return __privateGet(this, _element).hasAttributeNS(
      __privateGet(this, _namespaceURI),
      __privateGet(this, _attributeName)
    );
  }
  set booleanValue(value) {
    if (!__privateGet(this, _list, list_get) || __privateGet(this, _list, list_get).length === 1)
      this.value = value ? "" : null;
    else
      throw new DOMException("Value is not fully templatized");
  }
}
_value = new WeakMap();
_element = new WeakMap();
_attributeName = new WeakMap();
_namespaceURI = new WeakMap();
_list = new WeakSet();
list_get = function() {
  return attrPartToList.get(this);
};
class ChildNodePart extends Part {
  constructor(parentNode, nodes) {
    super();
    __privateAdd(this, _parentNode, void 0);
    __privateAdd(this, _nodes, void 0);
    __privateSet(this, _parentNode, parentNode);
    __privateSet(this, _nodes, nodes ? [...nodes] : [new Text()]);
  }
  get replacementNodes() {
    return __privateGet(this, _nodes);
  }
  get parentNode() {
    return __privateGet(this, _parentNode);
  }
  get nextSibling() {
    return __privateGet(this, _nodes)[__privateGet(this, _nodes).length - 1].nextSibling;
  }
  get previousSibling() {
    return __privateGet(this, _nodes)[0].previousSibling;
  }
  // FIXME: not sure why do we need string serialization here? Just because parent class has type DOMString?
  get value() {
    return __privateGet(this, _nodes).map((node) => node.textContent).join("");
  }
  set value(newValue) {
    this.replace(newValue);
  }
  replace(...nodes) {
    const normalisedNodes = nodes.flat().flatMap(
      (node) => node == null ? [new Text()] : node.forEach ? [...node] : node.nodeType === FRAGMENT ? [...node.childNodes] : node.nodeType ? [node] : [new Text(node)]
    );
    if (!normalisedNodes.length)
      normalisedNodes.push(new Text());
    __privateSet(this, _nodes, swapdom(
      __privateGet(this, _nodes)[0].parentNode,
      __privateGet(this, _nodes),
      normalisedNodes,
      this.nextSibling
    ));
  }
}
_parentNode = new WeakMap();
_nodes = new WeakMap();
class InnerTemplatePart extends ChildNodePart {
  constructor(parentNode, template) {
    const directive = template.getAttribute("directive") || template.getAttribute("type");
    let expression = template.getAttribute("expression") || template.getAttribute(directive) || "";
    if (expression.startsWith("{{"))
      expression = expression.trim().slice(2, -2).trim();
    super(parentNode);
    this.expression = expression;
    this.template = template;
    this.directive = directive;
  }
}
function swapdom(parent, a, b, end = null) {
  let i = 0, cur, next, bi, n = b.length, m = a.length;
  while (i < n && i < m && a[i] == b[i])
    i++;
  while (i < n && i < m && b[n - 1] == a[m - 1])
    end = b[--m, --n];
  if (i == m)
    while (i < n)
      parent.insertBefore(b[i++], end);
  if (i == n)
    while (i < m)
      parent.removeChild(a[i++]);
  else {
    cur = a[i];
    while (i < n) {
      bi = b[i++], next = cur ? cur.nextSibling : end;
      if (cur == bi)
        cur = next;
      else if (i < n && b[i] == next)
        parent.replaceChild(bi, cur), cur = next;
      else
        parent.insertBefore(bi, cur);
    }
    while (cur != end)
      next = cur.nextSibling, parent.removeChild(cur), cur = next;
  }
  return b;
}
