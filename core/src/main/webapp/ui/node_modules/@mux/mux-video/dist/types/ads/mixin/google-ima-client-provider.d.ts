/// <reference types="google_interactive_media_ads_types" preserve="true" />
import { GoogleImaClientAd } from './google-ima-client-ad.js';
import { IAdsVideoClientProvider } from './types.js';
export type GoogleImaClientProviderConfig = {
    adContainer: HTMLElement;
    videoElement: HTMLVideoElement;
    originalSize: DOMRect;
};
export declare class GoogleImaClientProvider extends EventTarget implements IAdsVideoClientProvider {
    #private;
    static isSDKAvailable(): boolean;
    constructor(config: GoogleImaClientProviderConfig);
    destroy(): void;
    unload(): void;
    get adsLoader(): google.ima.AdsLoader;
    get ad(): GoogleImaClientAd | undefined;
    get adBreak(): boolean;
    get paused(): boolean;
    get duration(): number;
    get currentTime(): number;
    get volume(): number;
    set volume(val: number);
    play(): Promise<void>;
    pause(): void;
    /**
     * Initializes the ad display container video elements for playback.
     * You must call this method as a direct result of a user action,
     * so that the browser can mark the video element as user initiated.
     */
    initializeAdDisplayContainer(): void;
    requestAds(adTagUrl: string): void;
}
