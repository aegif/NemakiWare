import type { AudioTrack } from './audio-track.js';
import type { AudioRendition } from './audio-rendition.js';
export declare function addRendition(track: AudioTrack, rendition: AudioRendition): void;
export declare function removeRendition(rendition: AudioRendition): void;
export declare function selectedChanged(rendition: AudioRendition): void;
export declare class AudioRenditionList extends EventTarget {
    #private;
    [index: number]: AudioRendition;
    [Symbol.iterator](): IterableIterator<AudioRendition>;
    get length(): number;
    getRenditionById(id: string): AudioRendition | null;
    get selectedIndex(): number;
    set selectedIndex(index: number);
    get onaddrendition(): ((event?: {
        rendition: AudioRendition;
    }) => void) | undefined;
    set onaddrendition(callback: ((event?: {
        rendition: AudioRendition;
    }) => void) | undefined);
    get onremoverendition(): ((event?: {
        rendition: AudioRendition;
    }) => void) | undefined;
    set onremoverendition(callback: ((event?: {
        rendition: AudioRendition;
    }) => void) | undefined);
    get onchange(): (() => void) | undefined;
    set onchange(callback: (() => void) | undefined);
}
