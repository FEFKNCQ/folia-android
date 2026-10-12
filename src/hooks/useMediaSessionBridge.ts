import { useEffect } from 'react';
import type { RefObject } from 'react';
import { PlayerState } from '../types';
import { isCapacitorAndroid } from '../platform/runtime';
import type { SongResult } from '../types';
import { getSongAlbumLabel, getSongArtistLabel, getSongCoverUrl } from '../services/onlineMusic/songMetadata';
import {
    getSupportedMediaSessionArtworkUrl,
    isMediaSessionSourceReady,
    publishMediaSessionTrack,
} from '../utils/mediaSessionSync';

// Bridges Folia playback state to the browser Media Session API.
type UseMediaSessionBridgeOptions = {
    audioRef: RefObject<HTMLAudioElement | null>;
    /**
     * The deck the displayed track is playing on, or null when the picture is live.
     *
     * Metadata, clock and source have to describe the same deck. `currentSong` is already the
     * DISPLAYED track - the outgoing one for the length of a blend - while `audioRef` names the
     * active deck, which by then is the arriving track. Publishing the two together put the outgoing
     * track's title on the incoming track's duration and position, so the system panel showed one
     * song's name over another song's progress. `audioSrc` is passed to match this element.
     */
    getDisplayAudioElement?: () => HTMLAudioElement | null;
    audioSrc: string | null;
    currentSong: SongResult | null;
    cachedCoverUrl: string | null;
    playerState: PlayerState;
    isNowPlayingStageActive: boolean;
    unknownArtistLabel: string;
    mediaSessionPlayRef: RefObject<() => Promise<void>>;
    mediaSessionPauseRef: RefObject<() => void>;
    mediaSessionPrevRef: RefObject<() => void>;
    mediaSessionNextRef: RefObject<() => Promise<void> | void>;
    isNowPlayingControlDisabledRef: RefObject<boolean>;
};

export const useMediaSessionBridge = ({
    audioRef,
    getDisplayAudioElement,
    audioSrc,
    currentSong,
    cachedCoverUrl,
    playerState,
    isNowPlayingStageActive,
    unknownArtistLabel,
    mediaSessionPlayRef,
    mediaSessionPauseRef,
    mediaSessionPrevRef,
    mediaSessionNextRef,
    isNowPlayingControlDisabledRef,
}: UseMediaSessionBridgeOptions) => {
    // [folia] 安卓原生媒体通知：把歌曲信息与播放状态推给原生 MediaSession
    //（KeepAliveService 的 MediaStyle 通知），让通知栏显示歌名/歌手/封面并带控制按钮。
    useEffect(() => {
        if (!isCapacitorAndroid()) return;
        (window as unknown as Record<string, unknown>).__foliaMediaCommands = {
            play: () => { void mediaSessionPlayRef.current?.(); },
            pause: () => { mediaSessionPauseRef.current?.(); },
            prev: () => { mediaSessionPrevRef.current?.(); },
            next: () => { void mediaSessionNextRef.current?.(); },
            seek: (ms: number) => {
                if (audioRef.current && Number.isFinite(ms)) {
                    audioRef.current.currentTime = ms / 1000;
                }
            },
        };
    }, []);

    useEffect(() => {
        if (!isCapacitorAndroid()) return;
        const nativeApi = (window as unknown as { FoliaMedia?: { updateMedia: (t: string, a: string, al: string, art: string, dur?: number) => void } | undefined }).FoliaMedia;
        if (!nativeApi || !currentSong) return;
        try {
            nativeApi.updateMedia(
                currentSong.name ?? '',
                getSongArtistLabel(currentSong),
                getSongAlbumLabel(currentSong),
                getSongCoverUrl(currentSong) ?? '',
                currentSong.durationMs ?? 0,
            );
        } catch {
            /* 原生侧未就绪时忽略 */
        }
    }, [currentSong]);

    useEffect(() => {
        if (!isCapacitorAndroid()) return;
        const nativeApi = (window as unknown as { FoliaMedia?: { updatePlayback: (playing: boolean, position: number) => void } | undefined }).FoliaMedia;
        if (!nativeApi) return;
        try {
            nativeApi.updatePlayback(playerState === PlayerState.PLAYING, audioRef.current?.currentTime ?? 0);
        } catch {
            /* ignore */
        }
    }, [playerState, audioSrc]);

    // [folia] 播放中每秒推送一次真实进度 —— 通知的进度条/时间随播放实时变动，
    // 并顺带纠正拖动进度与系统外推的偏差。
    useEffect(() => {
        if (!isCapacitorAndroid()) return;
        const audio = audioRef.current;
        const nativeApi = (window as unknown as { FoliaMedia?: { updatePlayback: (playing: boolean, position: number) => void } | undefined }).FoliaMedia;
        if (!audio || !nativeApi) return;
        let lastPush = 0;
        const onTimeUpdate = () => {
            const now = Date.now();
            if (now - lastPush < 1000) return;
            lastPush = now;
            try {
                nativeApi.updatePlayback(!audio.paused, audio.currentTime * 1000);
            } catch { /* ignore */ }
        };
        audio.addEventListener('timeupdate', onTimeUpdate);
        return () => audio.removeEventListener('timeupdate', onTimeUpdate);
    }, [audioSrc]);

    useEffect(() => {
        if (!('mediaSession' in navigator)) {
            return;
        }

        const mediaSession = navigator.mediaSession;
        const setActionHandlerSafely = (
            action: MediaSessionAction,
            handler: MediaSessionActionHandler | null
        ) => {
            try {
                mediaSession.setActionHandler(action, handler);
            } catch (e) {
                console.warn(`[MediaSession] Failed to bind ${action} handler`, e);
            }
        };

        setActionHandlerSafely('play', async () => {
            if (isNowPlayingControlDisabledRef.current || !audioRef.current) {
                return;
            }

            try {
                await mediaSessionPlayRef.current();
            } catch (e) {
                console.error('MediaSession play failed', e);
            }
        });
        setActionHandlerSafely('pause', () => {
            if (isNowPlayingControlDisabledRef.current || !audioRef.current) {
                return;
            }

            mediaSessionPauseRef.current();
        });
        setActionHandlerSafely('previoustrack', () => {
            if (isNowPlayingControlDisabledRef.current) {
                return;
            }
            mediaSessionPrevRef.current();
        });
        setActionHandlerSafely('nexttrack', () => {
            if (isNowPlayingControlDisabledRef.current) {
                return;
            }
            void mediaSessionNextRef.current();
        });

        return () => {
            setActionHandlerSafely('play', null);
            setActionHandlerSafely('pause', null);
            setActionHandlerSafely('previoustrack', null);
            setActionHandlerSafely('nexttrack', null);
        };
    }, [audioRef, isNowPlayingControlDisabledRef, mediaSessionNextRef, mediaSessionPauseRef, mediaSessionPlayRef, mediaSessionPrevRef]);

    useEffect(() => {
        if (!('mediaSession' in navigator)) {
            return;
        }

        if (!currentSong) {
            try {
                navigator.mediaSession.setPositionState();
                navigator.mediaSession.metadata = null;
            } catch (e) {
                console.warn('[MediaSession] Failed to clear metadata', e);
            }
            return;
        }

        // The displayed track's own deck, so the position published below belongs to the title
        // published beside it. Falls back to the active deck whenever no blend is holding a picture.
        const audio = getDisplayAudioElement?.() ?? audioRef.current;
        if (!audio || !audioSrc) {
            return;
        }

        let disposed = false;
        const sourceArtworkUrl = cachedCoverUrl || getSongCoverUrl(currentSong) || '';
        let artworkUrl = getSupportedMediaSessionArtworkUrl(sourceArtworkUrl, document.baseURI);
        let disposableArtworkUrl: string | null = null;
        const publish = () => {
            if (disposed || !isMediaSessionSourceReady(audio, audioSrc, document.baseURI)) {
                return;
            }

            try {
                publishMediaSessionTrack(navigator.mediaSession, audio, {
                    title: currentSong.name,
                    artist: getSongArtistLabel(currentSong) || unknownArtistLabel,
                    album: getSongAlbumLabel(currentSong),
                    artworkUrl,
                });
            } catch (e) {
                console.warn('[MediaSession] Failed to update metadata', e);
            }
        };

        // MediaMetadata rejects Electron's custom protocol, so expose that image through a
        // short-lived blob URL while this track owns the platform media session.
        const prepareUnsupportedArtwork = async () => {
            if (!sourceArtworkUrl || artworkUrl) return;

            try {
                const response = await fetch(sourceArtworkUrl);
                if (!response.ok) throw new Error(`Artwork request failed: ${response.status}`);
                const artworkBlob = await response.blob();
                if (artworkBlob.size <= 0 || !artworkBlob.type.startsWith('image/')) {
                    throw new Error('Artwork response is not a valid image');
                }

                const objectUrl = URL.createObjectURL(artworkBlob);
                if (disposed) {
                    URL.revokeObjectURL(objectUrl);
                    return;
                }
                disposableArtworkUrl = objectUrl;
                artworkUrl = objectUrl;
                publish();
            } catch (e) {
                if (!disposed) console.warn('[MediaSession] Failed to prepare artwork', e);
            }
        };

        audio.addEventListener('loadedmetadata', publish);
        audio.addEventListener('durationchange', publish);
        // Re-publish after playback starts in case Chromium delivered a late clear from the old source.
        audio.addEventListener('playing', publish);
        publish();
        void prepareUnsupportedArtwork();

        return () => {
            disposed = true;
            audio.removeEventListener('loadedmetadata', publish);
            audio.removeEventListener('durationchange', publish);
            audio.removeEventListener('playing', publish);
            if (disposableArtworkUrl) URL.revokeObjectURL(disposableArtworkUrl);
        };
    }, [audioRef, audioSrc, cachedCoverUrl, currentSong, getDisplayAudioElement, unknownArtistLabel]);

    useEffect(() => {
        if (!('mediaSession' in navigator)) {
            return;
        }

        try {
            navigator.mediaSession.playbackState = isNowPlayingStageActive
                ? 'none'
                : currentSong
                    ? (playerState === PlayerState.PLAYING ? 'playing' : 'paused')
                    : 'none';
        } catch (e) {
            console.warn('[MediaSession] Failed to update playback state', e);
        }
    }, [currentSong, isNowPlayingStageActive, playerState]);
};
