import { CustomVideoElement } from 'custom-media-element';
import Hls, { HlsConfig } from 'hls.js';
export { default as Hls } from 'hls.js';

type Constructor<T> = {
  new (...args: any[]): T;
};

declare function HlsVideoMixin<T extends Constructor<HTMLElement>>(superclass: T): HlsVideoElement;

declare class HlsVideoElement extends CustomVideoElement {
  /**
   * The current instance of the hls.js library.
   *
   * @example
   * ```js
   * const video = document.querySelector('hls-video');
   * video.api.on(Hls.Events.MANIFEST_PARSED, () => {});
   * ```
   */
  api: Hls | null;

  /**
   * The configuration object for the hls.js instance.
   */
  config: Partial<HlsConfig> | null;

  /**
   * Fires when attributes are changed on the custom element.
   */
  attributeChangedCallback(attrName: string, oldValue: any, newValue: any): void;

  /**
   * Unloads the HLS.js instance and detaches it from the video element.
   */
  #destroy(): void;
}

export { HlsVideoElement, HlsVideoMixin, HlsVideoElement as default };
