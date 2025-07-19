import { MediaChromeButton } from './media-chrome-button.js';
import { TextTrackLike } from './utils/TextTrackLike.js';
declare function getSlotTemplateHTML(_attrs: Record<string, string>): string;
declare function getTooltipContentHTML(): string;
/**
 * @slot on - An element that will be shown while closed captions or subtitles are on.
 * @slot off - An element that will be shown while closed captions or subtitles are off.
 * @slot icon - An element for representing on and off states in a single icon
 *
 * @attr {string} mediasubtitleslist - (read-only) A list of all subtitles and captions.
 * @attr {string} mediasubtitlesshowing - (read-only) A list of the showing subtitles and captions.
 *
 * @cssproperty [--media-captions-button-display = inline-flex] - `display` property of button.
 */
declare class MediaCaptionsButton extends MediaChromeButton {
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static getTooltipContentHTML: typeof getTooltipContentHTML;
    static get observedAttributes(): string[];
    connectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string, newValue: string): void;
    /**
     * An array of TextTrack-like objects.
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
    handleClick(): void;
}
export { MediaCaptionsButton };
export default MediaCaptionsButton;
