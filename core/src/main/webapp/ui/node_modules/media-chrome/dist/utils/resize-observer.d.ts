type ResizeCallback = (entry: ResizeObserverEntry) => void;
export declare function observeResize(element: Element, callback: ResizeCallback): void;
export declare function unobserveResize(element: Element, callback: ResizeCallback): void;
export {};
