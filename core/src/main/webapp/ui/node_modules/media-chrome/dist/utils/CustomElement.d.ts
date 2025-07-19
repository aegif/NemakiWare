export declare class CustomElement extends HTMLElement {
    static get observedAttributes(): any[];
    attributeChangedCallback(attrName: string, // eslint-disable-line
    oldValue: string | null, // eslint-disable-line
    newValue: string | null): void;
    connectedCallback(): void;
    disconnectedCallback(): void;
}
