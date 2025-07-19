import { globalThis } from "./server-safe-globals.js";
const callbacksMap = /* @__PURE__ */ new WeakMap();
const getCallbacks = (element) => {
  let callbacks = callbacksMap.get(element);
  if (!callbacks)
    callbacksMap.set(element, callbacks = /* @__PURE__ */ new Set());
  return callbacks;
};
const observer = new globalThis.ResizeObserver(
  (entries) => {
    for (const entry of entries) {
      for (const callback of getCallbacks(entry.target)) {
        callback(entry);
      }
    }
  }
);
function observeResize(element, callback) {
  getCallbacks(element).add(callback);
  observer.observe(element);
}
function unobserveResize(element, callback) {
  const callbacks = getCallbacks(element);
  callbacks.delete(callback);
  if (!callbacks.size) {
    observer.unobserve(element);
  }
}
export {
  observeResize,
  unobserveResize
};
