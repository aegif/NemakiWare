import { globalThis } from '../utils/server-safe-globals.js';
import type MediaChromeMenu from './media-chrome-menu.js';
declare function getTemplateHTML(_attrs: Record<string, string>): string;
declare function getSuffixSlotInnerHTML(_attrs: Record<string, string>): string;
export declare const Attributes: {
    TYPE: string;
    VALUE: string;
    CHECKED: string;
    DISABLED: string;
};
/**
 * @extends {HTMLElement}
 * @slot - Default slotted elements.
 *
 * @attr {(''|'radio'|'checkbox')} type - This attribute indicates the kind of command, and can be one of three values.
 * @attr {boolean} disabled - The Boolean disabled attribute makes the element not mutable or focusable.
 *
 * @cssproperty --media-menu-item-opacity - `opacity` of menu-item content.
 * @cssproperty --media-menu-item-transition - `transition` of menu-item.
 * @cssproperty --media-menu-item-checked-background - `background` of checked menu-item.
 * @cssproperty --media-menu-item-outline - `outline` menu-item.
 * @cssproperty --media-menu-item-outline-offset - `outline-offset` of menu-item.
 * @cssproperty --media-menu-item-hover-background - `background` of hovered menu-item.
 * @cssproperty --media-menu-item-hover-outline - `outline` of hovered menu-item.
 * @cssproperty --media-menu-item-hover-outline-offset - `outline-offset` of hovered menu-item.
 * @cssproperty --media-menu-item-focus-shadow - `box-shadow` of the :focus-visible state.
 * @cssproperty --media-menu-item-icon-height - `height` of icon.
 * @cssproperty --media-menu-item-description-max-width - `max-width` of description.
 * @cssproperty --media-menu-item-checked-indicator-display - `display` of checked indicator.
 *
 * @cssproperty --media-icon-color - `fill` color of icon.
 * @cssproperty --media-menu-icon-height - `height` of icon.
 *
 * @cssproperty --media-menu-item-indicator-fill - `fill` color of indicator icon.
 * @cssproperty --media-menu-item-indicator-height - `height` of menu-item indicator.
 */
declare class MediaChromeMenuItem extends globalThis.HTMLElement {
    #private;
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static getSuffixSlotInnerHTML: typeof getSuffixSlotInnerHTML;
    static get observedAttributes(): string[];
    constructor();
    enable(): void;
    disable(): void;
    handleEvent(event: any): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    get invokeTarget(): string;
    set invokeTarget(value: string);
    /**
     * Returns the element with the id specified by the `invoketarget` attribute
     * or the slotted submenu element.
     */
    get invokeTargetElement(): MediaChromeMenu | null;
    /**
     * Returns the slotted submenu element.
     */
    get submenuElement(): MediaChromeMenu | null;
    get type(): string;
    set type(val: string);
    get value(): string;
    set value(val: string);
    get text(): string;
    get checked(): boolean;
    set checked(value: boolean);
    handleClick(event: any): void;
    get keysUsed(): string[];
}
export { MediaChromeMenuItem };
export default MediaChromeMenuItem;
