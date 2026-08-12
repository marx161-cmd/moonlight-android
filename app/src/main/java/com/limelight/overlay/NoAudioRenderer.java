package com.limelight.overlay;

import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Renders NO audio for the artemisd daemon: opens no AudioTrack and discards every
 * decoded frame. The normal AndroidAudioRenderer opens a low-latency track
 * (USAGE_GAME + PERFORMANCE_MODE_LOW_LATENCY) which drives the Pixel's always-on-compute
 * audio DSP (the `aoc` HAL) and pinned it near 100% CPU even though nothing was audible
 * (audioEnabled=false only stops HOST-side playback, not the client track).
 *
 * We can't skip audio entirely: moonlight-common-c requires the audio init to succeed
 * (AudioStream.c: `if (err != 0) return err;` aborts the whole connection), and audio is
 * negotiated at the protocol level regardless of the renderer. So setup() must return 0.
 * This is the furthest safe "off": no track, no output, no AoC load — only the cheap Opus
 * decode remains.
 */
public class NoAudioRenderer implements AudioRenderer {
    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        return 0; // success, but deliberately no AudioTrack
    }

    @Override public void start() {}
    @Override public void stop() {}
    @Override public void playDecodedAudio(short[] audioData) { /* discard */ }
    @Override public void cleanup() {}
}
