import { globalThis } from './utils/server-safe-globals.js';
declare function getTemplateHTML(_attrs: Record<string, string>, _props?: Record<string, any>): string;
declare function getSlotTemplateHTML(_attrs: Record<string, string>, _props: Record<string, any>): string;
/**
 * @slot - Default slotted elements.
 *
 * @attr {string} mediacontroller - The element `id` of the media controller to connect to (if not nested within).
 *
 * @cssproperty --media-primary-color - Default color of text.
 * @cssproperty --media-secondary-color - Default color of background.
 * @cssproperty --media-text-color - `color` of text.
 *
 * @cssproperty --media-control-display - `display` property of control.
 * @cssproperty --media-control-background - `background` of control.
 * @cssproperty --media-control-padding - `padding` of control.
 * @cssproperty --media-control-height - `line-height` of control.
 *
 * @cssproperty --media-font - `font` shorthand property.
 * @cssproperty --media-font-weight - `font-weight` property.
 * @cssproperty --media-font-family - `font-family` property.
 * @cssproperty --media-font-size - `font-size` property.
 * @cssproperty --media-text-content-height - `line-height` of text.
 */
declare class MediaTextDisplay extends globalThis.HTMLElement {
    #private;
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static get observedAttributes(): string[];
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
}
export { MediaTextDisplay };
export default MediaTextDisplay;
