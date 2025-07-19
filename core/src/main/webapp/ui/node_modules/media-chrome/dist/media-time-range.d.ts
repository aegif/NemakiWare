import { MediaChromeRange } from './media-chrome-range.js';
import './media-preview-thumbnail.js';
import './media-preview-time-display.js';
import './media-preview-chapter-display.js';
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 * @slot preview - An element that slides along the timeline to the position of the pointer hovering.
 * @slot preview-arrow - An arrow element that slides along the timeline to the position of the pointer hovering.
 * @slot current - An element that slides along the timeline to the position of the current time.
 *
 * @attr {string} mediabuffered - (read-only) Set to the buffered time ranges.
 * @attr {string} mediaplaybackrate - (read-only) Set to the media playback rate.
 * @attr {string} mediaduration - (read-only) Set to the media duration.
 * @attr {string} mediaseekable - (read-only) Set to the seekable time ranges.
 * @attr {boolean} mediapaused - (read-only) Present if the media is paused.
 * @attr {boolean} medialoading - (read-only) Present if the media is loading.
 * @attr {string} mediacurrenttime - (read-only) Set to the current media time.
 * @attr {string} mediapreviewimage - (read-only) Set to the timeline preview image URL.
 * @attr {string} mediapreviewtime - (read-only) Set to the timeline preview time.
 *
 * @csspart buffered - A CSS part that selects the buffered bar element.
 * @csspart box - A CSS part that selects both the preview and current box elements.
 * @csspart preview-box - A CSS part that selects the preview box element.
 * @csspart current-box - A CSS part that selects the current box element.
 * @csspart arrow - A CSS part that selects the arrow element.
 *
 * @cssproperty [--media-time-range-display = inline-block] - `display` property of range.
 *
 * @cssproperty --media-preview-transition-property - `transition-property` of range hover preview.
 * @cssproperty --media-preview-transition-duration-out - `transition-duration` out of range hover preview.
 * @cssproperty --media-preview-transition-delay-out - `transition-delay` out of range hover preview.
 * @cssproperty --media-preview-transition-duration-in - `transition-duration` in of range hover preview.
 * @cssproperty --media-preview-transition-delay-in - `transition-delay` in of range hover preview.
 *
 * @cssproperty --media-preview-thumbnail-background - `background` of range preview thumbnail.
 * @cssproperty --media-preview-thumbnail-box-shadow - `box-shadow` of range preview thumbnail.
 * @cssproperty --media-preview-thumbnail-max-width - `max-width` of range preview thumbnail.
 * @cssproperty --media-preview-thumbnail-max-height - `max-height` of range preview thumbnail.
 * @cssproperty --media-preview-thumbnail-min-width - `min-width` of range preview thumbnail.
 * @cssproperty --media-preview-thumbnail-min-height - `min-height` of range preview thumbnail.
 * @cssproperty --media-preview-thumbnail-border-radius - `border-radius` of range preview thumbnail.
 * @cssproperty --media-preview-thumbnail-border - `border` of range preview thumbnail.
 *
 * @cssproperty --media-preview-chapter-background - `background` of range preview chapter display.
 * @cssproperty --media-preview-chapter-border-radius - `border-radius` of range preview chapter display.
 * @cssproperty --media-preview-chapter-padding - `padding` of range preview chapter display.
 * @cssproperty --media-preview-chapter-margin - `margin` of range preview chapter display.
 * @cssproperty --media-preview-chapter-text-shadow - `text-shadow` of range preview chapter display.
 *
 * @cssproperty --media-preview-time-background - `background` of range preview time display.
 * @cssproperty --media-preview-time-border-radius - `border-radius` of range preview time display.
 * @cssproperty --media-preview-time-padding - `padding` of range preview time display.
 * @cssproperty --media-preview-time-margin - `margin` of range preview time display.
 * @cssproperty --media-preview-time-text-shadow - `text-shadow` of range preview time display.
 *
 * @cssproperty --media-box-display - `display` of range box.
 * @cssproperty --media-box-margin - `margin` of range box.
 * @cssproperty --media-box-padding-left - `padding-left` of range box.
 * @cssproperty --media-box-padding-right - `padding-right` of range box.
 * @cssproperty --media-box-border-radius - `border-radius` of range box.
 *
 * @cssproperty --media-preview-box-display - `display` of range preview box.
 * @cssproperty --media-preview-box-margin - `margin` of range preview box.
 *
 * @cssproperty --media-current-box-display - `display` of range current box.
 * @cssproperty --media-current-box-margin - `margin` of range current box.
 *
 * @cssproperty --media-box-arrow-display - `display` of range box arrow.
 * @cssproperty --media-box-arrow-background - `border-top-color` of range box arrow.
 * @cssproperty --media-box-arrow-border-width - `border-width` of range box arrow.
 * @cssproperty --media-box-arrow-height - `height` of range box arrow.
 * @cssproperty --media-box-arrow-width - `width` of range box arrow.
 * @cssproperty --media-box-arrow-offset - `translateX` offset of range box arrow.
 */
declare class MediaTimeRange extends MediaChromeRange {
    #private;
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static get observedAttributes(): string[];
    constructor();
    connectedCallback(): void;
    disconnectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    get mediaChaptersCues(): any[];
    set mediaChaptersCues(value: any[]);
    /**
     * Is the media paused
     */
    get mediaPaused(): boolean;
    set mediaPaused(value: boolean);
    /**
     * Is the media loading
     */
    get mediaLoading(): boolean;
    set mediaLoading(value: boolean);
    /**
     *
     */
    get mediaDuration(): number | undefined;
    set mediaDuration(value: number | undefined);
    /**
     *
     */
    get mediaCurrentTime(): number | undefined;
    set mediaCurrentTime(value: number | undefined);
    /**
     *
     */
    get mediaPlaybackRate(): number;
    set mediaPlaybackRate(value: number);
    /**
     * An array of ranges, each range being an array of two numbers.
     * e.g. [[1, 2], [3, 4]]
     */
    get mediaBuffered(): number[][];
    set mediaBuffered(list: number[][]);
    /**
     * Range of values that can be seeked to
     * An array of two numbers [start, end]
     */
    get mediaSeekable(): number[] | undefined;
    set mediaSeekable(range: number[] | undefined);
    /**
     *
     */
    get mediaSeekableEnd(): number | undefined;
    get mediaSeekableStart(): number;
    /**
     * The url of the preview image
     */
    get mediaPreviewImage(): string | undefined;
    set mediaPreviewImage(value: string | undefined);
    /**
     *
     */
    get mediaPreviewTime(): number | undefined;
    set mediaPreviewTime(value: number | undefined);
    /**
     *
     */
    get mediaEnded(): boolean | undefined;
    set mediaEnded(value: boolean | undefined);
    updateBar(): void;
    updateBufferedBar(): void;
    updateCurrentBox(): void;
    handleEvent(evt: Event | MouseEvent): void;
}
export default MediaTimeRange;
