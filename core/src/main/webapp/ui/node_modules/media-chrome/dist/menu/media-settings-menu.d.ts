import { MediaChromeMenu } from './media-chrome-menu.js';
declare function getTemplateHTML(_attrs: Record<string, string>): string;
/**
 * @extends {MediaChromeMenu}
 *
 * @cssproperty --media-settings-menu-justify-content - `justify-content` of the menu.
 */
declare class MediaSettingsMenu extends MediaChromeMenu {
    static getTemplateHTML: typeof getTemplateHTML;
    /**
     * Returns the anchor element when it is a floating menu.
     */
    get anchorElement(): HTMLElement;
}
export { MediaSettingsMenu };
export default MediaSettingsMenu;
