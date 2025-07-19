import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-reelplay': MediaThemeReelplayElement;
  }
}

declare class MediaThemeReelplayElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeReelplayElement;
