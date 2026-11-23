package de.df.jauswertung.gui.plugins.zeitnahme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ZeitnahmeSimulatorTest {
    private final ZeitnahmeSimulator.Netz stabil = new ZeitnahmeSimulator.Netz(2_000, 0, 0);
    private final ZeitnahmeSimulator.Netz gestoert = new ZeitnahmeSimulator.Netz(2_000, 0, 24_000);

    @Test
    void reproduziertFehlerfreieVierBahnenUndTimeouts() {
        var bereit = ZeitnahmeSimulator.simulieren(einstellungen(stabil, stabil, false, 0), 7, 3);
        var blockiert = ZeitnahmeSimulator.simulieren(einstellungen(gestoert, gestoert, false, 0), 7, 3);

        assertEquals(3, bereit.bereit());
        assertTrue(bereit.ersterLauf().bahnfehler().stream().allMatch(fehler -> fehler == 0));
        assertEquals(4, bereit.ersterLauf().bahnfehler().size());
        assertEquals(0, blockiert.bereit());
        assertEquals(3, blockiert.timeouts());
    }

    @Test
    void erholtSichNachNeuverbindung() {
        var einstellungen = einstellungen(gestoert, stabil, true, 0);
        var lauf = ZeitnahmeSimulator.simulieren(einstellungen, 42, 1).ersterLauf();

        assertTrue(lauf.bereit());
        assertTrue(lauf.wartezeit() >= 250_000);
        assertEquals(lauf, ZeitnahmeSimulator.simulieren(einstellungen, 42, 1).ersterLauf());
    }

    @Test
    void behaeltGueltigeSchaetzungBeiVerschlechterungUndDrift() {
        var lauf = ZeitnahmeSimulator.simulieren(einstellungen(stabil, gestoert, false, 1_000), 42, 1).ersterLauf();

        assertTrue(lauf.bereit());
        assertEquals(0, lauf.wartezeit());
        assertTrue(lauf.bahnfehler().stream().allMatch(fehler -> Math.abs(fehler) <= Uhrabgleich.BUDGET));
    }

    @Test
    void behandeltStoppuhrenOhneBahnsynchronisation() {
        var ohneTelefon = new ZeitnahmeSimulator.Einstellungen(gestoert, gestoert, false, false, true,
                0, 250_000, 2_000_000, 30_000_000);
        var mitTelefon = new ZeitnahmeSimulator.Einstellungen(gestoert, gestoert, false, true, true,
                0, 250_000, 2_000_000, 30_000_000);

        var lauf = ZeitnahmeSimulator.simulieren(ohneTelefon, 2, 1).ersterLauf();
        assertTrue(lauf.bereit());
        assertEquals(List.of(), lauf.bahnfehler());
        assertFalse(ZeitnahmeSimulator.simulieren(mitTelefon, 2, 1).ersterLauf().bereit());
    }

    private ZeitnahmeSimulator.Einstellungen einstellungen(ZeitnahmeSimulator.Netz zuerst, ZeitnahmeSimulator.Netz danach,
            boolean neuverbinden, int drift) {
        return new ZeitnahmeSimulator.Einstellungen(zuerst, danach, neuverbinden, true, false, drift,
                250_000, 2_000_000, 1_000_000);
    }
}
