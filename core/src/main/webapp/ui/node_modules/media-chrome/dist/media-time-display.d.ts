import { MediaTextDisplay } from './media-text-display.js';
export declare const Attributes: {
    REMAINING: string;
    SHOW_DURATION: string;
    NO_TOGGLE: string;
};
declare function getSlotTemplateHTML(_attrs: Record<string, string>, props: Record<string, any>): string;
/**
 * @attr {boolean} remaining - Toggle on to show the remaining time instead of elapsed time.
 * @attr {boolean} showduration - Toggle on to show the duration.
 * @attr {boolean} disabled - The Boolean disabled attribute makes the element not mutable or focusable.
 * @attr {boolean} notoggle - Set this to disable click or tap behavior that toggles between remaining and current time.
 * @attr {string} mediacurrenttime - (read-only) Set to the current media time.
 * @attr {string} mediaduration - (read-only) Set to the media duration.
 * @attr {string} mediaseekable - (read-only) Set to the seekable time ranges.
 *
 * @cssproperty [--media-time-display-display = inline-flex] - `display` property of display.
 * @cssproperty --media-control-hover-background - `background` of control hover state.
 */
declare class MediaTimeDisplay extends MediaTextDisplay {
    #private;
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static get observedAttributes(): string[];
    constructor();
    connectedCallback(): void;
    toggleTimeDisplay(): void;
    disconnectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    enable(): void;
    disable(): void;
    /**
     * Whether to show the remaining time
     */
    get remaining(): boolean;
    set remaining(show: boolean);
    /**
     * Whether to show the duration
     */
    get showDuration(): boolean;
    set showDuration(show: boolean);
    /**
     * Disable the default behavior that toggles between current and remaining time
     */
    get noToggle(): boolean;
    set noToggle(noToggle: boolean);
    /**
     * Get the duration
     */
    get mediaDuration(): number;
    set mediaDuration(time: number);
    /**
     * The current time in seconds
     */
    get mediaCurrentTime(): number;
    set mediaCurrentTime(time: number);
    /**
     * Range of values that can be seeked to.
     * An array of two numbers [start, end]
     */
    get mediaSeekable(): [number, number];
    set mediaSeekable(range: [number, number]);
    update(): void;
}
export default MediaTimeDisplay;
