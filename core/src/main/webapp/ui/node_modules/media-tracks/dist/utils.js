const privateProps = /* @__PURE__ */ new WeakMap();
function getPrivate(instance) {
  return privateProps.get(instance) ?? setPrivate(instance, {});
}
function setPrivate(instance, props) {
  let saved = privateProps.get(instance);
  if (!saved) privateProps.set(instance, saved = {});
  return Object.assign(saved, props);
}
export {
  getPrivate,
  setPrivate
};
