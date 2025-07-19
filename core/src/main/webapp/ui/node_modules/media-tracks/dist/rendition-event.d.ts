import type { AudioRendition } from './audio-rendition.js';
import type { VideoRendition } from './video-rendition.js';
export declare class RenditionEvent extends Event {
    rendition: VideoRendition | AudioRendition;
    constructor(type: string, init: Record<string, VideoRendition | AudioRendition>);
}
