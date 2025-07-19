import type { AudioTrack } from './audio-track.js';
export declare function addAudioTrack(media: HTMLMediaElement, track: AudioTrack): void;
export declare function removeAudioTrack(track: AudioTrack): void;
export declare function enabledChanged(track: AudioTrack): void;
export declare class AudioTrackList extends EventTarget {
    #private;
    [index: number]: AudioTrack;
    constructor();
    [Symbol.iterator](): any;
    get length(): any;
    getTrackById(id: string): AudioTrack | null;
    get onaddtrack(): ((event?: {
        track: AudioTrack;
    }) => void) | undefined;
    set onaddtrack(callback: ((event?: {
        track: AudioTrack;
    }) => void) | undefined);
    get onremovetrack(): ((event?: {
        track: AudioTrack;
    }) => void) | undefined;
    set onremovetrack(callback: ((event?: {
        track: AudioTrack;
    }) => void) | undefined);
    get onchange(): (() => void) | undefined;
    set onchange(callback: (() => void) | undefined);
}
