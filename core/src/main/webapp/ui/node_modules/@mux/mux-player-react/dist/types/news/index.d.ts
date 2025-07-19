import React from 'react';
import { MuxPlayerProps } from '@mux/mux-player-react/ads';
export interface VideoItem {
    imageUrl: string;
    title: string;
    playbackId: string;
    adTagUrl: string | (() => string) | (() => Promise<string>);
}
export type PlaylistVideos = VideoItem[];
export interface PlaylistProps extends Omit<MuxPlayerProps, 'playbackId' | 'adTagUrl'> {
    videoList: PlaylistVideos;
}
declare const MuxNewsPlayer: ({ videoList, ...props }: PlaylistProps) => React.JSX.Element;
export default MuxNewsPlayer;
