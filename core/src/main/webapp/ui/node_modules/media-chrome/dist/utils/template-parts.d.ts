import { globalThis } from '../utils/server-safe-globals.js';
export type State = Record<string, any>;
export type Parts = [string, Part][];
export type Processor = {
    createCallback?: (instance: TemplateInstance, parts: Parts, state: State) => void;
    processCallback: (instance: TemplateInstance, parts: Parts, state: State) => void;
};
export declare const defaultProcessor: Processor;
/**
 *
 */
export declare class TemplateInstance extends globalThis.DocumentFragment {
    #private;
    constructor(template: HTMLTemplateElement, state?: State | null, processor?: Processor);
    update(state?: State): void;
}
export declare const parse: (element: Element, parts?: Parts) => Parts;
export declare const tokenize: (text: string) => [number, string][];
export declare class Part {
    get value(): string;
    set value(val: string);
    toString(): string;
}
type AttrPiece = AttrPart | string;
export declare class AttrPartList {
    #private;
    [Symbol.iterator](): IterableIterator<AttrPiece>;
    get length(): number;
    item(index: number): AttrPiece;
    append(...items: AttrPiece[]): void;
    toString(): string;
}
export declare class AttrPart extends Part {
    #private;
    constructor(element: Element, attributeName: string, namespaceURI: string | null);
    get attributeName(): string;
    get attributeNamespace(): string;
    get element(): Element;
    get value(): string;
    set value(newValue: string);
    get booleanValue(): boolean;
    set booleanValue(value: boolean);
}
export declare class ChildNodePart extends Part {
    #private;
    constructor(parentNode: Element, nodes?: ChildNode[]);
    get replacementNodes(): any;
    get parentNode(): any;
    get nextSibling(): any;
    get previousSibling(): any;
    get value(): any;
    set value(newValue: any);
    replace(...nodes: any[]): void;
}
export declare class InnerTemplatePart extends ChildNodePart {
    directive: string;
    expression: string;
    template: HTMLTemplateElement;
    constructor(parentNode: Element, template: HTMLTemplateElement);
}
export {};
