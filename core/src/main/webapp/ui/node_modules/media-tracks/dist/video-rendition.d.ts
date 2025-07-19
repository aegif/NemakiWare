/**
 * - The consumer should use the `selected` setter to select 1 or multiple
 *   renditions that the engine is allowed to play.
 */
export declare class VideoRendition {
    #private;
    src?: string;
    id?: string;
    width?: number;
    height?: number;
    bitrate?: number;
    frameRate?: number;
    codec?: string;
    get selected(): boolean;
    set selected(val: boolean);
}
