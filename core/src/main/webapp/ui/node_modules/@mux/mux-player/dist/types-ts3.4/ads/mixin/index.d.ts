import { MuxPlayerElementConstructor, Constructor, IAdsPlayer } from './types.js';
export declare const Attributes: {
    readonly AD_TAG_URL: "ad-tag-url";
    readonly ALLOW_AD_BLOCKER: "allow-ad-blocker";
};
export declare function AdsPlayerMixin<T extends MuxPlayerElementConstructor>(superclass: T): Constructor<IAdsPlayer> & T;
