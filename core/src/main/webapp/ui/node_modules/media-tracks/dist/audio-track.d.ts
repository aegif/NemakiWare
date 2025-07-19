import { AudioRendition } from './audio-rendition.js';
export declare const AudioTrackKind: {
    alternative: string;
    descriptions: string;
    main: string;
    'main-desc': string;
    translation: string;
    commentary: string;
};
export declare class AudioTrack {
    #private;
    id?: string;
    kind?: string;
    label: string;
    language: string;
    sourceBuffer?: SourceBuffer;
    addRendition(src: string, codec?: string, bitrate?: number): AudioRendition;
    removeRendition(rendition: AudioRendition): void;
    get enabled(): boolean;
    set enabled(val: boolean);
}
