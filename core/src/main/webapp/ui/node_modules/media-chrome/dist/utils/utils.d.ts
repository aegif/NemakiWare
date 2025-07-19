import type { Rendition } from '../media-store/state-mediator';
import type { TextTrackLike } from './TextTrackLike.js';
export declare function stringifyRenditionList(renditions: Rendition[]): string;
export declare function parseRenditionList(renditions: string): Rendition[];
export declare function stringifyRendition(rendition: Rendition): string;
export declare function parseRendition(rendition: string): Rendition;
export declare function stringifyAudioTrackList(audioTracks: any[]): string;
export declare function parseAudioTrackList(audioTracks: string): TextTrackLike[];
export declare function stringifyAudioTrack(audioTrack: any): string;
export declare function parseAudioTrack(audioTrack: string): TextTrackLike;
export declare function dashedToCamel(word: string): string;
export declare function constToCamel(word: string, upperFirst?: boolean): string;
export declare function camelCase(name: string): string;
export declare function isValidNumber(x: any): boolean;
export declare function isNumericString(str: any): boolean;
/**
 * Returns a promise that will resolve after passed ms.
 * @param  {number} ms
 * @return {Promise}
 */
export declare const delay: (ms: number) => Promise<void>;
export declare const capitalize: (str: string) => string;
