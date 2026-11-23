package io.github.rkaushikdev.stemwright.domain.drill;

import static io.github.rkaushikdev.stemwright.support.Drill.DRILL_CONTEXT;
import static io.github.rkaushikdev.stemwright.support.Drill.PUELLA;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static io.github.rkaushikdev.stemwright.support.Review.REVIEW_SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.errors.NotFoundException;
import io.github.rkaushikdev.stemwright.domain.mastery.CreditRule;
import io.github.rkaushikdev.stemwright.domain.mastery.LeechAction;
import io.github.rkaushikdev.stemwright.domain.mastery.LeechPolicy;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchVerdict;
import io.github.rkaushikdev.stemwright.domain.ortho.TransliterationRule;
import io.github.rkaushikdev.stemwright.domain.ortho.TransliterationTable;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewTest {

    private static final LocalDate TERM = d("2024-10-01");

    private static final Membership MEMBERSHIP =
            Membership.resolve(DRILL_CONTEXT, Deck.of(LATIN_NOUN, DeckInput.of("all")), TERM);

    private static final Cell GEN_SG = cellOf("case=gen;number=sg");
    private static final Cell ACC_SG = cellOf("case=acc;number=sg");

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static Cell cellOf(String bundle) {
        return MEMBERSHIP.cells().stream()
                .filter(cell ->
                        cell.lexemeId().equals(PUELLA.id()) && cell.bundle().equals(bundle))
                .findFirst()
                .orElseThrow();
    }

    private static MasteryLedger empty() {
        return MasteryLedger.empty("lrn_one");
    }

    /** The first answer of the session to the cell, on the day, in the deck that takes everything. */
    private static ReviewRequest.Builder request(Cell cell, String given, LocalDate on) {
        return ReviewRequest.builder()
                .cell(cell)
                .given(given)
                .on(on)
                .learnerId("lrn_one")
                .sessionId("sess_aaaaaa")
                .sequence(1)
                .deckKey("all");
    }

    private static ReviewResult ask(String given) {
        return ask(given, GEN_SG);
    }

    private static ReviewResult ask(String given, Cell cell) {
        return ask(given, cell, TERM);
    }

    private static ReviewResult ask(String given, Cell cell, LocalDate on) {
        return ask(given, cell, on, REVIEW_SETTINGS);
    }

    private static ReviewResult ask(String given, Cell cell, LocalDate on, ReviewSettings settings) {
        return Review.answer(
                DRILL_CONTEXT,
                settings,
                empty(),
                BurialSet.NONE,
                request(cell, given, on).build());
    }

    @Nested
    class ThroughATransliterationTable {

        /** A table that spells the letters of puellae as themselves and reads q as ae. */
        private static final TransliterationTable TYPED_AE = TransliterationTable.of(
                "latin",
                List.of(
                        new TransliterationRule("p", "p"),
                        new TransliterationRule("u", "u"),
                        new TransliterationRule("e", "e"),
                        new TransliterationRule("l", "l"),
                        new TransliterationRule("q", "ae")));

        @Test
        void marksTheAnswerAsTheTableReadsIt() {
            ReviewSettings settings = ReviewSettings.of(
                    REVIEW_SETTINGS.ortho(),
                    REVIEW_SETTINGS.rules(),
                    REVIEW_SETTINGS.ladder(),
                    REVIEW_SETTINGS.leech(),
                    TYPED_AE);
            ReviewResult result = ask("puellq", GEN_SG, TERM, settings);
            assertThat(result.match().isCorrect()).isTrue();
            assertThat(result.attempt().given()).isEqualTo("puellq");
            assertThat(result.ledger().strengthFor(GEN_SG.id()).correct()).isEqualTo(1);
        }

        @Test
        void readsNothingThroughTheTableByDefault() {
            assertThat(REVIEW_SETTINGS.transliteration()).isEqualTo(TransliterationTable.NONE);
            assertThat(ask("puellq").match().isCorrect()).isFalse();
        }
    }

    @Nested
    class ExpectedFormFor {

        @Test
        void realisesTheFormFromTheRevisionInForce() {
            assertThat(Review.expectedFormFor(DRILL_CONTEXT, REVIEW_SETTINGS, GEN_SG, TERM)
                            .form())
                    .isEqualTo("puellae");
        }

        @Test
        void refusesADayBeforeTheProfileWasPublished() {
            assertThatThrownBy(() -> Review.expectedFormFor(DRILL_CONTEXT, REVIEW_SETTINGS, GEN_SG, d("2024-01-01")))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Answer {

        @Test
        void marksARightAnswerAndSchedulesIt() {
            ReviewResult result = ask("puellae");
            assertThat(result.match().verdict()).isEqualTo(MatchVerdict.EXACT);
            assertThat(result.expected()).isEqualTo("puellae");
            assertThat(result.ledger().strengthFor(GEN_SG.id()).dueOn()).contains(d("2024-10-02"));
            assertThat(result.attempt().verdict()).isEqualTo(MatchVerdict.EXACT);
        }

        @Test
        void marksAWrongAnswerAsALapse() {
            ReviewResult result = ask("puellarum");
            assertThat(result.match().verdict()).isEqualTo(MatchVerdict.WRONG);
            assertThat(result.ledger().strengthFor(GEN_SG.id()).lapses()).isEqualTo(1);
        }

        @Test
        void holdsANearMissAtTheSameRung() {
            ReviewResult result = ask("puellai");
            assertThat(result.match().verdict()).isEqualTo(MatchVerdict.NEAR);
            assertThat(result.ledger().strengthFor(GEN_SG.id()).lapses()).isEqualTo(0);
            assertThat(result.ledger().strengthFor(GEN_SG.id()).correct()).isEqualTo(0);
        }

        @Test
        void copiesTheRevisionItGradedAgainstOntoTheAttempt() {
            ReviewResult result = ask("puellae");
            assertThat(result.attempt().revisionId()).isEqualTo(result.revisionId());
            assertThat(result.attempt().expected()).isEqualTo("puellae");
        }

        @Test
        void recordsTheToleranceLevelItResolved() {
            ReviewResult result = ask("puellae");
            assertThat(result.attempt().toleranceLevel()).isEqualTo("standard");
        }
    }

    @Nested
    class ToleranceInTheReview {

        @Test
        void takesACapitalInItsStrideAtTheStandardLevel() {
            assertThat(ask("Puellae").match().verdict()).isEqualTo(MatchVerdict.TOLERATED);
        }

        @Test
        void marksTheSameAnswerNearAtTheExactLevel() {
            ReviewResult result = Review.answer(
                    DRILL_CONTEXT,
                    REVIEW_SETTINGS,
                    empty(),
                    BurialSet.NONE,
                    request(GEN_SG, "Puellae", TERM).deckTolerance("exact").build());
            assertThat(result.match().verdict()).isEqualTo(MatchVerdict.NEAR);
        }

        @Test
        void letsACellOverrideTightenWhatTheDeckLoosened() {
            ReviewResult result = Review.answer(
                    DRILL_CONTEXT,
                    REVIEW_SETTINGS,
                    empty(),
                    BurialSet.NONE,
                    request(GEN_SG, "Puellae", TERM)
                            .deckTolerance("standard")
                            .cellTolerance("exact")
                            .build());
            assertThat(result.attempt().toleranceLevel()).isEqualTo("exact");
        }
    }

    @Nested
    class Twins {

        /** Puella's dative singular is a twin of the genitive singular under the revision in force. */
        private final Cell datSg = cellOf("case=dat;number=sg");

        /** The genitive singular answered second, after the first answer left this ledger. */
        private ReviewResult second(ReviewSettings settings, MasteryLedger ledger, String given) {
            return Review.answer(
                    DRILL_CONTEXT,
                    settings,
                    ledger,
                    BurialSet.NONE,
                    request(GEN_SG, given, TERM).sequence(2).build());
        }

        @Test
        void buriesTheAnsweredCellAndItsTwins() {
            ReviewResult result = ask("puellae");
            assertThat(result.buried().isBuried(GEN_SG.id())).isTrue();
            assertThat(result.buried().isBuried(datSg.id())).isTrue();
            assertThat(result.buried().isBuried(ACC_SG.id())).isFalse();
        }

        @Test
        void leavesBurialAloneWhenTheSettingsSwitchItOff() {
            ReviewResult result = ask("puellae", GEN_SG, TERM, REVIEW_SETTINGS.withBuryTwins(false));
            assertThat(result.buried()).isSameAs(BurialSet.NONE);
        }

        @Test
        void passesCreditToTwinsThatHaveBeenIntroduced() {
            ReviewResult first = ask("puellae", datSg);
            assertThat(second(REVIEW_SETTINGS, first.ledger(), "puellae").twinsCredited())
                    .isEqualTo(1);
        }

        @Test
        void passesNoCreditToTwinsNobodyHasMet() {
            assertThat(ask("puellae").twinsCredited()).isEqualTo(0);
        }

        @Test
        void passesNoCreditAtAllAfterAWrongAnswer() {
            ReviewResult first = ask("puellae", datSg);
            assertThat(second(REVIEW_SETTINGS, first.ledger(), "nonsense").twinsCredited())
                    .isEqualTo(0);
        }

        @Test
        void passesNoCreditWhenTheRuleSharesNothing() {
            ReviewResult first = ask("puellae", datSg);
            ReviewSettings settings =
                    REVIEW_SETTINGS.withCredit(CreditRule.builder().share(0).build());
            assertThat(second(settings, first.ledger(), "puellae").twinsCredited())
                    .isEqualTo(0);
        }

        @Test
        void buriesADifferentSetOnceALaterRevisionIsInForce() {
            Cell bellumNom = MEMBERSHIP.cells().stream()
                    .filter(cell -> cell.bundle().equals("case=nom;number=sg")
                            && !cell.lexemeId().equals(PUELLA.id()))
                    .findFirst()
                    .orElseThrow();
            ReviewResult early = ask("bellum", bellumNom, TERM);
            ReviewResult late = ask("bellum", bellumNom, d("2025-03-01"));
            assertThat(early.buried().buried()).hasSize(1);
            assertThat(late.buried().buried()).hasSize(2);
        }
    }

    @Nested
    class Leeches {

        @Test
        void suspendsACellThatLapsesPastTheThreshold() {
            MasteryLedger ledger = empty();
            boolean fired = false;
            for (int step = 1; step <= 3; step += 1) {
                ReviewResult result = Review.answer(
                        DRILL_CONTEXT,
                        REVIEW_SETTINGS,
                        ledger,
                        BurialSet.NONE,
                        request(GEN_SG, "nonsense", TERM).sequence(step).build());
                ledger = result.ledger();
                fired = result.leechFired();
            }
            assertThat(fired).isTrue();
            assertThat(ledger.strengthFor(GEN_SG.id()).suspended()).isTrue();
        }

        @Test
        void doesNotFireBelowTheThreshold() {
            assertThat(ask("nonsense").leechFired()).isFalse();
        }

        @Test
        void leavesTheScheduleAloneUnderTheTagAction() {
            ReviewSettings settings = REVIEW_SETTINGS.withLeech(
                    LeechPolicy.builder().threshold(1).action(LeechAction.TAG).build());
            ReviewResult result = ask("nonsense", GEN_SG, TERM, settings);
            assertThat(result.leechFired()).isTrue();
            assertThat(result.ledger().strengthFor(GEN_SG.id()).suspended()).isFalse();
        }
    }
}
