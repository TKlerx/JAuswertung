package de.df.jauswertung.gui.plugins.zeitnahme;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import de.df.jauswertung.gui.plugins.WettkampfZugriff;
import de.df.jauswertung.daten.EinzelWettkampf;
import de.df.jauswertung.daten.laufliste.OWLauf;
import de.df.jauswertung.daten.regelwerk.Strafe;
import de.df.jauswertung.util.ergebnis.FormelILSOutdoor;

class ZeitnahmeErgebnisUebernahmeTest {
    @Test
    void prueftDenGesamtenSatzVorJederAenderungUndSchliesstNurBeiErfolg() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var zugriff = new WettkampfZugriff();
            var ereignisse = new AtomicInteger();
            var probe = ZeitnahmeLaufquelle.probewettkampf();
            var quelle = new ZeitnahmeLaufquelle(() -> probe);
            var auswahl = quelle.laeufe().getFirst();
            var sitzung = new ZeitnahmeVersuch.Sitzung();
            UUID telefon = UUID.randomUUID();
            var v = sitzung.anlegen(new ZeitnahmeVersuch.Laufstand(auswahl.wettkampf(), auswahl.lauf(), auswahl.disziplin(),
                    auswahl.bahnen().stream().map(tn -> new ZeitnahmeVersuch.Bahn(tn.bahn(), tn.name(),
                            tn.bahn() == 3 ? telefon : null, false)).toList(), null), 90_000_000);
            assertTrue(v.tischstartReservieren(0));
            assertTrue(v.startErfassen(new ZeitnahmeEreignis.Start(UUID.randomUUID(), v.id(), null, 0, null)));
            var uebernahme = new ZeitnahmeErgebnisUebernahme(zugriff::versuchen, quelle::lauf, ereignisse::incrementAndGet);
            assertTrue(v.stoppuhrErfassen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), null, 4, 63_210_000)));
            assertTrue(v.stoppuhrErfassen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), null, 3, 9_999)));
            var review = uebernahme.pruefen(auswahl, v);
            var zeilen = review.zeilen().stream().filter(zeile -> zeile.ergebnis() != null).toList();
            assertEquals(ZeitnahmeErgebnisUebernahme.Status.UNGUELTIG,
                    uebernahme.uebernehmen(auswahl, v, zeilen, false).status());
            assertEquals(0, ereignisse.get());
            var ziele = quelle.lauf(auswahl).orElseThrow().ziele();
            assertEquals(0, ziele.get(1).zeit());
            ziele.get(1).teilnehmer().addStrafe(0, new Strafe(30));
            var strafen = List.copyOf(ziele.get(1).teilnehmer().getStrafen(0));
            ziele.get(2).setzen(5000);
            assertTrue(v.stoppuhrErfassen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), null, 3,
                    (Integer.MAX_VALUE + 1L) * 10_000)));
            zeilen = uebernahme.pruefen(auswahl, v).zeilen().stream().filter(zeile -> zeile.ergebnis() != null).toList();
            assertEquals(ZeitnahmeErgebnisUebernahme.Status.UNGUELTIG,
                    uebernahme.uebernehmen(auswahl, v, zeilen, false).status());
            assertEquals(strafen, ziele.get(1).teilnehmer().getStrafen(0));
            var mobil = new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), telefon, 3, 10_000);
            assertTrue(sitzung.stoppuhrEmpfangen(mobil));
            assertTrue(v.stoppuhrErfassen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), null, 3, 10_000)));
            zeilen = uebernahme.pruefen(auswahl, v).zeilen().stream().filter(zeile -> zeile.ergebnis() != null).toList();
            try (var belegt = zugriff.versuchen()) {
                assertEquals(ZeitnahmeErgebnisUebernahme.Status.BELEGT,
                        uebernahme.uebernehmen(auswahl, v, zeilen, false).status());
            }
            ziele.get(1).setzen(5001);
            assertEquals(ZeitnahmeErgebnisUebernahme.Status.VERALTET,
                    uebernahme.uebernehmen(auswahl, v, zeilen, false).status());
            zeilen = uebernahme.pruefen(auswahl, v).zeilen().stream().filter(zeile -> zeile.ergebnis() != null).toList();
            assertEquals(ZeitnahmeErgebnisUebernahme.Status.UEBERSCHREIBEN,
                    uebernahme.uebernehmen(auswahl, v, zeilen, false).status());
            assertEquals(ZeitnahmeErgebnisUebernahme.Status.OK,
                    uebernahme.uebernehmen(auswahl, v, zeilen, true).status());
            assertEquals(List.of(1, 6321, 5000), ziele.stream().map(ZeitnahmeErgebnisUebernahme.Bahnziel::zeit).toList());
            assertEquals(strafen, ziele.get(1).teilnehmer().getStrafen(0));
            assertEquals(1, ereignisse.get());
            sitzung.anlegen(v.laufstand(), 90_000_000);
            assertTrue(sitzung.stoppuhrEmpfangen(mobil));
            assertFalse(sitzung.stoppuhrEmpfangen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), null, 5, 65_000_000)));
        });
    }

    @Test
    void schreibtFreiwasserZeitUnterDisziplinIdUndLaesstBeckenzeitInRuhe() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var beckenprobe = ZeitnahmeLaufquelle.probewettkampf();
            var regelwerk = beckenprobe.getRegelwerk();
            regelwerk.setFormelID(FormelILSOutdoor.ID);
            var probe = new EinzelWettkampf(regelwerk, beckenprobe.getStrafen());
            var teilnehmer = probe.createTeilnehmer("", "Probe", "", 2000, false, "", 0, "");
            probe.addSchwimmer(teilnehmer);
            var disziplin = probe.getLauflisteOW().addDisziplin(0, false, 0, 1);
            var vorlage = beckenprobe.getLaufliste().getLaufliste().getFirst();
            var lauf = new OWLauf<>(probe, disziplin.getId(), vorlage);
            for (int index = 0; index < lauf.getBahnen(); index++) {
                lauf.setSchwimmer(index, index == 4 ? teilnehmer : null);
            }
            disziplin.laeufe.add(lauf);
            teilnehmer.setZeit(0, 900);
            var quelle = new ZeitnahmeLaufquelle(() -> probe);
            var auswahl = quelle.laeufe().getFirst();
            var v = new ZeitnahmeVersuch(new ZeitnahmeVersuch.Laufstand(auswahl.wettkampf(), auswahl.lauf(),
                    auswahl.disziplin(), List.of(new ZeitnahmeVersuch.Bahn(5, "Probe", null, false)), null), 120_000_000);
            assertTrue(v.tischstartReservieren(0));
            assertTrue(v.startErfassen(new ZeitnahmeEreignis.Start(UUID.randomUUID(), v.id(), null, 0, null)));
            assertTrue(v.stoppuhrErfassen(new ZeitnahmeEreignis.Stoppuhr(UUID.randomUUID(), v.id(), null, 5, 63_210_000)));
            var uebernahme = new ZeitnahmeErgebnisUebernahme(new WettkampfZugriff()::versuchen, quelle::lauf, () -> {});
            assertEquals(ZeitnahmeErgebnisUebernahme.Status.OK,
                    uebernahme.uebernehmen(auswahl, v, uebernahme.pruefen(auswahl, v).zeilen(), false).status());
            assertEquals(6321, teilnehmer.getZeit(disziplin.getId()));
            assertEquals(900, teilnehmer.getZeit(0));
        });
    }
}
