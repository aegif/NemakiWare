import React from 'react';
import type { ReactPlayerProps } from './types.js';
import type { PlayerEntry } from './players.js';
type ReactPlayer = React.ForwardRefExoticComponent<Omit<ReactPlayerProps, 'ref'> & React.RefAttributes<HTMLVideoElement>> & Partial<{
    addCustomPlayer: (player: PlayerEntry) => void;
    removeCustomPlayers: () => void;
    canPlay: (src: string) => boolean;
    canEnablePIP: (src: string) => boolean;
}>;
export declare const createReactPlayer: (players: PlayerEntry[], playerFallback: PlayerEntry) => ReactPlayer;
export {};
