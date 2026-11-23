package io.github.rkaushikdev.stemwright.cli;

import static io.github.rkaushikdev.stemwright.cli.Program.EXIT_FAILED;
import static io.github.rkaushikdev.stemwright.cli.Program.EXIT_MISUSED;
import static io.github.rkaushikdev.stemwright.cli.Program.EXIT_OK;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.stemwright.app.Application;
import io.github.rkaushikdev.stemwright.app.ApplicationOptions;
import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.drill.Deck;
import io.github.rkaushikdev.stemwright.domain.drill.DeckInput;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.text.Text;
import io.github.rkaushikdev.stemwright.support.App;
import io.github.rkaushikdev.stemwright.support.Drill;
import io.github.rkaushikdev.stemwright.support.Profiles;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CommandsTest {

    private static final CommandContext CONTEXT = new CommandContext(line -> {}, App.NOW);

    private static Program build() {
        return over(seeded());
    }

    /** The seeded stores with the deck {@code all} over the whole language. */
    private static Stores seeded() {
        Stores stores = App.seededStores();
        stores.decks().put(Deck.of(Profiles.LATIN_NOUN, DeckInput.of("all")));
        return stores;
    }

    private static Program over(Stores stores) {
        App.Harness harness = App.testHarness();
        Application app = Application.create(stores, ApplicationOptions.of(harness.bus(), harness.clock()));
        return new Program("stemwright", "1.0.0", Commands.all(app));
    }

    private static RunResult run(Program program, String... argv) {
        return program.run(List.of(argv), CONTEXT);
    }

    private static List<String> names(Program program) {
        return program.commands().stream().map(Command::name).toList();
    }

    @Nested
    class TheCommandTable {

        @Test
        void declaresEveryCommandTheCliDocuments() {
            assertThat(names(build())).contains("coverage", "import", "lexemes", "paradigm", "queue", "routes");
        }

        @Test
        void namesEachCommandExactlyOnce() {
            assertThat(names(build())).doesNotHaveDuplicates();
        }

        @Test
        void givesEveryCommandAUsageLine() {
            assertThat(build().commands()).allMatch(command -> command.usage().startsWith("stemwright "));
        }
    }

    @Nested
    class Queue {

        @Test
        void printsTheCellsALearnerWouldBeAsked() {
            RunResult result = run(build(), "queue", "--language", "latin", "--deck", "all", "--learner", "lrn_one");
            assertThat(result.code()).isEqualTo(EXIT_OK);
            assertThat(result.output()).contains("bundle");
            assertThat(result.output()).contains("new");
        }

        @Test
        void namesTheLexemeOfEachCellAsked() {
            RunResult result = run(build(), "queue", "--language", "latin", "--deck", "all", "--learner", "lrn_one");
            assertThat(Text.split(result.output(), "\n").getFirst()).startsWith("lemma");
            assertThat(result.output()).contains("puella").contains("bellum");
            assertThat(result.output()).doesNotContain("Left out");
        }

        @Test
        void explainsWhyTheRestOfTheDeckWasLeftOut() {
            RunResult result =
                    run(build(), "queue", "--language", "latin", "--deck", "all", "--learner", "lrn_one", "--explain");
            assertThat(result.code()).isEqualTo(EXIT_OK);
            List<String> lines = Text.split(result.output(), "\n");
            int asked = lines.indexOf("") - 2;
            assertThat(asked).isEqualTo(9);
            assertThat(lines.subList(lines.indexOf("") + 1, lines.size()))
                    .containsExactly(
                            "Left out: 21 of 30 cells on 2024-10-21",
                            "  reason       cells",
                            "  -----------  -----",
                            "  lexeme-cap      20",
                            "  row-spacing      1");
        }

        @Test
        void countsASuspendedCellUnderItsOwnReason() {
            Stores stores = seeded();
            String genitive = Cell.idFor(Drill.PUELLA.id(), "case=gen;number=sg");
            stores.ledgers()
                    .put(MasteryLedger.of(
                            "lrn_one",
                            List.of(CellStrength.fresh(genitive)
                                    .recordLapse(Ladder.STANDARD, App.TODAY)
                                    .suspend())));
            RunResult result = run(
                    over(stores), "queue", "--language", "latin", "--deck", "all", "--learner", "lrn_one", "--explain");
            assertThat(result.output()).containsPattern("suspended +1");
        }

        @Test
        void refusesACallWithNoLearner() {
            RunResult result = run(build(), "queue", "--language", "latin", "--deck", "all");
            assertThat(result.code()).isEqualTo(EXIT_MISUSED);
            assertThat(result.output()).contains("learner is required");
        }

        @Test
        void failsWhenTheDeckIsNotThere() {
            RunResult result =
                    run(build(), "queue", "--language", "latin", "--deck", "nowhere", "--learner", "lrn_one");
            assertThat(result.code()).isEqualTo(EXIT_FAILED);
            assertThat(result.output()).contains("was not found");
        }
    }

    @Nested
    class Paradigm {

        @Test
        void printsTheTableALexemeIsGradedAgainst() {
            RunResult result = run(
                    build(),
                    "paradigm",
                    "--language",
                    "latin",
                    "--lexeme",
                    Drill.PUELLA.id(),
                    "--rows",
                    "case",
                    "--columns",
                    "number");
            assertThat(result.code()).isEqualTo(EXIT_OK);
            assertThat(result.output()).contains("puella under first-declension revision 1");
            assertThat(result.output()).contains("puellae");
        }

        @Test
        void tracesHowEveryFormWasBuiltWhenAsked() {
            RunResult result = run(
                    build(),
                    "paradigm",
                    "--language",
                    "latin",
                    "--lexeme",
                    Drill.PUELLA.id(),
                    "--rows",
                    "case",
                    "--columns",
                    "number",
                    "--trace");
            List<String> lines = Text.split(result.output(), "\n");
            assertThat(result.code()).isEqualTo(EXIT_OK);
            assertThat(lines).contains("How each form is built");
            assertThat(lines).contains("  case=gen;number=sg  puell plus ae gives puellae in latin");
            assertThat(lines.stream().filter(line -> line.contains(" gives ")).count())
                    .isEqualTo(10);
        }

        @Test
        void leavesTheTraceOutUnlessItIsAskedFor() {
            RunResult result = run(
                    build(),
                    "paradigm",
                    "--language",
                    "latin",
                    "--lexeme",
                    Drill.PUELLA.id(),
                    "--rows",
                    "case",
                    "--columns",
                    "number");
            assertThat(result.output()).doesNotContain("How each form is built");
        }

        @Test
        void failsForALexemeNobodyStored() {
            RunResult result = run(
                    build(),
                    "paradigm",
                    "--language",
                    "latin",
                    "--lexeme",
                    "lex_zzzzzz",
                    "--rows",
                    "case",
                    "--columns",
                    "number");
            assertThat(result.code()).isEqualTo(EXIT_FAILED);
        }

        @Test
        void failsForADayBeforeTheProfileWasPublished() {
            RunResult result = run(
                    build(),
                    "paradigm",
                    "--language",
                    "latin",
                    "--lexeme",
                    Drill.PUELLA.id(),
                    "--rows",
                    "case",
                    "--columns",
                    "number",
                    "--on",
                    "2024-01-01");
            assertThat(result.code()).isEqualTo(EXIT_FAILED);
            assertThat(result.output()).contains("profile");
        }
    }

    @Nested
    class Coverage {

        @Test
        void printsTheCountsAndTheWeakestBundles() {
            RunResult result = run(build(), "coverage", "--language", "latin", "--deck", "all", "--learner", "lrn_one");
            assertThat(result.code()).isEqualTo(EXIT_OK);
            assertThat(result.output()).contains("cells");
            assertThat(result.output()).contains("maturity");
        }
    }

    @Nested
    class Lexemes {

        @Test
        void listsTheLexicon() {
            RunResult result = run(build(), "lexemes", "--language", "latin");
            assertThat(result.code()).isEqualTo(EXIT_OK);
            assertThat(result.output()).contains("puella");
            assertThat(result.output()).contains("bellum");
        }

        @Test
        void narrowsToASearchTerm() {
            RunResult result = run(build(), "lexemes", "--language", "latin", "--search", "field");
            assertThat(result.output()).contains("ager");
            assertThat(result.output()).doesNotContain("puella");
        }

        @Test
        void failsForALanguageNobodyConfigured() {
            RunResult result = run(build(), "lexemes", "--language", "greek");
            assertThat(result.code()).isEqualTo(EXIT_FAILED);
        }
    }

    @Nested
    class Routes {

        @Test
        void listsTheHttpRoutesTheBuildServes() {
            RunResult result = run(build(), "routes");
            assertThat(result.code()).isEqualTo(EXIT_OK);
            assertThat(result.output()).contains("method");
            assertThat(result.output()).contains("/health");
            assertThat(result.output()).contains("/queue");
        }

        @Test
        void needsNoArguments() {
            assertThat(run(build(), "routes", "--nonsense").code()).isEqualTo(EXIT_MISUSED);
        }

        @Test
        void isOneOfTheDeclaredCommands() {
            assertThat(names(build())).contains("routes");
        }
    }
}
