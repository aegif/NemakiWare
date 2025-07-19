import { globalThis } from '../../utils/server-safe-globals.js';
/**
 *
 */
declare class MediaClipSelector extends globalThis.HTMLElement {
    static get observedAttributes(): string[];
    draggingEl: HTMLElement | null;
    wrapper: HTMLElement;
    selection: HTMLElement;
    playhead: HTMLElement;
    leftTrim: HTMLElement;
    spacerFirst: HTMLElement;
    startHandle: HTMLElement;
    spacerMiddle: HTMLElement;
    endHandle: HTMLElement;
    spacerLast: HTMLElement;
    initialX: number;
    thumbnailPreview: HTMLElement;
    _clickHandler: () => void;
    _dragStart: () => void;
    _dragEnd: () => void;
    _drag: () => void;
    constructor();
    get mediaDuration(): number;
    get mediaCurrentTime(): number;
    getPlayheadBasedOnMouseEvent(evt: MouseEvent): number;
    getXPositionFromMouse(evt: any): number;
    getMousePercent(evt: MouseEvent): number;
    dragStart(evt: MouseEvent): void;
    dragEnd(): void;
    setSelectionWidth(selectionPercent: number, fullTimelineWidth: number): void;
    drag(evt: MouseEvent): void;
    dispatchUpdate(): void;
    getCurrentClipBounds(): {
        startTime: number;
        endTime: number;
    };
    isTimestampInBounds(timestamp: number): boolean;
    handleClick(evt: MouseEvent): void;
    mediaCurrentTimeSet(): void;
    mediaUnsetCallback(media: HTMLVideoElement): void;
    enableThumbnails(): void;
    disableThumbnails(): void;
}
export default MediaClipSelector;
