package org.telegram.messenger;

/** Stateless PCM gain, independent of Android so limiter behavior can be verified. */
public final class GhostAudioGain {
    private GhostAudioGain() {}
    public static short apply(short sample) {
        double value = sample / 32768.0 * 1.8;
        double magnitude = Math.abs(value);
        if (magnitude > .85) value = Math.copySign(.85 + .15 * Math.tanh((magnitude - .85) / .15), value);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 32768)));
    }
}
