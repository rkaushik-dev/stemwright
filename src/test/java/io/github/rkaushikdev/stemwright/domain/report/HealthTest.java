package io.github.rkaushikdev.stemwright.domain.report;

import static io.github.rkaushikdev.stemwright.support.Book.FIRST_HISTORY;
import static io.github.rkaushikdev.stemwright.support.Book.LATIN_BOOK;
import static io.github.rkaushikdev.stemwright.support.Drill.AGER;
import static io.github.rkaushikdev.stemwright.support.Drill.BELLUM;
import static io.github.rkaushikdev.stemwright.support.Drill.PUELLA;
import static io.github.rkaushikdev.stemwright.support.Drill.REX;
import static io.github.rkaushikdev.stemwright.support.Profiles.FIRST_DECLENSION;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.stemwright.domain.drill.Deck;
import io.github.rkaushikdev.stemwright.domain.drill.DeckInput;
import io.github.rkaushikdev.stemwright.domain.drill.DeckScopeInput;
import io.github.rkaushikdev.stemwright.domain.drill.DrillContext;
import io.github.rkaushikdev.stemwright.domain.drill.Membership;
import io.github.rkaushikdev.stemwright.domain.lexicon.ClassRegistry;
import io.github.rkaushikdev.stemwright.domain.lexicon.InflectionClassInput;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.lexicon.LexemeContext;
import io.github.rkaushikdev.stemwright.domain.lexicon.LexemeInput;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexicon;
import io.github.rkaushikdev.stemwright.domain.lexicon.PartOfSpeech;
import io.github.rkaushikdev.stemwright.domain.lexicon.StemInput;
import io.github.rkaushikdev.stemwright.domain.lexicon.StemSet;
import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmSlotInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileBook;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileBookInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileRevisionInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.RevisionHistory;
import io.github.rkaushikdev.stemwright.domain.paradigm.SyncretismGroupInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.SyncretismMap;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.support.Classes;
import io.github.rkaushikdev.stemwright.support.Ortho;
import io.github.rkaushikdev.stemwright.support.TestMaps;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HealthTest {

    private static final LocalDate DAY = CivilDates.parseCivilDate("2024-10-21");

    private static DrillContext contextOf(ClassRegistry classes, ProfileBook book, Lexicon lexicon) {
        return new DrillContext(LATIN_NOUN, classes, book, lexicon);
    }

    private static DrillContext contextOf(Lexicon lexicon) {
        return contextOf(Classes.LATIN, LATIN_BOOK, lexicon);
    }

    private static DrillContext contextOf() {
        return contextOf(Lexicon.build("latin", List.of(PUELLA, BELLUM, AGER, REX)));
    }

    private static List<HealthInput.ResolvedDeck> withDeck(DrillContext context, DeckScopeInput scope) {
        Deck entry = Deck.of(LATIN_NOUN, DeckInput.builder("all").scope(scope).build());
        return List.of(new HealthInput.ResolvedDeck(entry, Membership.resolve(context, entry, DAY)));
    }

    private static List<HealthInput.ResolvedDeck> withDeck(DrillContext context) {
        return withDeck(context, DeckScopeInput.EMPTY);
    }

    private static List<Finding> check(DrillContext context, List<HealthInput.ResolvedDeck> decks, LocalDate on) {
        return Health.check(new HealthInput(context, Ortho.LATIN, decks, on));
    }

    private static List<Finding> check(DrillContext context) {
        return check(context, List.of(), DAY);
    }

    private static List<String> messagesFor(List<Finding> findings, String area) {
        return findings.stream()
                .filter(finding -> finding.area().equals(area))
                .map(Finding::message)
                .toList();
    }

    @Nested
    class CheckOverClasses {

        @Test
        void reportsEveryClassWithNoParadigmAnywhereAboveIt() {
            List<String> classes = messagesFor(check(contextOf()), "classes");
            assertThat(classes.stream().anyMatch(message -> message.startsWith("third-declension has no paradigm")))
                    .isTrue();
            assertThat(classes.stream().anyMatch(message -> message.startsWith("first-conjugation has no paradigm")))
                    .isTrue();
        }

        @Test
        void saysNothingAboutAClassThatInheritsABindingFromItsParent() {
            assertThat(String.join("\n", messagesFor(check(contextOf()), "classes")))
                    .doesNotContain("second-declension.neuter");
        }

        @Test
        void staysQuietWhenEveryClassIsBound() {
            ClassRegistry classes = ClassRegistry.of(
                    "latin",
                    List.of(InflectionClassInput.builder("first-declension", PartOfSpeech.NOUN)
                            .label("First")
                            .build()));
            ProfileBook book = ProfileBook.of(new ProfileBookInput(
                    "latin", List.of(FIRST_HISTORY), TestMaps.ordered("first-declension", "first-declension")));
            DrillContext context = contextOf(classes, book, Lexicon.build("latin", List.of(PUELLA)));
            assertThat(messagesFor(check(context), "classes")).isEmpty();
        }
    }

    @Nested
    class CheckOverProfiles {

        @Test
        void warnsAboutAProfileWithNothingInForceYetAndNamesTheStart() {
            List<Finding> findings = check(contextOf(), List.of(), CivilDates.parseCivilDate("2024-01-01"));
            assertThat(messagesFor(findings, "profiles"))
                    .contains("first-declension has nothing in force on 2024-01-01, the first starts 2024-09-01");
        }

        @Test
        void staysQuietOnceEveryProfileHasBeenPublished() {
            assertThat(messagesFor(check(contextOf()), "profiles")).isEmpty();
        }

        @Test
        void warnsAboutTwinsDeclaredOneFormThatTheEndingsBuildApart() {
            RevisionHistory overclaimed = RevisionHistory.of(
                    "first-declension",
                    List.of(ProfileRevisionInput.of(CivilDates.parseCivilDate("2024-09-01"), FIRST_DECLENSION)
                            .withSyncretism(SyncretismMap.of(
                                    LATIN_NOUN,
                                    List.of(
                                            SyncretismGroupInput.of("ae", "gen.sg", "acc.sg", "nom.pl"),
                                            SyncretismGroupInput.of("is", "dat.pl", "abl.pl"))))));
            ProfileBook book = ProfileBook.of(new ProfileBookInput(
                    "latin", List.of(overclaimed), TestMaps.ordered("first-declension", "first-declension")));
            List<Finding> findings = check(contextOf(Classes.LATIN, book, Lexicon.build("latin", List.of(PUELLA))));

            assertThat(messagesFor(findings, "profiles"))
                    .containsExactly(
                            "first-declension revision 1 declares case=gen;number=sg and case=acc;number=sg, "
                                    + "case=acc;number=sg and case=nom;number=pl one form, but its endings build them differently");
            assertThat(findings)
                    .filteredOn(finding -> finding.area().equals("profiles"))
                    .extracting(Finding::level)
                    .containsExactly(FindingLevel.WARNING);
        }

        @Test
        void saysNothingAboutFormsTheEndingsBuildAlikeWithoutADeclaration() {
            ProfileBook bare = ProfileBook.of(new ProfileBookInput(
                    "latin",
                    List.of(RevisionHistory.of(
                            "first-declension",
                            List.of(ProfileRevisionInput.of(
                                    CivilDates.parseCivilDate("2024-09-01"), FIRST_DECLENSION)))),
                    TestMaps.ordered("first-declension", "first-declension")));
            assertThat(messagesFor(
                            check(contextOf(Classes.LATIN, bare, Lexicon.build("latin", List.of(PUELLA)))), "profiles"))
                    .isEmpty();
        }
    }

    @Nested
    class CheckOverTheLexicon {

        @Test
        void warnsThatALexemeWhoseClassIsUnboundCannotBeDrilled() {
            assertThat(messagesFor(check(contextOf()), "lexicon"))
                    .contains("1 lexemes cannot be drilled because their class has no paradigm");
        }

        @Test
        void callsAClassTheRegistryDoesNotHoldAnError() {
            Lexeme stray = new Lexeme(
                    PUELLA.id(),
                    PUELLA.language(),
                    PUELLA.lemma(),
                    "fourth-declension",
                    PUELLA.stems(),
                    PUELLA.inherent(),
                    PUELLA.glosses(),
                    PUELLA.homograph(),
                    PUELLA.tags(),
                    PUELLA.note());
            List<Finding> lexicon = check(contextOf(Lexicon.build("latin", List.of(stray)))).stream()
                    .filter(finding -> finding.area().equals("lexicon"))
                    .toList();
            assertThat(lexicon.getFirst().level()).isEqualTo(FindingLevel.ERROR);
            assertThat(lexicon.getFirst().message()).contains("fourth-declension");
        }

        @Test
        void notesAnEmptyLexiconRatherThanPassingItInSilence() {
            List<Finding> findings = check(contextOf(Lexicon.build("latin", List.of())));
            assertThat(messagesFor(findings, "lexicon")).contains("the lexicon is empty");
        }
    }

    @Nested
    class CheckOverStemRoles {

        private static final LexemeContext LEXEME_CONTEXT = new LexemeContext(LATIN_NOUN, Classes.LATIN);

        /** A first declension whose genitive singular is built on an oblique stem. */
        private static final ProfileBook OBLIQUE_BOOK = ProfileBook.of(new ProfileBookInput(
                "latin",
                List.of(RevisionHistory.of(
                        "first-declension",
                        List.of(ProfileRevisionInput.of(
                                CivilDates.parseCivilDate("2024-09-01"),
                                FIRST_DECLENSION.withSlot(
                                        LATIN_NOUN,
                                        ParadigmSlotInput.builder("gen.sg")
                                                .stemRole("oblique")
                                                .affix("ae")
                                                .build()))))),
                TestMaps.ordered("first-declension", "first-declension")));

        private static Lexeme noun(String lemma, StemInput... stems) {
            return Lexeme.of(LEXEME_CONTEXT, LexemeInput.of(lemma, "first-declension", StemSet.of(stems)));
        }

        private static List<Finding> checkNouns(Lexeme... nouns) {
            return check(contextOf(Classes.LATIN, OBLIQUE_BOOK, Lexicon.build("latin", List.of(nouns))));
        }

        @Test
        void warnsAboutALexemeMarkedAgainstAFormBuiltOnTheWrongStem() {
            List<Finding> findings = checkNouns(
                    noun("rosa", StemInput.of("base", "ros")),
                    noun("via", StemInput.of("base", "vi"), StemInput.of("oblique", "vi")));
            assertThat(messagesFor(findings, "lexicon"))
                    .containsExactly("1 lexemes lack a stem their profile builds on and are marked against forms"
                            + " built on the base stem: rosa (oblique)");
            assertThat(findings)
                    .filteredOn(finding -> finding.area().equals("lexicon"))
                    .extracting(Finding::level)
                    .containsExactly(FindingLevel.WARNING);
        }

        @Test
        void namesThreeLexemesInReadingOrderAndCountsTheRest() {
            List<Finding> findings = checkNouns(
                    noun("via", StemInput.of("base", "vi")),
                    noun("aqua", StemInput.of("base", "aqu")),
                    noun("rosa", StemInput.of("base", "ros")),
                    noun("mensa", StemInput.of("base", "mens")),
                    noun("terra", StemInput.of("base", "terr")));
            assertThat(messagesFor(findings, "lexicon"))
                    .containsExactly("5 lexemes lack a stem their profile builds on and are marked against forms"
                            + " built on the base stem: aqua (oblique), mensa (oblique), rosa (oblique) and 2 more");
        }

        @Test
        void saysNothingWhenTheProfileBuildsOnlyOnTheBaseStem() {
            assertThat(messagesFor(check(contextOf()), "lexicon"))
                    .noneMatch(message -> message.contains("lack a stem"));
        }

        @Test
        void skipsALexemeWithNothingInForceForItsClass() {
            List<Finding> findings = check(
                    contextOf(
                            Classes.LATIN,
                            OBLIQUE_BOOK,
                            Lexicon.build("latin", List.of(noun("rosa", StemInput.of("base", "ros"))))),
                    List.of(),
                    CivilDates.parseCivilDate("2024-01-01"));
            assertThat(messagesFor(findings, "lexicon")).isEmpty();
        }
    }

    @Nested
    class CheckOverDecks {

        @Test
        void callsADeckThatSelectsNothingAnError() {
            DrillContext context = contextOf();
            List<HealthInput.ResolvedDeck> decks = withDeck(
                    context,
                    DeckScopeInput.builder().classKeys("third-declension").build());
            assertThat(check(context, decks, DAY))
                    .anyMatch(finding -> finding.message().equals("all selects nothing on 2024-10-21"));
        }

        @Test
        void notesADeckHoldingOnlyAHandfulOfLexemes() {
            DrillContext context = contextOf();
            assertThat(messagesFor(check(context, withDeck(context), DAY), "decks"))
                    .contains("all holds only 3 lexemes");
        }

        @Test
        void callsADeckAtALevelTheLanguageNeverDeclaredAnError() {
            DrillContext context = contextOf();
            Deck lenient = Deck.of(
                    LATIN_NOUN,
                    DeckInput.builder("lenient").toleranceLevel("lenient").build());
            Deck exact = Deck.of(
                    LATIN_NOUN,
                    DeckInput.builder("exact").toleranceLevel("exact").build());
            List<HealthInput.ResolvedDeck> decks = List.of(
                    new HealthInput.ResolvedDeck(lenient, Membership.resolve(context, lenient, DAY)),
                    new HealthInput.ResolvedDeck(exact, Membership.resolve(context, exact, DAY)));

            List<Finding> errors = check(context, decks, DAY).stream()
                    .filter(finding -> finding.level() == FindingLevel.ERROR
                            && finding.area().equals("decks"))
                    .toList();

            assertThat(errors)
                    .extracting(Finding::message)
                    .containsExactly(
                            "lenient runs at the level lenient, which latin does not declare, so no answer to it can be marked");
        }

        @Test
        void notesADeckThatBurialLeavesWithFarFewerQuestionsThanCells() {
            DrillContext context = contextOf();
            List<HealthInput.ResolvedDeck> decks = withDeck(
                    context,
                    DeckScopeInput.builder().classKeys("first-declension").build());
            assertThat(messagesFor(check(context, decks, DAY), "decks"))
                    .contains("all takes 10 cells, but burial leaves a sitting 7 of them to ask, 70 percent,"
                            + " because the rest are twins");
        }

        @Test
        void saysNothingAboutTwinsWhenMostOfTheDeckCanStillBeAsked() {
            DrillContext context = contextOf();
            assertThat(messagesFor(check(context, withDeck(context), DAY), "decks"))
                    .noneMatch(message -> message.contains("twins"));
        }

        @Test
        void leavesADeckWhereOnlyAFifthOfTheCellsAreTwinsAlone() {
            DrillContext context = contextOf(Lexicon.build("latin", List.of(BELLUM)));
            List<HealthInput.ResolvedDeck> autumn = withDeck(
                    context,
                    DeckScopeInput.builder().classKeys("second-declension").build());
            LocalDate spring = CivilDates.parseCivilDate("2025-03-01");
            Deck entry = autumn.getFirst().deck();
            List<HealthInput.ResolvedDeck> neuterRule =
                    List.of(new HealthInput.ResolvedDeck(entry, Membership.resolve(context, entry, spring)));
            assertThat(messagesFor(check(context, autumn, DAY), "decks"))
                    .noneMatch(message -> message.contains("twins"));
            assertThat(messagesFor(check(context, neuterRule, spring), "decks"))
                    .noneMatch(message -> message.contains("twins"));
            assertThat(messagesFor(check(context, neuterRule, spring), "decks"))
                    .containsExactly("all holds only 1 lexemes");
        }

        @Test
        void notesWhenNothingHasBeenDefinedAtAll() {
            assertThat(messagesFor(check(contextOf()), "decks")).containsExactly("no decks are defined");
        }
    }

    @Nested
    class TheOrderAndTheSummary {

        @Test
        void putsErrorsFirstAndNotesLast() {
            DrillContext context = contextOf();
            List<FindingLevel> levels = check(context, withDeck(context), DAY).stream()
                    .map(Finding::level)
                    .toList();
            assertThat(levels)
                    .isEqualTo(levels.stream()
                            .sorted(Comparator.comparingInt(levels::indexOf))
                            .toList());
            assertThat(levels.getFirst()).isEqualTo(FindingLevel.ERROR);
            assertThat(levels.getLast()).isEqualTo(FindingLevel.NOTE);
        }

        @Test
        void keepsTheOrderTheChecksRanInWithinOneLevel() {
            DrillContext context = contextOf();
            List<Finding> errors = check(context, withDeck(context), DAY).stream()
                    .filter(finding -> finding.level() == FindingLevel.ERROR)
                    .toList();
            assertThat(errors).allMatch(finding -> finding.area().equals("classes"));
        }

        @Test
        void countsEachLevelIncludingTheOnesNothingReached() {
            assertThat(Health.countLevels(List.of())).isEqualTo(new FindingCounts(0, 0, 0));
            assertThat(Health.countLevels(List.of(
                            new Finding(FindingLevel.ERROR, "a", "one"),
                            new Finding(FindingLevel.ERROR, "a", "two"),
                            new Finding(FindingLevel.NOTE, "b", "three"))))
                    .isEqualTo(new FindingCounts(2, 0, 1));
        }

        @Test
        void callsALanguageHealthyWhenOnlyWarningsAndNotesWereFound() {
            assertThat(Health.isHealthy(List.of(new Finding(FindingLevel.WARNING, "a", "one"))))
                    .isTrue();
            assertThat(Health.isHealthy(List.of(new Finding(FindingLevel.ERROR, "a", "one"))))
                    .isFalse();
            assertThat(Health.isHealthy(List.of())).isTrue();
        }

        @Test
        void describesWhatItFoundInOneLine() {
            assertThat(Health.describe(List.of(), "latin")).isEqualTo("latin has nothing to report");
            assertThat(Health.describe(List.of(new Finding(FindingLevel.WARNING, "a", "one")), "latin"))
                    .isEqualTo("latin has 0 errors, 1 warnings and 0 notes");
        }
    }
}
