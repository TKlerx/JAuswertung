package de.df.jauswertung.gui.plugins.zeitnahme;

import java.io.BufferedReader;
import java.io.InputStreamReader;

final class PcHornProbe {
    public static void main(String[] args) throws Exception {
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            for (String command; (command = input.readLine()) != null;) {
                if (command.equals("clock")) {
                    System.out.println(Startsignal.jetzt());
                    continue;
                }
                var clip = Startsignal.vorbereiten(Integer.parseInt(command));
                boolean started = false;
                try {
                    long start = Startsignal.abspielen(clip, ignored -> true);
                    started = true;
                    System.out.println(start);
                } finally {
                    if (!started) {
                        clip.close();
                    }
                }
            }
        }
    }
}
