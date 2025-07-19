import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-yt': MediaThemeYtElement;
  }
}

declare class MediaThemeYtElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeYtElement;
