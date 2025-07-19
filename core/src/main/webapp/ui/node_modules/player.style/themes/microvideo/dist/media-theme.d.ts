import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-microvideo': MediaThemeMicrovideoElement;
  }
}

declare class MediaThemeMicrovideoElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeMicrovideoElement;
