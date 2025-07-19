import { MediaTextDisplay } from './media-text-display.js';
/**
 * @attr {string} mediapreviewchapter - (read-only) Set to the timeline preview chapter.
 *
 * @cssproperty [--media-preview-chapter-display-display = inline-flex] - `display` property of display.
 */
declare class MediaPreviewChapterDisplay extends MediaTextDisplay {
    #private;
    static get observedAttributes(): string[];
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     * @type {string | undefined} Timeline preview chapter
     */
    get mediaPreviewChapter(): string | undefined;
    set mediaPreviewChapter(value: string | undefined);
}
export default MediaPreviewChapterDisplay;
