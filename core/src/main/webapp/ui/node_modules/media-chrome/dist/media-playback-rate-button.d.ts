import { MediaChromeButton } from './media-chrome-button.js';
import { AttributeTokenList } from './utils/attribute-token-list.js';
export declare const Attributes: {
    RATES: string;
};
export declare const DEFAULT_RATES: number[];
export declare const DEFAULT_RATE = 1;
declare function getSlotTemplateHTML(attrs: Record<string, string>): string;
declare function getTooltipContentHTML(): string;
/**
 * @attr {string} rates - Set custom playback rates for the user to choose from.
 * @attr {string} mediaplaybackrate - (read-only) Set to the media playback rate.
 *
 * @cssproperty [--media-playback-rate-button-display = inline-flex] - `display` property of button.
 */
declare class MediaPlaybackRateButton extends MediaChromeButton {
    #private;
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static getTooltipContentHTML: typeof getTooltipContentHTML;
    static get observedAttributes(): string[];
    container: HTMLSlotElement;
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     * Get the playback rates for the button.
     */
    get rates(): AttributeTokenList | ArrayLike<number> | null | undefined;
    /**
     * Set the playback rates for the button.
     * For React 19+ compatibility, accept a string of space-separated rates.
     */
    set rates(value: ArrayLike<number> | string | null | undefined);
    /**
     * @type {number} The current playback rate
     */
    get mediaPlaybackRate(): number;
    set mediaPlaybackRate(value: number);
    handleClick(): void;
}
export default MediaPlaybackRateButton;
