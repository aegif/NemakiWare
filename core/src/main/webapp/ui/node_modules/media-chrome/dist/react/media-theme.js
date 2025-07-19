import React from "react";
import { createComponent } from 'ce-la-react';
import * as Modules from "../media-theme.js";

function toAttributeValue(propValue) {
  if (typeof propValue === 'boolean') return propValue ? '' : undefined;
  if (typeof propValue === 'function') return undefined;
  const isPrimitive = (v) => typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean';
  if (Array.isArray(propValue) && propValue.every(isPrimitive)) return propValue.join(' ');
  if (typeof propValue === 'object' && propValue !== null) return undefined;
  return propValue;
}

export const MediaTheme = createComponent({
  tagName: "media-theme",
  elementClass: Modules.MediaTheme,
  react: React,
  toAttributeValue: toAttributeValue,
  defaultProps: {
    suppressHydrationWarning: true,
  },
});