import { globalThis } from './utils/server-safe-globals.js';
/**
 * Get the template HTML for the dialog with the given attributes.
 *
 * This is a static method that can be called on the class and can be
 * overridden by subclasses to customize the template.
 *
 * Another static method, `getSlotTemplateHTML`, is called by this method
 * which can be separately overridden to customize the slot template.
 */
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 * Get the slot template HTML for the dialog with the given attributes.
 *
 * This is a static method that can be called on the class and can be
 * overridden by subclasses to customize the slot template.
 */
declare function getSlotTemplateHTML(_attrs: Record<string, string>): string;
export declare const Attributes: {
    OPEN: string;
    ANCHOR: string;
};
/**
 * @extends {HTMLElement}
 *
 * @slot - Default slotted elements.
 *
 * @attr {boolean} open - The open state of the dialog.
 *
 * @cssproperty --media-primary-color - Default color of text / icon.
 * @cssproperty --media-secondary-color - Default color of background.
 * @cssproperty --media-text-color - `color` of text.
 *
 * @cssproperty --media-dialog-display - `display` of dialog.
 *
 * @cssproperty --media-font - `font` shorthand property.
 * @cssproperty --media-font-weight - `font-weight` property.
 * @cssproperty --media-font-family - `font-family` property.
 * @cssproperty --media-font-size - `font-size` property.
 * @cssproperty --media-text-content-height - `line-height` of text.
 *
 * @event {Event} open - Dispatched when the dialog is opened.
 * @event {Event} close - Dispatched when the dialog is closed.
 * @event {Event} focus - Dispatched when the dialog is focused.
 * @event {Event} focusin - Dispatched when the dialog is focused in.
 */
declare class MediaChromeDialog extends globalThis.HTMLElement {
    #private;
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static get observedAttributes(): string[];
    constructor();
    get open(): boolean;
    set open(value: boolean);
    handleEvent(event: Event): void;
    connectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    focus(): void;
    get keysUsed(): string[];
}
export { MediaChromeDialog };
export default MediaChromeDialog;
