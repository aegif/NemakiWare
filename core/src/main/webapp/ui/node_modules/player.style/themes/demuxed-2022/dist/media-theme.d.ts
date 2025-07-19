import { MediaThemeElement } from 'media-chrome/dist/media-theme-element.js';

declare global {
  interface HTMLElementTagNameMap {
   'media-theme-demuxed-2022': MediaThemeDemuxed2022Element;
  }
}

declare class MediaThemeDemuxed2022Element extends MediaThemeElement {
  static template: HTMLTemplateElement;
}

export default MediaThemeDemuxed2022Element;
