import React from 'react';
import '@mux/mux-player/ads';
import { GenericEventListener, Props as MuxPlayerIndexProps } from '@mux/mux-player-react';
import MuxPlayerElement from '@mux/mux-player/ads';
import { EventMap as MuxPlayerElementEventMap } from '@mux/mux-player/ads';
export interface MuxPlayerProps extends Pick<MuxPlayerIndexProps, Exclude<keyof MuxPlayerIndexProps, 'playerSoftwareVersion' | 'playerSoftwareName'>> {
    adTagUrl?: string;
    allowAdBlocker?: boolean;
    onAdRequest?: GenericEventListener<MuxPlayerElementEventMap['adrequest']>;
    onAdResponse?: GenericEventListener<MuxPlayerElementEventMap['adresponse']>;
    onAdImpression?: GenericEventListener<MuxPlayerElementEventMap['adimpression']>;
    onAdBreakStart?: GenericEventListener<MuxPlayerElementEventMap['adbreakstart']>;
    onAdPlay?: GenericEventListener<MuxPlayerElementEventMap['adplay']>;
    onAdPlaying?: GenericEventListener<MuxPlayerElementEventMap['adplaying']>;
    onAdPause?: GenericEventListener<MuxPlayerElementEventMap['adpause']>;
    onAdFirstQuartile?: GenericEventListener<MuxPlayerElementEventMap['adfirstquartile']>;
    onAdMidpoint?: GenericEventListener<MuxPlayerElementEventMap['admidpoint']>;
    onAdThirdQuartile?: GenericEventListener<MuxPlayerElementEventMap['adthirdquartile']>;
    onAdError?: GenericEventListener<MuxPlayerElementEventMap['aderror']>;
    onAdClick?: GenericEventListener<MuxPlayerElementEventMap['adclick']>;
    onAdSkip?: GenericEventListener<MuxPlayerElementEventMap['adskip']>;
    onAdEnded?: GenericEventListener<MuxPlayerElementEventMap['adended']>;
    onAdBreakEnd?: GenericEventListener<MuxPlayerElementEventMap['adbreakend']>;
    onAdClose?: GenericEventListener<MuxPlayerElementEventMap['adclose']>;
}
declare const MuxPlayerAds: React.ForwardRefExoticComponent<MuxPlayerProps & React.RefAttributes<MuxPlayerElement>>;
export default MuxPlayerAds;
