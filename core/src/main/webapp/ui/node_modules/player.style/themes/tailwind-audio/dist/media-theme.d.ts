import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-tailwind-audio': MediaThemeTailwindAudioElement;
  }
}

declare class MediaThemeTailwindAudioElement extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeTailwindAudioElement;
