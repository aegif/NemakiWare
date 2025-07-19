import { globalThis } from './utils/server-safe-globals.js';
export declare const Attributes: {
    PLACEHOLDER_SRC: string;
    SRC: string;
};
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 * @attr {string} placeholdersrc - Placeholder image source URL, often a blurhash data URL.
 * @attr {string} src - Poster image source URL.
 *
 * @cssproperty --media-poster-image-display - `display` property of poster image.
 * @cssproperty --media-poster-image-background-position - `background-position` of poster image.
 * @cssproperty --media-poster-image-background-size - `background-size` of poster image.
 * @cssproperty --media-object-fit - `object-fit` of poster image.
 * @cssproperty --media-object-position - `object-position` of poster image.
 */
declare class MediaPosterImage extends globalThis.HTMLElement {
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static get observedAttributes(): string[];
    image: HTMLImageElement;
    constructor();
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    /**
     *
     */
    get placeholderSrc(): string | undefined;
    set placeholderSrc(value: string | undefined);
    /**
     *
     */
    get src(): string | undefined;
    set src(value: string | undefined);
}
export default MediaPosterImage;
