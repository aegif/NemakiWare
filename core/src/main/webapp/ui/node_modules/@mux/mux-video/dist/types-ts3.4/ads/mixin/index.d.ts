import { CustomVideoElement } from 'custom-media-element';
import { Constructor, IAdsVideo } from './types.js';
export * from './events.js';
export * from './types.js';
export declare const Attributes: {
    readonly AD_TAG_URL: "ad-tag-url";
    readonly ALLOW_AD_BLOCKER: "allow-ad-blocker";
};
export declare function AdsVideoMixin<T extends CustomVideoElement>(superclass: T): Constructor<IAdsVideo> & T;
