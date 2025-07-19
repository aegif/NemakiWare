import MediaController from './media-controller.js';
import { globalThis } from './utils/server-safe-globals.js';
import { TemplateInstance } from './utils/template-parts.js';
export * from './utils/template-parts.js';
/**
 * @extends {HTMLElement}
 *
 * @attr {string} template - The element `id` of the template to render.
 */
export declare class MediaThemeElement extends globalThis.HTMLElement {
    #private;
    static template: HTMLTemplateElement;
    static observedAttributes: string[];
    static processor: import("./utils/template-parts.js").Processor;
    renderRoot: ShadowRoot;
    renderer?: TemplateInstance;
    constructor();
    /** @type {HTMLElement & { breakpointsComputed?: boolean }} */
    get mediaController(): MediaController;
    get template(): HTMLTemplateElement;
    set template(element: HTMLTemplateElement);
    get props(): {};
    attributeChangedCallback(attrName: string, oldValue: string, newValue: string | null): void;
    connectedCallback(): void;
    createRenderer(): void;
    render(): void;
}
