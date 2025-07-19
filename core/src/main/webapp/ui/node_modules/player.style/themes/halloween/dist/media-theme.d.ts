import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-halloween': MediaThemeHalloweenElement;
  }
}

declare class MediaThemeHalloweenElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeHalloweenElement;
