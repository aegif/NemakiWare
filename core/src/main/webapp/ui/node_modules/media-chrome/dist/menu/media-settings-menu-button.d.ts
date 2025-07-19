import { MediaChromeMenuButton } from './media-chrome-menu-button.js';
declare function getSlotTemplateHTML(): string;
declare function getTooltipContentHTML(): string;
/**
 * @attr {string} target - CSS id selector for the element to be targeted by the button.
 */
declare class MediaSettingsMenuButton extends MediaChromeMenuButton {
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static getTooltipContentHTML: typeof getTooltipContentHTML;
    static get observedAttributes(): string[];
    connectedCallback(): void;
    /**
     * Returns the element with the id specified by the `invoketarget` attribute.
     * @return {HTMLElement | null}
     */
    get invokeTargetElement(): HTMLElement | null;
}
export { MediaSettingsMenuButton };
export default MediaSettingsMenuButton;
