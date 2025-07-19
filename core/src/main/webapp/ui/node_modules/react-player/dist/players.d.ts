/// <reference types="react" />
import type { VideoElementProps } from './types.js';
export type PlayerEntry = {
    key: string;
    name: string;
    canPlay: (src: string) => boolean;
    canEnablePIP?: () => boolean;
    player?: React.ComponentType<VideoElementProps> | React.LazyExoticComponent<React.ComponentType<VideoElementProps>>;
};
declare const Players: PlayerEntry[];
export default Players;
