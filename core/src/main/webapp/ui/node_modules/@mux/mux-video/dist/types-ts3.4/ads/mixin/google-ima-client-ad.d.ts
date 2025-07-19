import { IAdsVideoClientAd } from './types.js';
export declare class GoogleImaClientAd implements IAdsVideoClientAd {
    private "GoogleImaClientAd.#private";
    constructor(ad: google.ima.Ad, manager: google.ima.AdsManager);
    isLinear(): boolean;
    isCustomPlaybackUsed(): boolean;
}
