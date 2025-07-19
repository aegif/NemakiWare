import { MediaChromeMenuButton } from './media-chrome-menu-button.js';
declare function getSlotTemplateHTML(): string;
declare function getTooltipContentHTML(): string;
/**
 * @attr {string} mediaaudiotrackenabled - (read-only) Set to the selected audio track id.
 * @attr {(unavailable|unsupported)} mediaaudiotrackunavailable - (read-only) Set if audio track selection is unavailable.
 *
 * @cssproperty [--media-audio-track-menu-button-display = inline-flex] - `display` property of button.
 */
declare class MediaAudioTrackMenuButton extends MediaChromeMenuButton {
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static getTooltipContentHTML: typeof getTooltipContentHTML;
    static get observedAttributes(): string[];
    connectedCallback(): void;
    /**
     * Returns the element with the id specified by the `invoketarget` attribute.
     * @return {HTMLElement | null}
     */
    get invokeTargetElement(): HTMLElement | null;
    /**
     * Get enabled audio track id.
     * @return {string}
     */
    get mediaAudioTrackEnabled(): string;
    set mediaAudioTrackEnabled(id: string);
}
export { MediaAudioTrackMenuButton };
export default MediaAudioTrackMenuButton;
