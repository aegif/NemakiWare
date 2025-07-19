export type InvokeEventInit = EventInit & {
    action?: string;
    relatedTarget: Element;
};
/**
 * Dispatch an InvokeEvent on the target element to perform an action.
 * The default action is auto, which is determined by the target element.
 * In our case it's only used for toggling a menu.
 */
export declare class InvokeEvent extends Event {
    action: string;
    relatedTarget: Element;
    /**
     * @param init - The event options.
     */
    constructor({ action, relatedTarget, ...options }: InvokeEventInit);
}
export type ToggleState = 'open' | 'closed';
export type ToggleEventInit = EventInit & {
    newState: ToggleState;
    oldState: ToggleState;
};
/**
 * Similar to the popover toggle event.
 * https://developer.mozilla.org/en-US/docs/Web/API/ToggleEvent
 */
export declare class ToggleEvent extends Event {
    newState: ToggleState;
    oldState: ToggleState;
    /**
     * @param init - The event options.
     */
    constructor({ newState, oldState, ...options }: ToggleEventInit);
}
