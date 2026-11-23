package de.df.jauswertung.gui.plugins.zeitnahme;

import java.lang.reflect.Proxy;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Clip;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.spi.MixerProvider;

public final class PilotAudio extends MixerProvider {
    private final Mixer.Info info = new Mixer.Info("Pilot", "JAuswertung", "CI audio sink", "1") { };

    @Override
    public Mixer.Info[] getMixerInfo() {
        return new Mixer.Info[]{info};
    }

    @Override
    public Mixer getMixer(Mixer.Info requested) {
        if (requested != null && requested != info) throw new IllegalArgumentException();
        return (Mixer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Mixer.class},
                (object, method, arguments) -> switch (method.getName()) {
                    case "getMixerInfo" -> info;
                    case "isLineSupported" -> ((Line.Info) arguments[0]).getLineClass() == Clip.class;
                    case "getMaxLines" -> 2;
                    case "getLine" -> clip();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Clip clip() {
        var open = new java.util.concurrent.atomic.AtomicBoolean();
        return (Clip) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Clip.class},
                (object, method, arguments) -> switch (method.getName()) {
                    case "open" -> {
                        var format = (AudioFormat) arguments[0];
                        var samples = (byte[]) arguments[1];
                        var length = (int) arguments[3];
                        if (format.getChannels() != 1 || format.getSampleRate() != 48_000
                                || length <= 0 || length != samples.length) throw new IllegalArgumentException();
                        open.set(true);
                        yield null;
                    }
                    case "start" -> {
                        if (!open.get()) throw new IllegalStateException("Clip is closed");
                        yield null;
                    }
                    case "close" -> {
                        open.set(false);
                        yield null;
                    }
                    case "addLineListener" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
