import type { VideoTrack } from './video-track.js';
export declare function addVideoTrack(media: HTMLMediaElement, track: VideoTrack): void;
export declare function removeVideoTrack(track: VideoTrack): void;
export declare function selectedChanged(selected: VideoTrack): void;
export declare class VideoTrackList extends EventTarget {
    #private;
    [index: number]: VideoTrack;
    constructor();
    [Symbol.iterator](): any;
    get length(): any;
    getTrackById(id: string): VideoTrack | null;
    get selectedIndex(): number;
    get onaddtrack(): ((event?: {
        track: VideoTrack;
    }) => void) | undefined;
    set onaddtrack(callback: ((event?: {
        track: VideoTrack;
    }) => void) | undefined);
    get onremovetrack(): ((event?: {
        track: VideoTrack;
    }) => void) | undefined;
    set onremovetrack(callback: ((event?: {
        track: VideoTrack;
    }) => void) | undefined);
    get onchange(): (() => void) | undefined;
    set onchange(callback: (() => void) | undefined);
}
