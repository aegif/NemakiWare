import React from 'react';
import type { PlayerEntry } from './players.js';
import type { ReactPlayerProps } from './types.js';
type Player = React.ForwardRefExoticComponent<ReactPlayerProps & {
    activePlayer: PlayerEntry['player'];
}>;
declare const Player: Player;
export default Player;
