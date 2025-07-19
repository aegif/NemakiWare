import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-instaplay': MediaThemeInstaplayElement;
  }
}

declare class MediaThemeInstaplayElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeInstaplayElement;
