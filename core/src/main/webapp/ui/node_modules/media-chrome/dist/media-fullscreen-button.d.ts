import { MediaChromeButton } from './media-chrome-button.js';
declare function getSlotTemplateHTML(_attrs: Record<string, string>): string;
declare function getTooltipContentHTML(): string;
/**
 * @slot enter - An element shown when the media is not in fullscreen and pressing the button will trigger entering fullscreen.
 * @slot exit - An element shown when the media is in fullscreen and pressing the button will trigger exiting fullscreen.
 * @slot icon - An element for representing enter and exit states in a single icon
 *
 * @attr {(unavailable|unsupported)} mediafullscreenunavailable - (read-only) Set if fullscreen is unavailable.
 * @attr {boolean} mediaisfullscreen - (read-only) Present if the media is fullscreen.
 *
 * @cssproperty [--media-fullscreen-button-display = inline-flex] - `display` property of button.
 */
declare class MediaFullscreenButton extends MediaChromeButton {
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static getTooltipContentHTML: typeof getTooltipContentHTML;
    static get observedAttributes(): string[];
    connectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     * @type {string | undefined} Fullscreen unavailability state
     */
    get mediaFullscreenUnavailable(): string | undefined;
    set mediaFullscreenUnavailable(value: string | undefined);
    /**
     * @type {boolean} Whether fullscreen is available
     */
    get mediaIsFullscreen(): boolean;
    set mediaIsFullscreen(value: boolean);
    handleClick(): void;
}
export default MediaFullscreenButton;
