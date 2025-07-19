import { Autoplay } from '@mux/playback-core';
import { MuxVideoBaseElement } from '@mux/mux-video/base';
export * from '@mux/mux-video/base';
declare const MuxVideoElement_base: import("media-tracks").WithMediaTracks<typeof MuxVideoBaseElement>;
declare class MuxVideoElement extends MuxVideoElement_base {
    #private;
    /** @ts-ignore */
    get autoplay(): Autoplay;
    /** @ts-ignore */
    set autoplay(val: Autoplay);
    get muxCastCustomData(): {
        readonly mux: {
            readonly playbackId: string | undefined;
            readonly minResolution: import("@mux/playback-core").MinResolutionValue | undefined;
            readonly maxResolution: import("@mux/playback-core").MaxResolutionValue | undefined;
            readonly renditionOrder: "desc" | undefined;
            readonly customDomain: string | undefined;
            /** @TODO Add this.tokens to MuxVideoElement (CJP) */
            readonly tokens: {
                readonly drm: string | undefined;
            };
            readonly envKey: string | undefined;
            readonly metadata: Readonly<Partial<import("mux-embed").Metadata>> | undefined;
            readonly disableCookies: boolean;
            readonly disableTracking: boolean;
            readonly beaconCollectionDomain: string | undefined;
            readonly startTime: number | undefined;
            readonly preferCmcd: import("@mux/playback-core").ValueOf<import("@mux/playback-core").CmcdTypes> | undefined;
        };
    };
    get castCustomData(): Record<string, any> | undefined;
    set castCustomData(val: Record<string, any> | undefined);
}
type MuxVideoElementType = typeof MuxVideoElement;
declare global {
    var MuxVideoElement: MuxVideoElementType;
}
export default MuxVideoElement;
