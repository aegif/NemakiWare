import { MediaChromeButton } from './media-chrome-button.js';
declare function getSlotTemplateHTML(_attrs: Record<string, string>): string;
declare function getTooltipContentHTML(): string;
/**
 * @slot enter - An element shown when the media is not in casting mode and pressing the button will open the Cast menu.
 * @slot exit - An element shown when the media is in casting mode and pressing the button will open the Cast menu.
 * @slot icon - An element for representing enter and exit states in a single icon
 *
 * @attr {(unavailable|unsupported)} mediacastunavailable - (read-only) Set if casting is unavailable.
 * @attr {boolean} mediaiscasting - (read-only) Present if the media is casting.
 *
 * @cssproperty [--media-cast-button-display = inline-flex] - `display` property of button.
 */
declare class MediaCastButton extends MediaChromeButton {
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static getTooltipContentHTML: typeof getTooltipContentHTML;
    static get observedAttributes(): string[];
    connectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     * @type {boolean} Are we currently casting
     */
    get mediaIsCasting(): boolean;
    set mediaIsCasting(value: boolean);
    /**
     * @type {string | undefined} Cast unavailability state
     */
    get mediaCastUnavailable(): string | undefined;
    set mediaCastUnavailable(value: string | undefined);
    handleClick(): void;
}
export default MediaCastButton;
