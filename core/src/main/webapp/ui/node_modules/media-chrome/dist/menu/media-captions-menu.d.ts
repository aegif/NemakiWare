import { MediaChromeMenu } from './media-chrome-menu.js';
import { TextTrackLike } from '../utils/TextTrackLike.js';
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 * @extends {MediaChromeMenu}
 *
 * @slot - Default slotted elements.
 * @slot header - An element shown at the top of the menu.
 * @slot checked-indicator - An icon element indicating a checked menu-item.
 * @slot captions-indicator - An icon element indicating an item with closed captions.
 *
 * @attr {string} mediasubtitleslist - (read-only) A list of all subtitles and captions.
 * @attr {boolean} mediasubtitlesshowing - (read-only) A list of the showing subtitles and captions.
 */
declare class MediaCaptionsMenu extends MediaChromeMenu {
    #private;
    static getTemplateHTML: typeof getTemplateHTML;
    static get observedAttributes(): string[];
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    /**
     * Returns the anchor element when it is a floating menu.
     */
    get anchorElement(): HTMLElement;
    /**
     * @type {Array<object>} An array of TextTrack-like objects.
     * Objects must have the properties: kind, language, and label.
     */
    get mediaSubtitlesList(): TextTrackLike[];
    set mediaSubtitlesList(list: TextTrackLike[]);
    /**
     * An array of TextTrack-like objects.
     * Objects must have the properties: kind, language, and label.
     */
    get mediaSubtitlesShowing(): TextTrackLike[];
    set mediaSubtitlesShowing(list: TextTrackLike[]);
}
export { MediaCaptionsMenu };
export default MediaCaptionsMenu;
