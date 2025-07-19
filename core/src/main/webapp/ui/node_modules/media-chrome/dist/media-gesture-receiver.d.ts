import { globalThis } from './utils/server-safe-globals.js';
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 * @extends {HTMLElement}
 *
 * @attr {boolean} mediapaused - (read-only) Present if the media is paused.
 * @attr {string} mediacontroller - The element `id` of the media controller to connect to (if not nested within).
 *
 * @cssproperty --media-gesture-receiver-display - `display` property of gesture receiver.
 * @cssproperty --media-control-display - `display` property of control.
 */
declare class MediaGestureReceiver extends globalThis.HTMLElement {
    #private;
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static get observedAttributes(): string[];
    _pointerType: string;
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    handleEvent(event: any): void;
    /**
     * @type {boolean} Is the media paused
     */
    get mediaPaused(): boolean;
    set mediaPaused(value: boolean);
    /**
     * @abstract
     * @argument {Event} e
     */
    handleTap(e: any): void;
    handleMouseClick(e: any): void;
}
export default MediaGestureReceiver;
