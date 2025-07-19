import { globalThis } from './utils/server-safe-globals.js';
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 *
 * @attr {string} mediacontroller - The element `id` of the media controller to connect to (if not nested within).
 * @attr {string} mediapreviewimage - (read-only) Set to the timeline preview image URL.
 * @attr {string} mediapreviewcoords - (read-only) Set to the active preview image coordinates.
 *
 * @cssproperty [--media-preview-thumbnail-display = inline-block] - `display` property of display.
 * @cssproperty [--media-control-display = inline-block] - `display` property of control.
 */
declare class MediaPreviewThumbnail extends globalThis.HTMLElement {
    #private;
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static get observedAttributes(): ("mediacontroller" | "mediapreviewcoords" | "mediapreviewimage")[];
    imgWidth: number;
    imgHeight: number;
    constructor();
    connectedCallback(): void;
    disconnectedCallback(): void;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     * @type {string | undefined} The url of the preview image
     */
    get mediaPreviewImage(): any;
    set mediaPreviewImage(value: any);
    /**
     * @type {Array<number> | undefined} Fixed length array [x, y, width, height] or undefined
     */
    get mediaPreviewCoords(): number[];
    set mediaPreviewCoords(value: number[]);
    update(): void;
}
export default MediaPreviewThumbnail;
