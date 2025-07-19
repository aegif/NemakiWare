import { MediaChromeDialog } from './media-chrome-dialog.js';
type MediaErrorLike = {
    code: number;
    message: string;
    [key: string]: any;
};
declare function getSlotTemplateHTML(attrs: Record<string, string>): string;
declare function formatErrorMessage(error: MediaErrorLike): string;
/**
 * @attr {number} mediaerrorcode - (read-only) The error code for the current media error.
 * @attr {string} mediaerrormessage - (read-only) The error message for the current media error.
 *
 * @cssproperty --media-control-background - `background` of control.
 */
declare class MediaErrorDialog extends MediaChromeDialog {
    #private;
    static getSlotTemplateHTML: typeof getSlotTemplateHTML;
    static formatErrorMessage: typeof formatErrorMessage;
    static get observedAttributes(): string[];
    formatErrorMessage(error: MediaErrorLike): string;
    attributeChangedCallback(attrName: string, oldValue: string | null, newValue: string | null): void;
    get mediaError(): MediaErrorLike | null;
    set mediaError(value: MediaErrorLike | null);
    get mediaErrorCode(): number;
    set mediaErrorCode(value: number);
    get mediaErrorMessage(): any;
    set mediaErrorMessage(value: any);
}
export { MediaErrorDialog };
export default MediaErrorDialog;
