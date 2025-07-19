import React from "react";
import { createComponent } from 'ce-la-react';
import * as Modules from "../../menu/index.js";

function toAttributeValue(propValue) {
  if (typeof propValue === 'boolean') return propValue ? '' : undefined;
  if (typeof propValue === 'function') return undefined;
  const isPrimitive = (v) => typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean';
  if (Array.isArray(propValue) && propValue.every(isPrimitive)) return propValue.join(' ');
  if (typeof propValue === 'object' && propValue !== null) return undefined;
  return propValue;
}

export const MediaChromeMenu = createComponent({
  tagName: "media-chrome-menu",
  elementClass: Modules.MediaChromeMenu,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaChromeMenuItem = createComponent({
  tagName: "media-chrome-menu-item",
  elementClass: Modules.MediaChromeMenuItem,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaSettingsMenu = createComponent({
  tagName: "media-settings-menu",
  elementClass: Modules.MediaSettingsMenu,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaSettingsMenuItem = createComponent({
  tagName: "media-settings-menu-item",
  elementClass: Modules.MediaSettingsMenuItem,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaChromeMenuButton = createComponent({
  tagName: "media-chrome-menu-button",
  elementClass: Modules.MediaChromeMenuButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaSettingsMenuButton = createComponent({
  tagName: "media-settings-menu-button",
  elementClass: Modules.MediaSettingsMenuButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaAudioTrackMenu = createComponent({
  tagName: "media-audio-track-menu",
  elementClass: Modules.MediaAudioTrackMenu,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaAudioTrackMenuButton = createComponent({
  tagName: "media-audio-track-menu-button",
  elementClass: Modules.MediaAudioTrackMenuButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaCaptionsMenu = createComponent({
  tagName: "media-captions-menu",
  elementClass: Modules.MediaCaptionsMenu,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaCaptionsMenuButton = createComponent({
  tagName: "media-captions-menu-button",
  elementClass: Modules.MediaCaptionsMenuButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaPlaybackRateMenu = createComponent({
  tagName: "media-playback-rate-menu",
  elementClass: Modules.MediaPlaybackRateMenu,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaPlaybackRateMenuButton = createComponent({
  tagName: "media-playback-rate-menu-button",
  elementClass: Modules.MediaPlaybackRateMenuButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaRenditionMenu = createComponent({
  tagName: "media-rendition-menu",
  elementClass: Modules.MediaRenditionMenu,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});

export const MediaRenditionMenuButton = createComponent({
  tagName: "media-rendition-menu-button",
  elementClass: Modules.MediaRenditionMenuButton,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});