/**
 * Custom Media Element
 * Based on https://github.com/muxinc/custom-video-element - Mux - MIT License
 *
 * The goal is to create an element that works just like the video element
 * but can be extended/sub-classed, because native elements cannot be
 * extended today across browsers.
 */
export declare const Events: readonly ["abort", "canplay", "canplaythrough", "durationchange", "emptied", "encrypted", "ended", "error", "loadeddata", "loadedmetadata", "loadstart", "pause", "play", "playing", "progress", "ratechange", "seeked", "seeking", "stalled", "suspend", "timeupdate", "volumechange", "waiting", "waitingforkey", "resize", "enterpictureinpicture", "leavepictureinpicture", "webkitbeginfullscreen", "webkitendfullscreen", "webkitpresentationmodechanged"];
export type EventsMap = {
    [key in typeof Events[number]]: CustomEvent;
};
export declare const Attributes: readonly ["autopictureinpicture", "disablepictureinpicture", "disableremoteplayback", "autoplay", "controls", "controlslist", "crossorigin", "loop", "muted", "playsinline", "poster", "preload", "src"];
/**
 * Helper function to generate the HTML template for audio elements.
 */
declare function getAudioTemplateHTML(attrs: Record<string, string>): string;
/**
 * Helper function to generate the HTML template for video elements.
 */
declare function getVideoTemplateHTML(attrs: Record<string, string>): string;
type Constructor<T> = {
    new (...args: any[]): T;
};
declare class CustomAudioElementClass extends HTMLAudioElement implements HTMLAudioElement {
    static readonly observedAttributes: string[];
    static getTemplateHTML: typeof getAudioTemplateHTML;
    static shadowRootOptions: ShadowRootInit;
    static Events: string[];
    readonly nativeEl: HTMLAudioElement;
    attributeChangedCallback(attrName: string, oldValue?: string | null, newValue?: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    init(): void;
    handleEvent(event: Event): void;
}
declare class CustomVideoElementClass extends HTMLVideoElement implements HTMLVideoElement {
    static readonly observedAttributes: string[];
    static getTemplateHTML: typeof getVideoTemplateHTML;
    static shadowRootOptions: ShadowRootInit;
    static Events: string[];
    readonly nativeEl: HTMLVideoElement;
    attributeChangedCallback(attrName: string, oldValue?: string | null, newValue?: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    init(): void;
    handleEvent(event: Event): void;
}
type CustomMediaElementConstructor<T> = {
    readonly observedAttributes: string[];
    getTemplateHTML: typeof getVideoTemplateHTML | typeof getAudioTemplateHTML;
    shadowRootOptions: ShadowRootInit;
    Events: string[];
    new (...args: any[]): T;
};
export type CustomVideoElement = CustomMediaElementConstructor<CustomVideoElementClass>;
export type CustomAudioElement = CustomMediaElementConstructor<CustomAudioElementClass>;
/**
 * @see https://justinfagnani.com/2015/12/21/real-mixins-with-javascript-classes/
 */
export declare function CustomMediaMixin<T extends Constructor<HTMLElement>>(superclass: T, { tag, is }: {
    tag: 'video';
    is?: string;
}): CustomVideoElement;
export declare function CustomMediaMixin<T extends Constructor<HTMLElement>>(superclass: T, { tag, is }: {
    tag: 'audio';
    is?: string;
}): CustomAudioElement;
export declare const CustomVideoElement: CustomVideoElement;
export declare const CustomAudioElement: CustomAudioElement;
export {};
