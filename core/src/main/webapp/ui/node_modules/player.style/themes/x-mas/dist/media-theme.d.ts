import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-x-mas': MediaThemeXMasElement;
  }
}

declare class MediaThemeXMasElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeXMasElement;
