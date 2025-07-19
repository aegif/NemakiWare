/**
 * @typedef {Partial<HTMLVideoElement> & {
 *  webkitDisplayingFullscreen?: boolean;
 *  webkitPresentationMode?: 'fullscreen'|'picture-in-picture';
 *  webkitEnterFullscreen?: () => any;
 * }} MediaStateOwner
 */
/**
 * @typedef {Partial<Document|ShadowRoot>} RootNodeStateOwner
 */
/**
 * @typedef {Partial<HTMLElement>} FullScreenElementStateOwner
 */
/**
 * @typedef {object} StateOwners
 * @property {MediaStateOwner} [media]
 * @property {RootNodeStateOwner} [documentElement]
 * @property {FullScreenElementStateOwner} [fullscreenElement]
 */
/** @type {(stateOwners: StateOwners) => Promise<undefined> | undefined} */
export declare const enterFullscreen: (stateOwners: any) => Promise<any>;
/** @type {(stateOwners: StateOwners) => Promise<undefined> | undefined} */
export declare const exitFullscreen: (stateOwners: any) => Promise<any>;
/** @type {(stateOwners: StateOwners) => FullScreenElementStateOwner | null | undefined} */
export declare const getFullscreenElement: (stateOwners: any) => any;
/** @type {(stateOwners: StateOwners) => boolean} */
export declare const isFullscreen: (stateOwners: any) => boolean;
/** @type {(stateOwners: StateOwners) => boolean} */
export declare const isFullscreenEnabled: (stateOwners: any) => boolean;
