package io.github.rkaushikdev.stemwright.support;

import io.github.rkaushikdev.stemwright.app.Clock;
import io.github.rkaushikdev.stemwright.app.LanguageBundle;
import io.github.rkaushikdev.stemwright.app.RecordingBus;
import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.ortho.TransliterationTable;
import io.github.rkaushikdev.stemwright.domain.paradigm.RuleSet;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.infra.MemoryStores;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The application fixture the service, route and cli tests build on: Latin configured as one
 * language bundle, stores seeded with it, its profile book and the four drill nouns, and a bus and
 * clock to hand the services.
 */
public final class App {

    private App() {}

    /** The study day every service test runs on, a Monday in the Michaelmas term. */
    public static final LocalDate TODAY = CivilDates.parseCivilDate("2024-10-21");

    /** Nine in the morning UTC on {@link #TODAY}, the instant the harness clock answers. */
    public static final Instant NOW = Instant.parse("2024-10-21T09:00:00Z");

    /**
     * {@link Profiles#LATIN_NOUN}, {@link Classes#LATIN}, {@link Ortho#LATIN}, no join rules and no
     * transliteration.
     */
    public static final LanguageBundle LATIN_BUNDLE = new LanguageBundle(
            "latin", Profiles.LATIN_NOUN, Classes.LATIN, Ortho.LATIN, RuleSet.NONE, TransliterationTable.NONE);

    /**
     * Fresh memory stores holding {@link #LATIN_BUNDLE}, {@link Book#LATIN_BOOK} and the four nouns
     * of {@link Drill}, and nothing else: no decks, ledgers, attempts, sessions or courses.
     */
    public static Stores seededStores() {
        Stores stores = MemoryStores.create();
        stores.languages().put(LATIN_BUNDLE);
        stores.books().put(Book.LATIN_BOOK);
        for (Lexeme entry : List.of(Drill.PUELLA, Drill.BELLUM, Drill.AGER, Drill.REX)) {
            stores.lexemes().put(entry);
        }
        return stores;
    }

    /** A recording bus that has seen nothing, and a clock fixed at {@link #NOW} on {@link #TODAY}. */
    public static Harness testHarness() {
        return new Harness(RecordingBus.create(), Clock.fixed(NOW, TODAY));
    }

    /**
     * What a service is built with besides its stores.
     *
     * @param bus the bus the service publishes to, which keeps what it saw
     * @param clock the clock the service reads the day from
     */
    public record Harness(RecordingBus bus, Clock clock) {

        public Harness {
            Objects.requireNonNull(bus, "bus");
            Objects.requireNonNull(clock, "clock");
        }
    }
}
