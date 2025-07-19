import { VideoTrack } from './video-track.js';
import { VideoTrackList } from './video-track-list.js';
import { AudioTrack } from './audio-track.js';
import { AudioTrackList } from './audio-track-list.js';
import { VideoRenditionList } from './video-rendition-list.js';
import { AudioRenditionList } from './audio-rendition-list.js';
declare interface MediaTracks {
    videoTracks: VideoTrackList;
    audioTracks: AudioTrackList;
    addVideoTrack(kind: string, label?: string, language?: string): VideoTrack;
    addAudioTrack(kind: string, label?: string, language?: string): AudioTrack;
    removeVideoTrack(track: VideoTrack): void;
    removeAudioTrack(track: AudioTrack): void;
    videoRenditions: VideoRenditionList;
    audioRenditions: AudioRenditionList;
}
declare type Constructor<T> = {
    new (...args: any[]): T;
    prototype: T;
};
export type WithMediaTracks<T> = T & Constructor<MediaTracks>;
export declare function MediaTracksMixin<T>(MediaElementClass: T): WithMediaTracks<T>;
export {};
