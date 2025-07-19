import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-winamp': MediaThemeWinampElement;
  }
}

declare class MediaThemeWinampElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeWinampElement;
