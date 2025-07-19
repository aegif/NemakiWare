import { MediaChromeMenuItem } from './media-chrome-menu-item.js';
declare function getTemplateHTML(_attrs: Record<string, string>): string;
declare function getSuffixSlotInnerHTML(_attrs: Record<string, string>): string;
declare class MediaSettingsMenuItem extends MediaChromeMenuItem {
    static shadowRootOptions: {
        mode: ShadowRootMode;
    };
    static getTemplateHTML: typeof getTemplateHTML;
    static getSuffixSlotInnerHTML: typeof getSuffixSlotInnerHTML;
}
export { MediaSettingsMenuItem };
export default MediaSettingsMenuItem;
