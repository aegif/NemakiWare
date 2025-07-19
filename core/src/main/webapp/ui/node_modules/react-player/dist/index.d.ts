/// <reference types="react" />
declare const _default: import("react").ForwardRefExoticComponent<Omit<import("./types.js").ReactPlayerProps, "ref"> & import("react").RefAttributes<HTMLVideoElement>> & Partial<{
    addCustomPlayer: (player: import("./players.js").PlayerEntry) => void;
    removeCustomPlayers: () => void;
    canPlay: (src: string) => boolean;
    canEnablePIP: (src: string) => boolean;
}>;
export default _default;
