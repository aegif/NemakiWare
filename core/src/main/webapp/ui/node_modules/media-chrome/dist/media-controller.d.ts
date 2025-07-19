import { MediaContainer } from './media-container.js';
import { MediaStore } from './media-store/media-store.js';
export declare const Attributes: {
    DEFAULT_SUBTITLES: string;
    DEFAULT_STREAM_TYPE: string;
    DEFAULT_DURATION: string;
    FULLSCREEN_ELEMENT: string;
    HOTKEYS: string;
    KEYS_USED: string;
    LIVE_EDGE_OFFSET: string;
    SEEK_TO_LIVE_OFFSET: string;
    NO_AUTO_SEEK_TO_LIVE: string;
    NO_HOTKEYS: string;
    NO_VOLUME_PREF: string;
    NO_SUBTITLES_LANG_PREF: string;
    NO_DEFAULT_STORE: string;
    KEYBOARD_FORWARD_SEEK_OFFSET: string;
    KEYBOARD_BACKWARD_SEEK_OFFSET: string;
    LANG: string;
};
/**
 * Media Controller should not mimic the HTMLMediaElement API.
 * @see https://github.com/muxinc/media-chrome/pull/182#issuecomment-1067370339
 *
 * @attr {boolean} defaultsubtitles
 * @attr {string} defaultstreamtype
 * @attr {string} defaultduration
 * @attr {string} fullscreenelement
 * @attr {boolean} nohotkeys
 * @attr {string} hotkeys
 * @attr {string} keysused
 * @attr {string} liveedgeoffset
 * @attr {string} seektoliveoffset
 * @attr {boolean} noautoseektolive
 * @attr {boolean} novolumepref
 * @attr {boolean} nosubtitleslangpref
 * @attr {boolean} nodefaultstore
 * @attr {string} lang
 */
declare class MediaController extends MediaContainer {
    #private;
    static get observedAttributes(): string[];
    mediaStateReceivers: HTMLElement[];
    associatedElementSubscriptions: Map<HTMLElement, () => void>;
    constructor();
    get mediaStore(): MediaStore;
    set mediaStore(value: MediaStore);
    get fullscreenElement(): HTMLElement;
    set fullscreenElement(element: HTMLElement);
    get defaultSubtitles(): boolean | undefined;
    set defaultSubtitles(value: boolean);
    get defaultStreamType(): string | undefined;
    set defaultStreamType(value: string | undefined);
    get defaultDuration(): number | undefined;
    set defaultDuration(value: number | undefined);
    get noHotkeys(): boolean | undefined;
    set noHotkeys(value: boolean | undefined);
    get keysUsed(): string | undefined;
    set keysUsed(value: string | undefined);
    get liveEdgeOffset(): number | undefined;
    set liveEdgeOffset(value: number | undefined);
    get noAutoSeekToLive(): boolean | undefined;
    set noAutoSeekToLive(value: boolean | undefined);
    get noVolumePref(): boolean | undefined;
    set noVolumePref(value: boolean | undefined);
    get noSubtitlesLangPref(): boolean | undefined;
    set noSubtitlesLangPref(value: boolean | undefined);
    get noDefaultStore(): boolean | undefined;
    set noDefaultStore(value: boolean | undefined);
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    /**
     * @override
     * @param {HTMLMediaElement} media
     */
    mediaSetCallback(media: HTMLMediaElement): void;
    /**
     * @override
     * @param {HTMLMediaElement} media
     */
    mediaUnsetCallback(media: HTMLMediaElement): void;
    propagateMediaState(stateName: string, state: any): void;
    associateElement(element: HTMLElement): void;
    unassociateElement(element: HTMLElement): void;
    registerMediaStateReceiver(el: HTMLElement): void;
    unregisterMediaStateReceiver(el: HTMLElement): void;
    enableHotkeys(): void;
    disableHotkeys(): void;
    get hotkeys(): string | undefined;
    set hotkeys(value: string | undefined);
    keyboardShortcutHandler(e: KeyboardEvent): void;
}
export { MediaController };
export default MediaController;
