export declare const getTestMediaEl: () => HTMLVideoElement;
/**
 * Test for volume support
 *
 * @param mediaEl - The media element to test
 */
export declare const hasVolumeSupportAsync: (mediaEl?: HTMLVideoElement) => Promise<boolean>;
/**
 * Test for PIP support
 *
 * @param mediaEl - The media element to test
 */
export declare const hasPipSupport: (mediaEl?: HTMLVideoElement) => boolean;
/**
 * Test for Fullscreen support
 *
 * @param mediaEl - The media element to test
 */
export declare const hasFullscreenSupport: (mediaEl?: HTMLVideoElement) => boolean;
export declare const fullscreenSupported: boolean;
export declare const pipSupported: boolean;
export declare const airplaySupported: boolean;
export declare const castSupported: boolean;
