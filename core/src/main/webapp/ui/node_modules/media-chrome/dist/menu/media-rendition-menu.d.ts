import { MediaChromeMenu } from './media-chrome-menu.js';
import { Rendition } from '../media-store/state-mediator.js';
/**
 * @extends {MediaChromeMenu}
 *
 * @slot - Default slotted elements.
 * @slot header - An element shown at the top of the menu.
 * @slot checked-indicator - An icon element indicating a checked menu-item.
 *
 * @attr {string} mediarenditionselected - (read-only) Set to the selected rendition id.
 * @attr {string} mediarenditionlist - (read-only) Set to the rendition list.
 */
declare class MediaRenditionMenu extends MediaChromeMenu {
    #private;
    static get observedAttributes(): string[];
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    /**
     * Returns the anchor element when it is a floating menu.
     */
    get anchorElement(): HTMLElement;
    get mediaRenditionList(): Rendition[];
    set mediaRenditionList(list: Rendition[]);
    /**
     * Get selected rendition id.
     */
    get mediaRenditionSelected(): string;
    set mediaRenditionSelected(id: string);
    get mediaHeight(): number;
    set mediaHeight(height: number);
}
export { MediaRenditionMenu };
export default MediaRenditionMenu;
