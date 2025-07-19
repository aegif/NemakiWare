import React from "react";
import { AUDIO_EXTENSIONS } from "./patterns.js";
const HtmlPlayer = React.forwardRef((props, ref) => {
  const Media = AUDIO_EXTENSIONS.test(`${props.src}`) ? "audio" : "video";
  return /* @__PURE__ */ React.createElement(Media, { ...props, ref }, props.children);
});
var HtmlPlayer_default = HtmlPlayer;
export {
  HtmlPlayer_default as default
};
