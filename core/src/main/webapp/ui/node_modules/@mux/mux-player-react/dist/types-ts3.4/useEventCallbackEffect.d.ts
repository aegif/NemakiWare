import React from 'react';
import { GenericEventListener } from './index';
export declare const useEventCallbackEffect: <TElement extends EventTarget = EventTarget, TEventMap extends Record<string, Event> = Record<string, Event>, K extends keyof TEventMap = keyof TEventMap>(type: K, ref: // | ((instance: EventTarget | null) => void)
React.MutableRefObject<TElement | null> | null | undefined, callback: GenericEventListener<TEventMap[K]> | undefined) => void;
