export declare class AttributeTokenList implements Pick<DOMTokenList, 'length' | 'value' | 'toString' | 'item' | 'add' | 'remove' | 'contains' | 'toggle' | 'replace'> {
    #private;
    constructor(el?: HTMLElement, attr?: string, { defaultValue }?: {
        defaultValue: any;
    });
    [Symbol.iterator](): SetIterator<string>;
    get length(): number;
    get value(): string;
    set value(val: string);
    toString(): string;
    item(index: any): string;
    values(): Iterable<string>;
    forEach(callback: (value: string, key: string, parent: Set<string>) => void, thisArg?: any): void;
    add(...tokens: string[]): void;
    remove(...tokens: string[]): void;
    contains(token: string): boolean;
    toggle(token: string, force: boolean): boolean;
    replace(oldToken: string, newToken: string): boolean;
}
