import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-sutro-audio': MediaThemeSutroAudioElement;
  }
}

declare class MediaThemeSutroAudioElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeSutroAudioElement;
