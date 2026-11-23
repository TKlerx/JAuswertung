package de.df.jauswertung.gui.plugins.zeitnahme;

import java.util.Objects;
import java.util.UUID;

sealed interface ZeitnahmeEreignis {
    UUID id();

    UUID versuch();

    record Start(UUID id, UUID versuch, UUID geraet, long zeit, Uhrabgleich.Schaetzung schaetzung)
            implements ZeitnahmeEreignis {
        public Start {
            Objects.requireNonNull(id);
            Objects.requireNonNull(versuch);
            if ((geraet == null) != (schaetzung == null)) {
                throw new IllegalArgumentException();
            }
        }
    }

    record Ziel(UUID id, UUID versuch, UUID geraet, int bahn, long zeit, Uhrabgleich.Schaetzung schaetzung)
            implements ZeitnahmeEreignis {
        public Ziel {
            Objects.requireNonNull(id);
            Objects.requireNonNull(versuch);
            Objects.requireNonNull(geraet);
            Objects.requireNonNull(schaetzung);
            if (bahn < 1) {
                throw new IllegalArgumentException();
            }
        }
    }

    record Stoppuhr(UUID id, UUID versuch, UUID geraet, int bahn, long dauer) implements ZeitnahmeEreignis {
        public Stoppuhr {
            Objects.requireNonNull(id);
            Objects.requireNonNull(versuch);
            if (bahn < 1 || dauer <= 0) {
                throw new IllegalArgumentException();
            }
        }
    }
}
