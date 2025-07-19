import { AttributeTokenList } from '../utils/attribute-token-list.js';
import { MediaChromeMenu } from './media-chrome-menu.js';
export declare const Attributes: {
    RATES: string;
};
/**
 * @extends {MediaChromeMenu}
 *
 * @slot - Default slotted elements.
 * @slot header - An element shown at the top of the menu.
 * @slot checked-indicator - An icon element indicating a checked menu-item.
 *
 * @attr {string} rates - Set custom playback rates for the user to choose from.
 * @attr {string} mediaplaybackrate - (read-only) Set to the media playback rate.
 */
declare class MediaPlaybackRateMenu extends MediaChromeMenu {
    #private;
    static get observedAttributes(): string[];
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    /**
     * Returns the anchor element when it is a floating menu.
     */
    get anchorElement(): HTMLElement;
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
     * The current playback rate
     */
    get mediaPlaybackRate(): number;
    set mediaPlaybackRate(value: number);
}
export { MediaPlaybackRateMenu };
export default MediaPlaybackRateMenu;
