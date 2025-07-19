import { TextTrackLike } from '../utils/TextTrackLike.js';
export declare const getSubtitleTracks: (stateOwners: any) => TextTrackLike[];
export declare const getShowingSubtitleTracks: (stateOwners: any) => TextTrackLike[];
export declare const toggleSubtitleTracks: (stateOwners: any, force: boolean) => void;
export declare const areValuesEq: (x: any, y: any) => boolean;
export declare const areArraysEq: (xs: number[], ys: number[]) => boolean;
