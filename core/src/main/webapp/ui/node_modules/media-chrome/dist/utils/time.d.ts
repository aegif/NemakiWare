/**
 * This function converts numeric seconds into a phrase
 * @param {number} seconds - a (positive or negative) time, represented as seconds
 * @returns {string} The time, represented as a phrase of hours, minutes, and seconds
 */
export declare const formatAsTimePhrase: (seconds: any) => string;
/**
 * Converts a time, in numeric seconds, to a formatted string representation of the form [HH:[MM:]]SS, where hours and minutes
 * are optional, either based on the value of `seconds` or (optionally) based on the value of `guide`.
 *
 * @param seconds - The total time you'd like formatted, in seconds
 * @param guide - A number in seconds that represents how many units you'd want to show. This ensures consistent formatting between e.g. 35s and 4834s.
 * @returns A string representation of the time, with expected units
 */
export declare function formatTime(seconds: number, guide?: number): string;
export declare const emptyTimeRanges: TimeRanges;
/**
 */
export declare function serializeTimeRanges(timeRanges?: TimeRanges): string;
