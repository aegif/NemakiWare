import { MediaTextDisplay } from './media-text-display.js';
declare function getSlotTemplateHTML(_attrs: Record<string, string>, props: Record<string, any>): string;
/**
 * @attr {string} mediaduration - (read-only) Set to the media duration.
 *
 * @cssproperty [--media-duration-display-display = inline-flex] - `display` property of display.
 */
declare class MediaDurationDisplay extends MediaTextDisplay {
    #private;
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static get observedAttributes(): string[];
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     * @type {number | undefined} In seconds
     */
    get mediaDuration(): number | undefined;
    set mediaDuration(time: number | undefined);
}
export default MediaDurationDisplay;
