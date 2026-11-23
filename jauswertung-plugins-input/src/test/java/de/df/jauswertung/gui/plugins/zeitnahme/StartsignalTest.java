package de.df.jauswertung.gui.plugins.zeitnahme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StartsignalTest {
    @Test
    void countdownHatDreiKurzeToeneImSekundentakt() {
        byte[] ton = Startsignal.countdown();
        int bytesProMillisekunde = 96;
        assertEquals(3000 * bytesProMillisekunde, ton.length);
        for (int sekunde = 0; sekunde < 3; sekunde++) {
            int beginn = sekunde * 1000 * bytesProMillisekunde;
            int ende = beginn + 120 * bytesProMillisekunde;
            int anzahl = 0;
            for (int i = beginn; i < ende; i++) {
                if (ton[i] != 0) {
                    anzahl++;
                }
            }
            assertTrue(anzahl > 1000);
            for (int i = ende; i < beginn + 1000 * bytesProMillisekunde; i++) {
                assertEquals(0, ton[i]);
            }
        }
    }
}
