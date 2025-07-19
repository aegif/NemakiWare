/**
 * - The consumer should use the `selected` setter to select 1 or multiple
 *   renditions that the engine is allowed to play.
 */
export declare class AudioRendition {
    #private;
    src?: string;
    id?: string;
    bitrate?: number;
    codec?: string;
    get selected(): boolean;
    set selected(val: boolean);
}
