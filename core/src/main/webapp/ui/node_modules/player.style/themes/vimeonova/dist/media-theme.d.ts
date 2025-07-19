import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-vimeonova': MediaThemeVimeonovaElement;
  }
}

declare class MediaThemeVimeonovaElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeVimeonovaElement;
