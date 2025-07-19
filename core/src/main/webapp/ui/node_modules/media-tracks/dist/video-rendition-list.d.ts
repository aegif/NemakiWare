import type { VideoTrack } from './video-track.js';
import type { VideoRendition } from './video-rendition.js';
export declare function addRendition(track: VideoTrack, rendition: VideoRendition): void;
export declare function removeRendition(rendition: VideoRendition): void;
export declare function selectedChanged(rendition: VideoRendition): void;
export declare class VideoRenditionList extends EventTarget {
    #private;
    [index: number]: VideoRendition;
    [Symbol.iterator](): IterableIterator<VideoRendition>;
    get length(): number;
    getRenditionById(id: string): VideoRendition | null;
    get selectedIndex(): number;
    set selectedIndex(index: number);
    get onaddrendition(): ((event?: {
        rendition: VideoRendition;
    }) => void) | undefined;
    set onaddrendition(callback: ((event?: {
        rendition: VideoRendition;
    }) => void) | undefined);
    get onremoverendition(): ((event?: {
        rendition: VideoRendition;
    }) => void) | undefined;
    set onremoverendition(callback: ((event?: {
        rendition: VideoRendition;
    }) => void) | undefined);
    get onchange(): (() => void) | undefined;
    set onchange(callback: (() => void) | undefined);
}
