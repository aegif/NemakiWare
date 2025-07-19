import React from "react";
import { createComponent } from 'ce-la-react';
import * as Modules from "../index.js";

function toAttributeValue(propValue) {
  if (typeof propValue === 'boolean') return propValue ? '' : undefined;
  if (typeof propValue === 'function') return undefined;
  const isPrimitive = (v) => typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean';
  if (Array.isArray(propValue) && propValue.every(isPrimitive)) return propValue.join(' ');
  if (typeof propValue === 'object' && propValue !== null) return undefined;
  return propValue;
}

export const MediaGestureReceiver = createComponent({
  tagName: "media-gesture-receiver",
  elementClass: Modules.MediaGestureReceiver,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaContainer = createComponent({
  tagName: "media-container",
  elementClass: Modules.MediaContainer,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaController = createComponent({
  tagName: "media-controller",
  elementClass: Modules.MediaController,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaTooltip = createComponent({
  tagName: "media-tooltip",
  elementClass: Modules.MediaTooltip,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaChromeButton = createComponent({
  tagName: "media-chrome-button",
  elementClass: Modules.MediaChromeButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaAirplayButton = createComponent({
  tagName: "media-airplay-button",
  elementClass: Modules.MediaAirplayButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaCaptionsButton = createComponent({
  tagName: "media-captions-button",
  elementClass: Modules.MediaCaptionsButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaCastButton = createComponent({
  tagName: "media-cast-button",
  elementClass: Modules.MediaCastButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaChromeDialog = createComponent({
  tagName: "media-chrome-dialog",
  elementClass: Modules.MediaChromeDialog,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaChromeRange = createComponent({
  tagName: "media-chrome-range",
  elementClass: Modules.MediaChromeRange,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaControlBar = createComponent({
  tagName: "media-control-bar",
  elementClass: Modules.MediaControlBar,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaTextDisplay = createComponent({
  tagName: "media-text-display",
  elementClass: Modules.MediaTextDisplay,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaDurationDisplay = createComponent({
  tagName: "media-duration-display",
  elementClass: Modules.MediaDurationDisplay,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaErrorDialog = createComponent({
  tagName: "media-error-dialog",
  elementClass: Modules.MediaErrorDialog,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaFullscreenButton = createComponent({
  tagName: "media-fullscreen-button",
  elementClass: Modules.MediaFullscreenButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaLiveButton = createComponent({
  tagName: "media-live-button",
  elementClass: Modules.MediaLiveButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaLoadingIndicator = createComponent({
  tagName: "media-loading-indicator",
  elementClass: Modules.MediaLoadingIndicator,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaMuteButton = createComponent({
  tagName: "media-mute-button",
  elementClass: Modules.MediaMuteButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaPipButton = createComponent({
  tagName: "media-pip-button",
  elementClass: Modules.MediaPipButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaPlaybackRateButton = createComponent({
  tagName: "media-playback-rate-button",
  elementClass: Modules.MediaPlaybackRateButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaPlayButton = createComponent({
  tagName: "media-play-button",
  elementClass: Modules.MediaPlayButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaPosterImage = createComponent({
  tagName: "media-poster-image",
  elementClass: Modules.MediaPosterImage,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaPreviewChapterDisplay = createComponent({
  tagName: "media-preview-chapter-display",
  elementClass: Modules.MediaPreviewChapterDisplay,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaPreviewThumbnail = createComponent({
  tagName: "media-preview-thumbnail",
  elementClass: Modules.MediaPreviewThumbnail,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaPreviewTimeDisplay = createComponent({
  tagName: "media-preview-time-display",
  elementClass: Modules.MediaPreviewTimeDisplay,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaSeekBackwardButton = createComponent({
  tagName: "media-seek-backward-button",
  elementClass: Modules.MediaSeekBackwardButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaSeekForwardButton = createComponent({
  tagName: "media-seek-forward-button",
  elementClass: Modules.MediaSeekForwardButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaTimeDisplay = createComponent({
  tagName: "media-time-display",
  elementClass: Modules.MediaTimeDisplay,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaTimeRange = createComponent({
  tagName: "media-time-range",
  elementClass: Modules.MediaTimeRange,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaVolumeRange = createComponent({
  tagName: "media-volume-range",
  elementClass: Modules.MediaVolumeRange,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});