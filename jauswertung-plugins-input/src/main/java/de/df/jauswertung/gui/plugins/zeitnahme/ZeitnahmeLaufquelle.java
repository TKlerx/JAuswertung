package de.df.jauswertung.gui.plugins.zeitnahme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import de.df.jauswertung.daten.AWettkampf;
import de.df.jauswertung.daten.EinzelWettkampf;
import de.df.jauswertung.daten.laufliste.Lauf;
import de.df.jauswertung.daten.regelwerk.Altersklasse;
import de.df.jauswertung.daten.regelwerk.Disziplin;
import de.df.jauswertung.daten.regelwerk.Regelwerk;
import de.df.jauswertung.daten.regelwerk.Strafen;
import de.df.jauswertung.gui.plugins.zeitnahme.VirtuellerZeitnahmelauf.Auswahl;
import de.df.jauswertung.gui.plugins.zeitnahme.VirtuellerZeitnahmelauf.Teilnehmer;
import de.df.jauswertung.gui.plugins.zeitnahme.ZeitnahmeErgebnisUebernahme.Bahnziel;
import de.df.jauswertung.gui.plugins.zeitnahme.ZeitnahmeErgebnisUebernahme.Laufdaten;
import de.df.jauswertung.gui.util.I18n;
import de.df.jauswertung.util.ergebnis.FormelDLRG2007;

final class ZeitnahmeLaufquelle {
    private final Supplier<AWettkampf<?>> quelle;
    private final Map<Object, UUID> ids = new IdentityHashMap<>();
    private AWettkampf<?> wettkampf;

    ZeitnahmeLaufquelle(Supplier<AWettkampf<?>> quelle) {
        this.quelle = quelle;
    }

    List<Auswahl> laeufe() {
        return laufdaten().stream().map(Laufdaten::auswahl).toList();
    }

    Optional<Laufdaten> lauf(Auswahl auswahl) {
        return laufdaten().stream().filter(daten -> daten.auswahl().equals(auswahl)).findFirst();
    }

    private List<Laufdaten> laufdaten() {
        AWettkampf<?> aktuell = quelle.get();
        if (wettkampf != aktuell) {
            wettkampf = aktuell;
            ids.clear();
        }
        List<Laufdaten> laeufe = new ArrayList<>();
        if (aktuell.isOpenWater()) {
            var laufliste = aktuell.getLauflisteOW();
            if (laufliste == null) {
                return List.of();
            }
            for (var disziplin : laufliste.getDisziplinen()) {
                String name = aktuell.getRegelwerk().getAk(disziplin.akNummer)
                        .getDisziplin(disziplin.disziplin, disziplin.maennlich).getName();
                for (var lauf : disziplin.getLaeufe()) {
                    List<Bahnziel> bahnen = new ArrayList<>();
                    for (int index = 0; index < lauf.getBahnen(); index++) {
                        var schwimmer = lauf.getSchwimmer(index);
                        if (schwimmer != null) {
                            bahnen.add(new Bahnziel(index + 1, schwimmer, disziplin.disziplin, lauf.getDisciplineId()));
                        }
                    }
                    laufEintragen(laeufe, lauf, lauf.getName(), name, lauf.getDisciplineId(), bahnen);
                }
            }
        } else {
            var laufliste = aktuell.getLaufliste().getLaufliste();
            if (laufliste == null) {
                return List.of();
            }
            for (var lauf : laufliste) {
                List<Bahnziel> bahnen = new ArrayList<>();
                for (int index = 0; index < lauf.getBahnen(); index++) {
                    var schwimmer = lauf.getSchwimmer(index);
                    if (schwimmer != null) {
                        bahnen.add(new Bahnziel(index + 1, schwimmer, lauf.getDisznummer(index), null));
                    }
                }
                laufEintragen(laeufe, lauf, lauf.getName(aktuell.getHeatsNumberingScheme()), lauf.getDisziplin(), null, bahnen);
            }
        }
        return laeufe;
    }

    private void laufEintragen(List<Laufdaten> laeufe, Object lauf, String laufname, String disziplin, String freiwasser,
            List<Bahnziel> bahnen) {
        if (!bahnen.isEmpty()) {
            List<Teilnehmer> zuordnung = bahnen.stream().map(ziel -> new Teilnehmer(ziel.bahn(),
                    ziel.teilnehmer().getName(), id(ziel.teilnehmer()), ziel.disziplin())).toList();
            var auswahl = new Auswahl(id(wettkampf), id(lauf), I18n.get("ZeitnahmeLauf.Laufname", laufname, disziplin),
                    disziplin, zuordnung, freiwasser);
            laeufe.add(new Laufdaten(auswahl, bahnen));
        }
    }

    private UUID id(Object objekt) {
        return ids.computeIfAbsent(objekt, ignoriert -> UUID.randomUUID());
    }

    static EinzelWettkampf probewettkampf() {
        Regelwerk regelwerk = new Regelwerk(1, true, FormelDLRG2007.ID);
        Disziplin[] disziplinen = { new Disziplin(I18n.get("ZeitnahmeLauf.Probedisziplin"), 6_000, 50, 1) };
        regelwerk.setAk(0, new Altersklasse(I18n.get("ZeitnahmeLauf.Probelauf"),
                new Disziplin[][] { disziplinen, disziplinen }, false));
        EinzelWettkampf probe = new EinzelWettkampf(regelwerk, new Strafen());
        boolean[] benutzbar = new boolean[6];
        Arrays.fill(benutzbar, true);
        Lauf<de.df.jauswertung.daten.Teilnehmer> lauf = new Lauf<>(6, 1, 0, benutzbar);
        List<String> namen = List.of("TeamA", "TeamB", "TeamC");
        for (int index = 0; index < namen.size(); index++) {
            var schwimmer = probe.createTeilnehmer("", I18n.get("ZeitnahmeLauf." + namen.get(index)), "", 2000, false,
                    I18n.get("ZeitnahmeLauf.Probelauf"), 0, "");
            probe.addSchwimmer(schwimmer);
            lauf.setSchwimmer(schwimmer, 0, index + 2);
        }
        probe.getLaufliste().erzeugen();
        var liste = probe.getLaufliste().getLaufliste();
        liste.clear();
        liste.add(lauf);
        return probe;
    }
}
