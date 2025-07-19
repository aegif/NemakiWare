import type { Point } from './Point.js';
import type { Rect } from './Rect.js';
export type PositionElements = {
    anchor: HTMLElement;
    floating: HTMLElement;
};
export type PositionRects = {
    anchor: Rect;
    floating: Rect;
};
export type Positions = PositionElements & {
    placement: string;
};
export declare function computePosition({ anchor, floating, placement, }: Positions): Point;
