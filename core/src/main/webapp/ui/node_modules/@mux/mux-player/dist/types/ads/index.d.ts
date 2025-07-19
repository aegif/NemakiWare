import '@mux/mux-video/ads';
import MuxPlayerBaseElement from '@mux/mux-player/base';
import type { EventMap as MuxVideoAdsEventMap } from '@mux/mux-video/ads';
type Expand<T> = T extends infer O ? {
    [K in keyof O]: O[K];
} : never;
export type EventMap = Expand<MuxVideoAdsEventMap>;
declare const MuxPlayerElement_base: import("./mixin/types").Constructor<import("./mixin/types").IAdsPlayer> & typeof MuxPlayerBaseElement;
declare class MuxPlayerElement extends MuxPlayerElement_base {
}
export * from '@mux/mux-player/base';
export default MuxPlayerElement;
