import { MediaChromeMenu } from './media-chrome-menu.js';
import { TextTrackLike } from '../utils/TextTrackLike.js';
/**
 * @extends {MediaChromeMenu}
 *
 * @slot - Default slotted elements.node
 * @slot header - An element shown at the top of the menu.
 * @slot checked-indicator - An icon element indicating a checked menu-item.
 *
 * @attr {string} mediaaudiotrackenabled - (read-only) Set to the enabled audio track.
 * @attr {string} mediaaudiotracklist - (read-only) Set to the audio track list.
 */
declare class MediaAudioTrackMenu extends MediaChromeMenu {
    #private;
    static get observedAttributes(): string[];
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
    /**
     * Returns the anchor element when it is a floating menu.
     */
    get anchorElement(): HTMLElement;
    get mediaAudioTrackList(): TextTrackLike[];
    set mediaAudioTrackList(list: TextTrackLike[]);
    /**
     * Get enabled audio track id.
     */
    get mediaAudioTrackEnabled(): string;
    set mediaAudioTrackEnabled(id: string);
}
export { MediaAudioTrackMenu };
export default MediaAudioTrackMenu;
