import type { TextTrackLike } from './TextTrackLike.js';
/**
 * Splits a string (representing TextTracks) into an array of strings based on whitespace.
 * @param textTracksStr - a string of 1+ "items" (representing TextTracks), separated by whitespace
 * @returns An array of non-whitesace strings (each representing a single TextTrack).
 */
export declare const splitTextTracksStr: (textTracksStr?: string) => string[];
/**
 * Parses a string that represents a TextTrack into a "TextTrack-like object"
 * The expected TextTrack string format is:
 * "language[:label]"
 * where the language *should* conform to BCP 47, just like TextTracks, and the (optional)
 * label *must* be URL encoded.
 * Note that this format may be expanded to include additional properties, such as
 * `id`.
 * @see https://developer.mozilla.org/en-US/docs/Web/API/TextTrack
 * @param textTrackStr - A well-defined TextTrack string representations
 * @returns An object that resembles a (partial) TextTrack (`{ language: string; label?: string; }`)
 */
export declare const parseTextTrackStr: (textTrackStr?: string) => TextTrackLike;
/**
 * Parses a whitespace-separated string that represents list of TextTracks into an array of TextTrack-like objects,
 * where each object will have the properties identified by the corresponding string, plus any properties generically
 * provided by the (optional) `textTrackLikeObj` argument.
 * @param textTracksStr - a string of 1+ "items" (representing TextTracks), separated by whitespace
 * @param textTrackLikeObj An object that resembles a (partial) TextTrack, used to add generic properties to all parsed TextTracks.
 * @returns An array of "TextTrack-like objects", each with properties parsed from the string and any properties from `textTrackLikeObj`.
 * @example
 * ```js
 * const tracksStr = 'en-US:English en:English%20%28with%20descriptions%29';
 * const tracks = parseTextTracksStr(tracksStr);
 * // [{ language: 'en-US', label: 'English' }, { language: 'en', label: 'English (with descriptions)' }];
 *
 * const tracksData = { kind: 'captions' };
 * const tracksWithData = parseTextTracksStr(tracksStr, tracksData);
 * // [{ language: 'en-US', label: 'English', kind: 'captions' }, { language: 'en', label: 'English (with descriptions)', kind: 'captions' }];
 * ```
 */
export declare const parseTextTracksStr: (textTracksStr?: string, textTrackLikeObj?: Partial<TextTrackLike>) => TextTrackLike[];
export type TrackOrTracks = string[] | TextTrackLike[] | string | TextTrackLike;
export type TextTrackListLike = TextTrackLike[] | TextTrackList;
/**
 * Takes a variety of possible representations of TextTrack(s) and "normalizes" them to an Array of 1+ TextTrack-like objects.
 * @param trackOrTracks - A value representing 1+ TextTracks
 * @returns An array of TextTrack-like objects.
 */
export declare const parseTracks: (trackOrTracks: TrackOrTracks) => TextTrackLike[];
/**
 * Translates a TextTrack-like object into a well-defined string representation for the TextTrack
 * @param obj - A TextTrack or TextTrack-like object
 * @returns {string} A string representing a TextTrack with the format: "language[:label]"
 */
export declare const formatTextTrackObj: ({ kind, label, language }?: TextTrackLike) => string;
/**
 * Translates a set of TextTracks into a well-defined, whitespace-separated string representation of the set
 * @see https://developer.mozilla.org/en-US/docs/Web/API/TextTrackList
 * @param textTracks - A TextTracks object or an Array of TextTracks or TextTrack-like objects.
 * @returns A string representing a set of TextTracks, separated by whitespace.
 */
export declare const stringifyTextTrackList: (textTracks?: TextTrackListLike) => string;
/**
 * A generic higher-order function that yields a predicate to assert whether or not some value has the provided key/value pair
 * @param key - The property key/name against which we'd like to match
 * @param value - The value of the key we expect for a match
 * @returns A predicate function that yields true if the provided object has the expected key/value pair, otherwise false.
 * @example
 * ```js
 * const hasShowingMode = isMatchingPropOf('mode', 'showing');
 * hasShowingMode({ mode: 'showing' }); // true
 * hasShowingMode({ mode: 'disabled' }); // false
 * hasShowingMode({ no_mode: 'any' }); // false
 * ```
 */
export declare const isMatchingPropOf: (key: string | number, value: any) => ((value: any) => boolean);
/**
 * A higher-order function that yields a single predicate to assert whether or not some value has *every* key/value pair defined in `filterObj`.
 * @param filterObj - An object of key/value pairs that we expect on a given object
 * @returns A predicate function that yields true iff the provided object has *every* key/value pair in `filterObj`, otherwise false
 * @example
 * ```js
 * const track1 = { label: 'English', kind: 'captions', language: 'en-US' };
 * const track1a = { label: 'English', kind: 'captions', language: 'en-US', id: '1', mode: 'showing' };
 * const track2 = { label: 'English (with descriptions)', kind: 'captions', language: 'en-US', id: '2', mode: 'disabled' };
 * const track3 = { label: 'Español', kind: 'subtitles', language: 'es-MX', id: '3', mode: 'disabled' };
 * const track4 = { label: 'English', language: 'en-US', mode: 'showing' };
 *
 * const isMatchingTrack = textTrackObjAsPred({ label: 'English', kind: 'captions', language: 'en-US' });
 * isMatchingTrack(track1); // true
 * isMatchingTrack(track1a); // true
 * isMatchingTrack(track2); // false
 * isMatchingTrack(track3); // false
 * isMatchingTrack(track4); // false
 * isMatchingTrack({ no_corresponding_props: 'any' }); // false
 * ```
 */
export declare const textTrackObjAsPred: (filterObj: any) => ((textTrack: TextTrackLike) => boolean);
/**
 * Updates any `tracks` that match one of the `tracksToUpdate` to be in the provided TextTrack `mode`.
 * @see https://developer.mozilla.org/en-US/docs/Web/API/TextTrack/mode
 * @see {@link parseTracks}
 * @param mode - The desired mode for any matching TextTracks. Should be one of "disabled"|"hidden"|"showing"
 * @param tracks - A TextTracks object or array of TextTracks that should contain any matching TextTracks to update
 * @param tracksToUpdate - A value representing a set of TextTracks
 */
export declare const updateTracksModeTo: (mode: TextTrackMode, tracks?: TextTrackListLike, tracksToUpdate?: TrackOrTracks) => void;
export type TrackFilter = (track: TextTrackLike) => boolean;
/**
 * Takes an `HTMLMediaElement media` and yields an array of `TextTracks` that match the provided `filterPredOrObj` criteria (or all `TextTracks` by default).
 * @see https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/textTracks
 * @see {@link textTrackObjAsPred}
 * @param media - An HTMLMediaElement with an expected textTracks value
 * (NOTE: This uses "structural polymorphism", so as long as `media` has an Array-like `textTracks` value of TextTrack-like objects, this function will work).
 * @param filterPredOrObj - Either a predicate function or an object that can be translated into a predicate function of matching key/value pairs.
 * @returns An array of TextTracks that match the given `filterPredOrObj` (or all TextTracks on `media` by default)
 * @example
 * ```html
 * <!DOCTYPE html>
 * <html lang="en">
 * <head></head>
 * <body>
 * <video src="https://stream.mux.com/DS00Spx1CV902MCtPj5WknGlR102V5HFkDe/high.mp4">
 *   <track label="Spanish" kind="subtitles" srclang="es" src="./vtt/en-sub.vtt">
 *   <track label="English" kind="subtitles" srclang="en" src="./vtt/es-sub.vtt">
 *   <track label="English" kind="captions" srclang="en" src="./vtt/en-cc.vtt">
  </video>
 * </body>
 * </html>
 * ```
 * ```js
 * // js ...
 * const media = document.querySelector('video');
 * getTextTracksList(media, { kind: 'subtitles' });
 * // [{ label: 'Spanish', kind: 'subtitles', language: 'es' }, { label: 'English', kind: 'subtitles', language: 'en' }]
 * getTextTracksList(media, { kind: 'captions' });
 * // [{ label: 'English', kind: 'captions', language: 'en' }]
 * getTextTracksList(media);
 * // [{ label: 'Spanish', kind: 'subtitles', language: 'es' }, { label: 'English', kind: 'subtitles', language: 'en' }, { label: 'English', kind: 'captions', language: 'en' }]
 * ```
 */
export declare const getTextTracksList: (media: HTMLVideoElement, filterPredOrObj?: TrackFilter | TextTrackLike) => TextTrackLike[];
/**
 * Are captions or subtitles enabled?
 *
 * @param el - An HTMLElement that has caption related attributes on it.
 * @returns Whether captions are enabled or not
 */
export declare const areSubsOn: (el: HTMLElement & {
    mediaSubtitlesShowing?: any[];
}) => boolean;
