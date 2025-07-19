// As defined on the list of supported events: https://reactjs.org/docs/events.html
export const clipboardEvents = ['onCopy', 'onCut', 'onPaste'];
export const compositionEvents = [
    'onCompositionEnd',
    'onCompositionStart',
    'onCompositionUpdate',
];
export const focusEvents = ['onFocus', 'onBlur'];
export const formEvents = ['onInput', 'onInvalid', 'onReset', 'onSubmit'];
export const imageEvents = ['onLoad', 'onError'];
export const keyboardEvents = ['onKeyDown', 'onKeyPress', 'onKeyUp'];
export const mediaEvents = [
    'onAbort',
    'onCanPlay',
    'onCanPlayThrough',
    'onDurationChange',
    'onEmptied',
    'onEncrypted',
    'onEnded',
    'onError',
    'onLoadedData',
    'onLoadedMetadata',
    'onLoadStart',
    'onPause',
    'onPlay',
    'onPlaying',
    'onProgress',
    'onRateChange',
    'onSeeked',
    'onSeeking',
    'onStalled',
    'onSuspend',
    'onTimeUpdate',
    'onVolumeChange',
    'onWaiting',
];
export const mouseEvents = [
    'onClick',
    'onContextMenu',
    'onDoubleClick',
    'onMouseDown',
    'onMouseEnter',
    'onMouseLeave',
    'onMouseMove',
    'onMouseOut',
    'onMouseOver',
    'onMouseUp',
];
export const dragEvents = [
    'onDrag',
    'onDragEnd',
    'onDragEnter',
    'onDragExit',
    'onDragLeave',
    'onDragOver',
    'onDragStart',
    'onDrop',
];
export const selectionEvents = ['onSelect'];
export const touchEvents = ['onTouchCancel', 'onTouchEnd', 'onTouchMove', 'onTouchStart'];
export const pointerEvents = [
    'onPointerDown',
    'onPointerMove',
    'onPointerUp',
    'onPointerCancel',
    'onGotPointerCapture',
    'onLostPointerCapture',
    'onPointerEnter',
    'onPointerLeave',
    'onPointerOver',
    'onPointerOut',
];
export const uiEvents = ['onScroll'];
export const wheelEvents = ['onWheel'];
export const animationEvents = [
    'onAnimationStart',
    'onAnimationEnd',
    'onAnimationIteration',
];
export const transitionEvents = ['onTransitionEnd'];
export const otherEvents = ['onToggle'];
export const changeEvents = ['onChange'];
export const allEvents = [
    ...clipboardEvents,
    ...compositionEvents,
    ...focusEvents,
    ...formEvents,
    ...imageEvents,
    ...keyboardEvents,
    ...mediaEvents,
    ...mouseEvents,
    ...dragEvents,
    ...selectionEvents,
    ...touchEvents,
    ...pointerEvents,
    ...uiEvents,
    ...wheelEvents,
    ...animationEvents,
    ...transitionEvents,
    ...changeEvents,
    ...otherEvents,
];
/**
 * Returns an object with on-event callback props curried with provided args.
 *
 * @template ArgsType Type of arguments to curry on-event callbacks with.
 * @param {PropsType} props Props passed to a component.
 * @param {GetArgs<ArgsType>} [getArgs] A function that returns argument(s) on-event callbacks
 *   shall be curried with.
 */
export default function makeEventProps(props, getArgs) {
    const eventProps = {};
    for (const eventName of allEvents) {
        const eventHandler = props[eventName];
        if (!eventHandler) {
            continue;
        }
        if (getArgs) {
            eventProps[eventName] = ((event) => eventHandler(event, getArgs(eventName)));
        }
        else {
            eventProps[eventName] = eventHandler;
        }
    }
    return eventProps;
}
