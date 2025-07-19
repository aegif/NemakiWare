import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-minimal': MediaThemeMinimalElement;
  }
}

declare class MediaThemeMinimalElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeMinimalElement;
