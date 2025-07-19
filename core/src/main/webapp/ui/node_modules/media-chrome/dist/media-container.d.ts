import { globalThis } from './utils/server-safe-globals.js';
import './media-gesture-receiver.js';
export declare const Attributes: {
    AUDIO: string;
    AUTOHIDE: string;
    BREAKPOINTS: string;
    GESTURES_DISABLED: string;
    KEYBOARD_CONTROL: string;
    NO_AUTOHIDE: string;
    USER_INACTIVE: string;
    AUTOHIDE_OVER_CONTROLS: string;
};
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 * @extends {HTMLElement}
 *
 * @attr {boolean} audio
 * @attr {string} autohide
 * @attr {boolean} autohideovercontrols
 * @attr {string} breakpoints
 * @attr {boolean} gesturesdisabled
 * @attr {boolean} keyboardcontrol
 * @attr {boolean} noautohide
 * @attr {boolean} userinactive
 *
 * @cssprop --media-background-color - `background-color` of container.
 * @cssprop --media-slot-display - `display` of the media slot (default none for [audio] usage).
 * @cssprop --media-control-transition-out - `transition` used to define the animation effect when hiding the container.
 * @cssprop --media-control-transition-in - `transition` used to define the animation effect when showing the container.
 */
declare class MediaContainer extends globalThis.HTMLElement {
    #private;
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static get observedAttributes(): string[];
    breakpointsComputed: boolean;
    constructor();
    attributeChangedCallback(attrName: string, _oldValue: string, newValue: string): void;
    get media(): HTMLVideoElement | null;
    handleMediaUpdated(media: HTMLMediaElement): Promise<void>;
    connectedCallback(): void;
    disconnectedCallback(): void;
    /**
     * @abstract
     */
    mediaSetCallback(_media: HTMLMediaElement): void;
    mediaUnsetCallback(_media: HTMLMediaElement): void;
    handleEvent(event: Event): void;
    set autohide(seconds: string);
    get autohide(): string;
    get breakpoints(): string | undefined;
    set breakpoints(value: string | undefined);
    get audio(): boolean | undefined;
    set audio(value: boolean | undefined);
    get gesturesDisabled(): boolean | undefined;
    set gesturesDisabled(value: boolean | undefined);
    get keyboardControl(): boolean | undefined;
    set keyboardControl(value: boolean | undefined);
    get noAutohide(): boolean | undefined;
    set noAutohide(value: boolean | undefined);
    get autohideOverControls(): boolean | undefined;
    set autohideOverControls(value: boolean | undefined);
    get userInteractive(): boolean | undefined;
    set userInteractive(value: boolean | undefined);
}
export { MediaContainer };
export default MediaContainer;
