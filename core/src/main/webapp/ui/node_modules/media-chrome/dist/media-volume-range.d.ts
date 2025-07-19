import { MediaChromeRange } from './media-chrome-range.js';
/**
 * @attr {string} mediavolume - (read-only) Set to the media volume.
 * @attr {boolean} mediamuted - (read-only) Set to the media muted state.
 * @attr {string} mediavolumeunavailable - (read-only) Set if changing volume is unavailable.
 *
 * @cssproperty [--media-volume-range-display = inline-block] - `display` property of range.
 */
declare class MediaVolumeRange extends MediaChromeRange {
    static get observedAttributes(): string[];
    constructor();
    connectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     *
     */
    get mediaVolume(): number;
    set mediaVolume(value: number);
    /**
     * Is the media currently muted
     */
    get mediaMuted(): boolean;
    set mediaMuted(value: boolean);
    /**
     * The volume unavailability state
     */
    get mediaVolumeUnavailable(): string | undefined;
    set mediaVolumeUnavailable(value: string | undefined);
}
export default MediaVolumeRange;
