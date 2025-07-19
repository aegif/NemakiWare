import React from 'react';
import type { VideoElementProps } from './types.js';
declare const HtmlPlayer: React.ForwardRefExoticComponent<Omit<VideoElementProps, "ref"> & React.RefAttributes<HTMLVideoElement>>;
export default HtmlPlayer;
