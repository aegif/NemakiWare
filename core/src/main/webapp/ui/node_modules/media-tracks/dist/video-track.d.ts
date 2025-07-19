import { VideoRendition } from './video-rendition.js';
export declare const VideoTrackKind: {
    alternative: string;
    captions: string;
    main: string;
    sign: string;
    subtitles: string;
    commentary: string;
};
export declare class VideoTrack {
    #private;
    id?: string;
    kind?: string;
    label: string;
    language: string;
    sourceBuffer?: SourceBuffer;
    addRendition(src: string, width?: number, height?: number, codec?: string, bitrate?: number, frameRate?: number): VideoRendition;
    removeRendition(rendition: VideoRendition): void;
    get selected(): boolean;
    set selected(val: boolean);
}
