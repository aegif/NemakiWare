import { MediaChromeButton } from '../media-chrome-button.js';
/**
 * @attr {string} invoketarget - The id of the element to invoke when clicked.
 */
declare class MediaChromeMenuButton extends MediaChromeButton {
    connectedCallback(): void;
    get invokeTarget(): string | null;
    set invokeTarget(value: string | null);
    /**
     * Returns the element with the id specified by the `invoketarget` attribute.
     * @return {HTMLElement | null}
     */
    get invokeTargetElement(): HTMLElement | null;
    handleClick(): void;
}
export { MediaChromeMenuButton };
export default MediaChromeMenuButton;
