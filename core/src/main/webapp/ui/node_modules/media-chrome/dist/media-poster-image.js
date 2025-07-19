import { globalThis } from "./utils/server-safe-globals.js";
import { getStringAttr, namedNodeMapToObject, setStringAttr } from "./utils/element-utils.js";
const Attributes = {
  PLACEHOLDER_SRC: "placeholdersrc",
  SRC: "src"
};
function getTemplateHTML(_attrs) {
  return (
    /*html*/
    `
    <style>
      :host {
        pointer-events: none;
        display: var(--media-poster-image-display, inline-block);
        box-sizing: border-box;
      }

      img {
        max-width: 100%;
        max-height: 100%;
        min-width: 100%;
        min-height: 100%;
        background-repeat: no-repeat;
        background-position: var(--media-poster-image-background-position, var(--media-object-position, center));
        background-size: var(--media-poster-image-background-size, var(--media-object-fit, contain));
        object-fit: var(--media-object-fit, contain);
        object-position: var(--media-object-position, center);
      }
    </style>

    <img part="poster img" aria-hidden="true" id="image"/>
  `
  );
}
const unsetBackgroundImage = (el) => {
  el.style.removeProperty("background-image");
};
const setBackgroundImage = (el, image) => {
  el.style["background-image"] = `url('${image}')`;
};
class MediaPosterImage extends globalThis.HTMLElement {
  static get observedAttributes() {
    return [Attributes.PLACEHOLDER_SRC, Attributes.SRC];
  }
  constructor() {
    super();
    if (!this.shadowRoot) {
      this.attachShadow(this.constructor.shadowRootOptions);
      const attrs = namedNodeMapToObject(this.attributes);
      this.shadowRoot.innerHTML = this.constructor.getTemplateHTML(attrs);
    }
    this.image = this.shadowRoot.querySelector("#image");
  }
  attributeChangedCallback(attrName, oldValue, newValue) {
    if (attrName === Attributes.SRC) {
      if (newValue == null) {
        this.image.removeAttribute(Attributes.SRC);
      } else {
        this.image.setAttribute(Attributes.SRC, newValue);
      }
    }
    if (attrName === Attributes.PLACEHOLDER_SRC) {
      if (newValue == null) {
        unsetBackgroundImage(this.image);
      } else {
        setBackgroundImage(this.image, newValue);
      }
    }
  }
  /**
   *
   */
  get placeholderSrc() {
    return getStringAttr(this, Attributes.PLACEHOLDER_SRC);
  }
  set placeholderSrc(value) {
    setStringAttr(this, Attributes.SRC, value);
  }
  /**
   *
   */
  get src() {
    return getStringAttr(this, Attributes.SRC);
  }
  set src(value) {
    setStringAttr(this, Attributes.SRC, value);
  }
}
MediaPosterImage.shadowRootOptions = { mode: "open" };
MediaPosterImage.getTemplateHTML = getTemplateHTML;
if (!globalThis.customElements.get("media-poster-image")) {
  globalThis.customElements.define("media-poster-image", MediaPosterImage);
}
var media_poster_image_default = MediaPosterImage;
export {
  Attributes,
  media_poster_image_default as default
};
