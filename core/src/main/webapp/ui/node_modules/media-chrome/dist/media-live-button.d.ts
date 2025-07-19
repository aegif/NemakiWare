import { MediaChromeButton } from './media-chrome-button.js';
declare function getSlotTemplateHTML(_attrs: Record<string, string>): string;
/**
 * @slot indicator - The default is an SVG of a circle that changes to red when the video or audio is live. Can be replaced with your own SVG or font icon.
 * @slot spacer - A simple text space (&nbsp;) between the indicator and the text.
 * @slot text - The text content of the button, with a default of “LIVE”.
 *
 * @attr {boolean} mediapaused - (read-only) Present if the media is paused.
 * @attr {boolean} mediatimeislive - (read-only) Present if the media playback is live.
 *
 * @cssproperty [--media-live-button-display = inline-flex] - `display` property of button.
 * @cssproperty --media-live-button-icon-color - `fill` and `color` of not live button icon.
 * @cssproperty --media-live-button-indicator-color - `fill` and `color` of live button icon.
 */
declare class MediaLiveButton extends MediaChromeButton {
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static get observedAttributes(): string[];
    connectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     * @type {boolean} Is the media paused
     */
    get mediaPaused(): boolean;
    set mediaPaused(value: boolean);
    /**
     * @type {boolean} Is the media playback currently live
     */
    get mediaTimeIsLive(): boolean;
    set mediaTimeIsLive(value: boolean);
    handleClick(): void;
}
export default MediaLiveButton;
