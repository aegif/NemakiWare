export declare const isServer: boolean;
export declare const GlobalThis: typeof globalThis;
export declare const Document: typeof globalThis['document'] & Partial<{
    webkitExitFullscreen: typeof globalThis['document']['exitFullscreen'];
}>;
export { GlobalThis as globalThis, Document as document };
