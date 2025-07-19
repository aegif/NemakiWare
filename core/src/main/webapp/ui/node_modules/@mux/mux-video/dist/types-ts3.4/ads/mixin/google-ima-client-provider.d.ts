/// <reference types="google_interactive_media_ads_types" preserve="true" />
import { GoogleImaClientAd } from './google-ima-client-ad.js';
import { IAdsVideoClientProvider } from './types.js';
export type GoogleImaClientProviderConfig = {
    adContainer: HTMLElement;
    videoElement: HTMLVideoElement;
    originalSize: DOMRect;
};
export declare class GoogleImaClientProvider extends EventTarget implements IAdsVideoClientProvider {
    private "GoogleImaClientProvider.#private";
    static isSDKAvailable(): boolean;
    constructor(config: GoogleImaClientProviderConfig);
    destroy(): void;
    unload(): void;
    readonly adsLoader: google.ima.AdsLoader;
    readonly ad: GoogleImaClientAd | undefined;
    readonly adBreak: boolean;
    readonly paused: boolean;
    readonly duration: number;
    readonly currentTime: number;
    volume: number;
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
