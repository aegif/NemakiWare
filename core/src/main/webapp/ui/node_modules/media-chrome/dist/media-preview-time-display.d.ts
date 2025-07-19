import { MediaTextDisplay } from './media-text-display.js';
/**
 * @attr {string} mediapreviewtime - (read-only) Set to the timeline preview time.
 *
 * @cssproperty [--media-preview-time-display-display = inline-flex] - `display` property of display.
 */
declare class MediaPreviewTimeDisplay extends MediaTextDisplay {
    #private;
    static get observedAttributes(): string[];
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     * Timeline preview time
     */
    get mediaPreviewTime(): number | undefined;
    set mediaPreviewTime(value: number | undefined);
}
export default MediaPreviewTimeDisplay;
