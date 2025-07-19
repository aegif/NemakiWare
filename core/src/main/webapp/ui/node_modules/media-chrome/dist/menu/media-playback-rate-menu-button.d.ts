import { MediaChromeMenuButton } from './media-chrome-menu-button.js';
export declare const DEFAULT_RATE = 1;
declare function getSlotTemplateHTML(attrs: Record<string, string>): string;
declare function getTooltipContentHTML(): string;
/**
 * @attr {string} mediaplaybackrate - (read-only) Set to the media playback rate.
 *
 * @cssproperty [--media-playback-rate-menu-button-display = inline-flex] - `display` property of button.
 */
declare class MediaPlaybackRateMenuButton extends MediaChromeMenuButton {
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static getTooltipContentHTML: typeof getTooltipContentHTML;
    static get observedAttributes(): string[];
    container: HTMLSlotElement;
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     * Returns the element with the id specified by the `invoketarget` attribute.
     */
    get invokeTargetElement(): HTMLElement | null;
    /**
     * The current playback rate
     */
    get mediaPlaybackRate(): number;
    set mediaPlaybackRate(value: number);
}
export { MediaPlaybackRateMenuButton };
export default MediaPlaybackRateMenuButton;
