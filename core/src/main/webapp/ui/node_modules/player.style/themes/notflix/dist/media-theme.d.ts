import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-notflix': MediaThemeNotflixElement;
  }
}

declare class MediaThemeNotflixElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeNotflixElement;
