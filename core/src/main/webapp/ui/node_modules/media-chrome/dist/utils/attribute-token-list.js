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
var _el, _attr, _defaultSet, _tokenSet, _tokens, tokens_get;
class AttributeTokenList {
  constructor(el, attr, { defaultValue } = { defaultValue: void 0 }) {
    __privateAdd(this, _tokens);
    __privateAdd(this, _el, void 0);
    __privateAdd(this, _attr, void 0);
    __privateAdd(this, _defaultSet, void 0);
    __privateAdd(this, _tokenSet, /* @__PURE__ */ new Set());
    __privateSet(this, _el, el);
    __privateSet(this, _attr, attr);
    __privateSet(this, _defaultSet, new Set(defaultValue));
  }
  [Symbol.iterator]() {
    return __privateGet(this, _tokens, tokens_get).values();
  }
  get length() {
    return __privateGet(this, _tokens, tokens_get).size;
  }
  get value() {
    var _a;
    return (_a = [...__privateGet(this, _tokens, tokens_get)].join(" ")) != null ? _a : "";
  }
  set value(val) {
    var _a;
    if (val === this.value)
      return;
    __privateSet(this, _tokenSet, /* @__PURE__ */ new Set());
    this.add(...(_a = val == null ? void 0 : val.split(" ")) != null ? _a : []);
  }
  toString() {
    return this.value;
  }
  item(index) {
    return [...__privateGet(this, _tokens, tokens_get)][index];
  }
  values() {
    return __privateGet(this, _tokens, tokens_get).values();
  }
  forEach(callback, thisArg) {
    __privateGet(this, _tokens, tokens_get).forEach(callback, thisArg);
  }
  add(...tokens) {
    var _a, _b;
    tokens.forEach((t) => __privateGet(this, _tokenSet).add(t));
    if (this.value === "" && !((_a = __privateGet(this, _el)) == null ? void 0 : _a.hasAttribute(`${__privateGet(this, _attr)}`))) {
      return;
    }
    (_b = __privateGet(this, _el)) == null ? void 0 : _b.setAttribute(`${__privateGet(this, _attr)}`, `${this.value}`);
  }
  remove(...tokens) {
    var _a;
    tokens.forEach((t) => __privateGet(this, _tokenSet).delete(t));
    (_a = __privateGet(this, _el)) == null ? void 0 : _a.setAttribute(`${__privateGet(this, _attr)}`, `${this.value}`);
  }
  contains(token) {
    return __privateGet(this, _tokens, tokens_get).has(token);
  }
  toggle(token, force) {
    if (typeof force !== "undefined") {
      if (force) {
        this.add(token);
        return true;
      } else {
        this.remove(token);
        return false;
      }
    }
    if (this.contains(token)) {
      this.remove(token);
      return false;
    }
    this.add(token);
    return true;
  }
  replace(oldToken, newToken) {
    this.remove(oldToken);
    this.add(newToken);
    return oldToken === newToken;
  }
}
_el = new WeakMap();
_attr = new WeakMap();
_defaultSet = new WeakMap();
_tokenSet = new WeakMap();
_tokens = new WeakSet();
tokens_get = function() {
  return __privateGet(this, _tokenSet).size ? __privateGet(this, _tokenSet) : __privateGet(this, _defaultSet);
};
export {
  AttributeTokenList
};
