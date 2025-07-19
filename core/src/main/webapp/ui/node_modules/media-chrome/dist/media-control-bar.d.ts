import { globalThis } from './utils/server-safe-globals.js';
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 * @attr {string} mediacontroller - The element `id` of the media controller to connect to (if not nested within).
 *
 * @cssproperty --media-primary-color - Default color of text and icon.
 * @cssproperty --media-secondary-color - Default color of button background.
 * @cssproperty --media-text-color - `color` of button text.
 *
 * @cssproperty --media-control-bar-display - `display` property of control bar.
 * @cssproperty --media-control-display - `display` property of control.
 */
declare class MediaControlBar extends globalThis.HTMLElement {
    #private;
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static get observedAttributes(): string[];
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
}
export default MediaControlBar;
