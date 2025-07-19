function stringifyRenditionList(renditions) {
  return renditions == null ? void 0 : renditions.map(stringifyRendition).join(" ");
}
function parseRenditionList(renditions) {
  return renditions == null ? void 0 : renditions.split(/\s+/).map(parseRendition);
}
function stringifyRendition(rendition) {
  if (rendition) {
    const { id, width, height } = rendition;
    return [id, width, height].filter((a) => a != null).join(":");
  }
}
function parseRendition(rendition) {
  if (rendition) {
    const [id, width, height] = rendition.split(":");
    return { id, width: +width, height: +height };
  }
}
function stringifyAudioTrackList(audioTracks) {
  return audioTracks == null ? void 0 : audioTracks.map(stringifyAudioTrack).join(" ");
}
function parseAudioTrackList(audioTracks) {
  return audioTracks == null ? void 0 : audioTracks.split(/\s+/).map(parseAudioTrack);
}
function stringifyAudioTrack(audioTrack) {
  if (audioTrack) {
    const { id, kind, language, label } = audioTrack;
    return [id, kind, language, label].filter((a) => a != null).join(":");
  }
}
function parseAudioTrack(audioTrack) {
  if (audioTrack) {
    const [id, kind, language, label] = audioTrack.split(":");
    return {
      id,
      kind,
      language,
      label
    };
  }
}
function dashedToCamel(word) {
  return word.split("-").map(function(x, i) {
    return (i ? x[0].toUpperCase() : x[0].toLowerCase()) + x.slice(1).toLowerCase();
  }).join("");
}
function constToCamel(word, upperFirst = false) {
  return word.split("_").map(function(x, i) {
    return (i || upperFirst ? x[0].toUpperCase() : x[0].toLowerCase()) + x.slice(1).toLowerCase();
  }).join("");
}
function camelCase(name) {
  return name.replace(/[-_]([a-z])/g, ($0, $1) => $1.toUpperCase());
}
function isValidNumber(x) {
  return typeof x === "number" && !Number.isNaN(x) && Number.isFinite(x);
}
function isNumericString(str) {
  if (typeof str != "string")
    return false;
  return !isNaN(str) && !isNaN(parseFloat(str));
}
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const capitalize = (str) => str && str[0].toUpperCase() + str.slice(1);
export {
  camelCase,
  capitalize,
  constToCamel,
  dashedToCamel,
  delay,
  isNumericString,
  isValidNumber,
  parseAudioTrack,
  parseAudioTrackList,
  parseRendition,
  parseRenditionList,
  stringifyAudioTrack,
  stringifyAudioTrackList,
  stringifyRendition,
  stringifyRenditionList
};
