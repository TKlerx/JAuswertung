package de.df.jauswertung.gui.plugins.zeitnahme;

import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;

final class Startsignal {
    static final int STANDARD = 400;
    static final int COUNTDOWN_STANDARD = 800;
    static final int MINIMUM = 200;
    static final int MAXIMUM = 1_500;
    static final int COUNTDOWN_MS = 3_000;
    private static final int ABTASTRATE = 48_000;
    private static final AudioFormat FORMAT = new AudioFormat(ABTASTRATE, 16, 1, true, false);

    private Startsignal() {
    }

    static long jetzt() {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime());
    }

    static byte[] ton(int millisekunden) {
        if (millisekunden < MINIMUM || millisekunden > MAXIMUM) {
            throw new IllegalArgumentException();
        }
        return ton(millisekunden, 415);
    }

    static byte[] countdown() {
        int bytesProSekunde = ABTASTRATE * 2;
        byte[] daten = new byte[COUNTDOWN_MS / 1000 * bytesProSekunde];
        byte[] kurzton = ton(120, 700);
        for (int beginn = 0; beginn < daten.length; beginn += bytesProSekunde) {
            System.arraycopy(kurzton, 0, daten, beginn, kurzton.length);
        }
        return daten;
    }

    private static byte[] ton(int millisekunden, int frequenz) {
        int anzahl = ABTASTRATE * millisekunden / 1000;
        byte[] daten = new byte[anzahl * 2];
        for (int i = 0; i < anzahl; i++) {
            double phase = 2 * Math.PI * frequenz * i / ABTASTRATE;
            double huelle = Math.min(1.0, Math.min(i / 240.0, (anzahl - i) / 960.0));
            short wert = (short) (12_000 * huelle * (Math.sin(phase) + 0.25 * Math.sin(2 * phase)));
            daten[2 * i] = (byte) wert;
            daten[2 * i + 1] = (byte) (wert >> 8);
        }
        return daten;
    }

    static Clip vorbereiten(int millisekunden) throws LineUnavailableException {
        return vorbereiten(ton(millisekunden));
    }

    static Clip countdownVorbereiten() throws LineUnavailableException {
        return vorbereiten(countdown());
    }

    private static Clip vorbereiten(byte[] daten) throws LineUnavailableException {
        var clip = AudioSystem.getClip();
        try {
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                }
            });
            clip.open(FORMAT, daten, 0, daten.length);
            return clip;
        } catch (LineUnavailableException | RuntimeException ex) {
            clip.close();
            throw ex;
        }
    }

    static long abspielen(Clip clip, LongPredicate freigabe) throws LineUnavailableException {
        long signalbeginn = jetzt();
        if (!freigabe.test(signalbeginn)) {
            throw new LineUnavailableException();
        }
        clip.start();
        return signalbeginn;
    }
}
