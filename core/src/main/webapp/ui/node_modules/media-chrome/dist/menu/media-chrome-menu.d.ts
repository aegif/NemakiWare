import { globalThis } from '../utils/server-safe-globals.js';
import MediaChromeMenuItem from './media-chrome-menu-item.js';
export declare function createMenuItem({ type, text, value, checked, }: {
    type?: string;
    text: string;
    value: string;
    checked: boolean;
}): MediaChromeMenuItem;
export declare function createIndicator(el: HTMLElement, name: string): Node | "";
declare function getTemplateHTML(_attrs: Record<string, string>): string;
export declare const Attributes: {
    readonly STYLE: "style";
    readonly HIDDEN: "hidden";
    readonly DISABLED: "disabled";
    readonly ANCHOR: "anchor";
};
/**
 * @extends {HTMLElement}
 *
 * @slot - Default slotted elements.
 * @slot header - An element shown at the top of the menu.
 * @slot checked-indicator - An icon element indicating a checked menu-item.
 *
 * @attr {boolean} disabled - The Boolean disabled attribute makes the element not mutable or focusable.
 * @attr {string} mediacontroller - The element `id` of the media controller to connect to (if not nested within).
 *
 * @cssproperty --media-primary-color - Default color of text / icon.
 * @cssproperty --media-secondary-color - Default color of background.
 * @cssproperty --media-text-color - `color` of text.
 *
 * @cssproperty --media-control-background - `background` of control.
 * @cssproperty --media-menu-display - `display` of menu.
 * @cssproperty --media-menu-layout - Set to `row` for a horizontal menu design.
 * @cssproperty --media-menu-flex-direction - `flex-direction` of menu.
 * @cssproperty --media-menu-gap - `gap` between menu items.
 * @cssproperty --media-menu-background - `background` of menu.
 * @cssproperty --media-menu-border-radius - `border-radius` of menu.
 * @cssproperty --media-menu-border - `border` of menu.
 * @cssproperty --media-menu-transition-in - `transition` of menu when showing.
 * @cssproperty --media-menu-transition-out - `transition` of menu when hiding.
 * @cssproperty --media-menu-visibility - `visibility` of menu when showing.
 * @cssproperty --media-menu-hidden-visibility - `visibility` of menu when hiding.
 * @cssproperty --media-menu-max-height - `max-height` of menu.
 * @cssproperty --media-menu-hidden-max-height - `max-height` of menu when hiding.
 * @cssproperty --media-menu-opacity - `opacity` of menu when showing.
 * @cssproperty --media-menu-hidden-opacity - `opacity` of menu when hiding.
 * @cssproperty --media-menu-transform-in - `transform` of menu when showing.
 * @cssproperty --media-menu-transform-out - `transform` of menu when hiding.
 *
 * @cssproperty --media-font - `font` shorthand property.
 * @cssproperty --media-font-weight - `font-weight` property.
 * @cssproperty --media-font-family - `font-family` property.
 * @cssproperty --media-font-size - `font-size` property.
 * @cssproperty --media-text-content-height - `line-height` of text.
 *
 * @cssproperty --media-icon-color - `fill` color of icon.
 * @cssproperty --media-menu-icon-height - `height` of icon.
 * @cssproperty --media-menu-item-checked-indicator-display - `display` of check indicator.
 * @cssproperty --media-menu-item-checked-background - `background` of checked menu item.
 * @cssproperty --media-menu-item-max-width - `max-width` of menu item text.
 */
declare class MediaChromeMenu extends globalThis.HTMLElement {
    #private;
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static get observedAttributes(): string[];
    static formatMenuItemText(text: string, _data?: any): string;
    container: HTMLElement;
    defaultSlot: HTMLSlotElement;
    constructor();
    enable(): void;
    disable(): void;
    handleEvent(event: Event): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    formatMenuItemText(text: string, data?: any): string;
    get anchor(): string;
    set anchor(value: string);
    /**
     * Returns the anchor element when it is a floating menu.
     */
    get anchorElement(): HTMLElement;
    /**
     * Returns the menu items.
     */
    get items(): MediaChromeMenuItem[];
    get radioGroupItems(): MediaChromeMenuItem[];
    get checkedItems(): MediaChromeMenuItem[];
    get value(): string;
    set value(newValue: string);
    focus(): void;
    handleSelect(event: MouseEvent | KeyboardEvent): void;
    get keysUsed(): string[];
    handleMove(event: KeyboardEvent): void;
}
export { MediaChromeMenu };
export default MediaChromeMenu;
