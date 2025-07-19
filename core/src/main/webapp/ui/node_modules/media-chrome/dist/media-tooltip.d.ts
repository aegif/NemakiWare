import { globalThis } from './utils/server-safe-globals.js';
export declare const Attributes: {
    PLACEMENT: string;
    BOUNDS: string;
};
export type TooltipPlacement = 'top' | 'right' | 'bottom' | 'left' | 'none';
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 * @extends {HTMLElement}
 *
 * @attr {('top'|'right'|'bottom'|'left'|'none')} placement - The placement of the tooltip, defaults to "top"
 * @attr {string} bounds - ID for the containing element (one of it's parents) that should constrain the tooltips horizontal position.
 *
 * @cssproperty --media-primary-color - Default color of text.
 * @cssproperty --media-secondary-color - Default color of tooltip background.
 * @cssproperty --media-text-color - `color` of tooltip text.
 *
 * @cssproperty --media-font - `font` shorthand property.
 * @cssproperty --media-font-weight - `font-weight` property.
 * @cssproperty --media-font-family - `font-family` property.
 * @cssproperty --media-font-size - `font-size` property.
 * @cssproperty --media-text-content-height - `line-height` of button text.
 *
 * @cssproperty --media-tooltip-border - 'border' of tooltip
 * @cssproperty --media-tooltip-background-color - Background color of tooltip and arrow, unless individually overidden
 * @cssproperty --media-tooltip-background - `background` of tooltip, ignoring the arrow
 * @cssproperty --media-tooltip-display - `display` of tooltip
 * @cssproperty --media-tooltip-z-index - `z-index` of tooltip
 * @cssproperty --media-tooltip-padding - `padding` of tooltip
 * @cssproperty --media-tooltip-border-radius - `border-radius` of tooltip
 * @cssproperty --media-tooltip-filter - `filter` property of tooltip, for drop-shadow
 * @cssproperty --media-tooltip-white-space - `white-space` property of tooltip
 * @cssproperty --media-tooltip-arrow-display - `display` property of tooltip arrow
 * @cssproperty --media-tooltip-arrow-width - Arrow width
 * @cssproperty --media-tooltip-arrow-height - Arrow height
 * @cssproperty --media-tooltip-arrow-color - Arrow color
 */
declare class MediaTooltip extends globalThis.HTMLElement {
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static get observedAttributes(): string[];
    arrowEl: HTMLElement;
    constructor();
    updateXOffset: () => void;
    /**
     * Get or set tooltip placement
     */
    get placement(): TooltipPlacement | undefined;
    set placement(value: TooltipPlacement | undefined);
    /**
     * Get or set tooltip container ID selector that will constrain the tooltips
     * horizontal position.
     */
    get bounds(): string | undefined;
    set bounds(value: string | undefined);
}
export default MediaTooltip;
