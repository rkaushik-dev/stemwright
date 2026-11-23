package io.github.rkaushikdev.stemwright.cli;

import static io.github.rkaushikdev.stemwright.cli.Program.EXIT_FAILED;
import static io.github.rkaushikdev.stemwright.cli.Program.EXIT_MISUSED;
import static io.github.rkaushikdev.stemwright.cli.Program.EXIT_OK;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.stemwright.app.Application;
import io.github.rkaushikdev.stemwright.app.ApplicationOptions;
import io.github.rkaushikdev.stemwright.app.LanguageBundle;
import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.drill.Deck;
import io.github.rkaushikdev.stemwright.domain.drill.DeckInput;
import io.github.rkaushikdev.stemwright.domain.lexicon.ClassRegistry;
import io.github.rkaushikdev.stemwright.domain.lexicon.InflectionClassInput;
import io.github.rkaushikdev.stemwright.domain.lexicon.PartOfSpeech;
import io.github.rkaushikdev.stemwright.domain.ortho.TransliterationTable;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileBook;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileBookInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.RuleSet;
import io.github.rkaushikdev.stemwright.domain.text.Text;
import io.github.rkaushikdev.stemwright.infra.MemoryStores;
import io.github.rkaushikdev.stemwright.support.App;
import io.github.rkaushikdev.stemwright.support.Book;
import io.github.rkaushikdev.stemwright.support.Drill;
import io.github.rkaushikdev.stemwright.support.Ortho;
import io.github.rkaushikdev.stemwright.support.Profiles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DoctorCommandTest {

    private static final CommandContext CONTEXT = new CommandContext(line -> {}, App.NOW);

    private static Program programOver(Stores stores) {
        App.Harness harness = App.testHarness();
        Application app = Application.create(stores, ApplicationOptions.of(harness.bus(), harness.clock()));
        return new Program("stemwright", "1.0.0", List.of(DoctorCommand.of(app)));
    }

    /** The shipped fixture has classes nobody bound, which is the unhappy case. */
    private static Program messy() {
        Stores stores = App.seededStores();
        stores.decks().put(Deck.of(Profiles.LATIN_NOUN, DeckInput.of("all")));
        return programOver(stores);
    }

    /** {@link #tidy(Map)} with no binding beyond the one the class needs. */
    private static Program tidy() {
        return tidy(Map.of());
    }

    /** A language with one class, one binding, one lexeme and one deck, plus any bindings given. */
    private static Program tidy(Map<String, String> bindings) {
        return programOver(tidyStores(bindings));
    }

    /** The stores {@link #tidy(Map)} runs over. */
    private static Stores tidyStores(Map<String, String> bindings) {
        Stores stores = MemoryStores.create();
        stores.languages()
                .put(new LanguageBundle(
                        "latin",
                        Profiles.LATIN_NOUN,
                        ClassRegistry.of(
                                "latin",
                                List.of(InflectionClassInput.builder("first-declension", PartOfSpeech.NOUN)
                                        .label("First declension")
                                        .build())),
                        Ortho.LATIN,
                        RuleSet.NONE,
                        TransliterationTable.NONE));
        Map<String, String> bound = new LinkedHashMap<>();
        bound.put("first-declension", "first-declension");
        bound.putAll(bindings);
        stores.books().put(ProfileBook.of(new ProfileBookInput("latin", List.of(Book.FIRST_HISTORY), bound)));
        stores.lexemes().put(Drill.PUELLA);
        stores.decks().put(Deck.of(Profiles.LATIN_NOUN, DeckInput.of("all")));
        return stores;
    }

    private static RunResult run(Program program, String... argv) {
        return program.run(List.of(argv), CONTEXT);
    }

    @Nested
    class DoctorOnALanguageWithHolesInIt {

        @Test
        void exitsNonZeroSoItCanBeAStepInADeployment() {
            assertThat(run(messy(), "doctor", "--language", "latin").code()).isEqualTo(EXIT_FAILED);
        }

        @Test
        void stillPrintsTheWholeReportOnTheWayOut() {
            RunResult result = run(messy(), "doctor", "--language", "latin");
            assertThat(result.output()).contains("Findings");
            assertThat(result.output()).contains("third-declension has no paradigm bound");
            assertThat(result.output()).contains("errors stop a class drilling");
        }

        @Test
        void countsWhatItLookedAt() {
            RunResult result = run(messy(), "doctor", "--language", "latin");
            assertThat(result.output()).containsPattern("classes {2,}9");
            assertThat(result.output()).containsPattern("profiles {2,}2");
            assertThat(result.output()).containsPattern("lexemes {2,}4");
            assertThat(result.output()).containsPattern("decks {2,}1");
        }

        @Test
        void leadsWithTheCountOfEachLevel() {
            RunResult result = run(messy(), "doctor", "--language", "latin");
            assertThat(Text.split(result.output(), "\n").getFirst())
                    .containsPattern("^latin has \\d+ errors, \\d+ warnings");
        }
    }

    @Nested
    class DoctorOnALanguageWithNothingWrong {

        @Test
        void passes() {
            assertThat(run(tidy(), "doctor", "--language", "latin").code()).isEqualTo(EXIT_OK);
        }

        @Test
        void failsOnceADeckRunsAtALevelTheLanguageDoesNotDeclare() {
            Stores stores = tidyStores(Map.of());
            stores.decks()
                    .put(Deck.of(
                            Profiles.LATIN_NOUN,
                            DeckInput.builder("prose")
                                    .toleranceLevel("pedantic")
                                    .build()));
            RunResult result = run(programOver(stores), "doctor", "--language", "latin");
            assertThat(result.code()).isEqualTo(EXIT_FAILED);
            assertThat(result.output()).contains("prose runs at the level pedantic, which latin does not declare");
        }

        @Test
        void stillSaysWhatItNoted() {
            RunResult result = run(tidy(), "doctor", "--language", "latin");
            assertThat(result.output()).contains("all holds only 1 lexemes");
        }

        @Test
        void passesAWarningByDefaultAndRefusesItUnderStrict() {
            Map<String, String> stray = Map.of("fourth-declension", "first-declension");

            RunResult relaxed = run(tidy(stray), "doctor", "--language", "latin");
            assertThat(relaxed.code()).isEqualTo(EXIT_OK);
            assertThat(relaxed.output()).contains("the binding on fourth-declension is shadowed");

            RunResult strict = run(tidy(stray), "doctor", "--language", "latin", "--strict");
            assertThat(strict.code()).isEqualTo(EXIT_FAILED);
            assertThat(strict.output()).contains("1 warnings, and strict was asked for");
        }

        @Test
        void reportsADayOnWhichNothingHadBeenPublishedYet() {
            RunResult result = run(tidy(), "doctor", "--language", "latin", "--on", "2024-01-01");
            assertThat(result.code()).isEqualTo(EXIT_FAILED);
            assertThat(result.output()).contains("nothing in force on 2024-01-01");
            assertThat(result.output()).contains("all selects nothing on 2024-01-01");
        }

        @Test
        void doesNotFailUnderStrictWhenOnlyNotesWereFound() {
            RunResult result = run(tidy(), "doctor", "--language", "latin", "--strict");
            assertThat(result.code()).isEqualTo(EXIT_OK);
        }
    }

    @Nested
    class DoctorAsACommand {

        @Test
        void refusesACallWithNoLanguage() {
            assertThat(run(tidy(), "doctor").code()).isEqualTo(EXIT_MISUSED);
        }

        @Test
        void refusesAFlagItDoesNotDeclare() {
            assertThat(run(tidy(), "doctor", "--language", "latin", "--deep").code())
                    .isEqualTo(EXIT_MISUSED);
        }

        @Test
        void failsForALanguageNobodyConfigured() {
            RunResult result = run(tidy(), "doctor", "--language", "greek");
            assertThat(result.code()).isEqualTo(EXIT_FAILED);
            assertThat(result.output()).contains("was not found");
        }

        @Test
        void documentsItself() {
            RunResult result = run(tidy(), "help", "doctor");
            assertThat(result.output()).contains("stemwright doctor --language latin --strict");
            assertThat(result.output()).contains("--strict");
        }
    }
}
