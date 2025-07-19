import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-sutro': MediaThemeSutroElement;
  }
}

declare class MediaThemeSutroElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeSutroElement;
