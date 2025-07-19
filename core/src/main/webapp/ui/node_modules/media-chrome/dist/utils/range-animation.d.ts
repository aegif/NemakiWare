type Range = {
    valueAsNumber: number;
};
/**
 * Smoothly animate a range input accounting for hiccups and diverging playback.
 */
export declare class RangeAnimation {
    #private;
    fps: number;
    callback: (value: number) => void;
    duration: number;
    playbackRate: number;
    constructor(range: Range, callback: (value: number) => void, fps: number);
    start(): void;
    stop(): void;
    update({ start, duration, playbackRate }: {
        start: any;
        duration: any;
        playbackRate: any;
    }): void;
}
export {};
