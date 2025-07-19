import { MediaChromeMenuButton } from './media-chrome-menu-button.js';
declare function getSlotTemplateHTML(): string;
declare function getTooltipContentHTML(): string;
/**
 * @attr {string} mediarenditionselected - (read-only) Set to the selected rendition id.
 * @attr {(unavailable|unsupported)} mediarenditionunavailable - (read-only) Set if rendition selection is unavailable.
 *
 * @cssproperty [--media-rendition-menu-button-display = inline-flex] - `display` property of button.
 */
declare class MediaRenditionMenuButton extends MediaChromeMenuButton {
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static getTooltipContentHTML: typeof getTooltipContentHTML;
    static get observedAttributes(): string[];
    connectedCallback(): void;
    /**
     * Returns the element with the id specified by the `invoketarget` attribute.
     */
    get invokeTargetElement(): HTMLElement | null;
    /**
     * Get selected rendition id.
     */
    get mediaRenditionSelected(): string;
    set mediaRenditionSelected(id: string);
    get mediaHeight(): number;
    set mediaHeight(height: number);
}
export { MediaRenditionMenuButton };
export default MediaRenditionMenuButton;
