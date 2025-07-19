import React from 'react';
import { VideoItem } from '.';
type PlaylistEndScreenProps = {
    currentIndex: number;
    relatedVideos: VideoItem[];
    visible: boolean;
    selectVideoCallback: (index: number) => void;
};
declare const PlaylistEndScreen: ({ currentIndex, relatedVideos, visible, selectVideoCallback, }: PlaylistEndScreenProps) => React.JSX.Element;
export default PlaylistEndScreen;
