package io.github.rkaushikdev.stemwright.domain.drill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.stemwright.domain.IdKind;
import io.github.rkaushikdev.stemwright.domain.Identifiers;
import io.github.rkaushikdev.stemwright.domain.errors.ConflictException;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.AttemptInput;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchVerdict;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SessionTest {

    private static final LocalDate DAY = d("2025-03-03");
    private static final Session SESSION = Session.open("lrn_one", "first-year", DAY);

    private static final AttemptInput BASE = AttemptInput.builder()
            .learnerId("lrn_one")
            .sessionId(SESSION.id())
            .cellId("cell_aaaaaa")
            .deckKey("first-year")
            .on(DAY)
            .sequence(1)
            .given("puellae")
            .expected("puellae")
            .verdict(MatchVerdict.EXACT)
            .toleranceLevel("standard")
            .revisionId("rev_aaaaaa")
            .build();

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static Attempt makeAttempt() {
        return Attempt.of(BASE);
    }

    /** The base attempt with the parts the patch sets changed, and every other part kept. */
    private static Attempt makeAttempt(UnaryOperator<AttemptInput.Builder> patch) {
        return Attempt.of(patch.apply(BASE.toBuilder()).build());
    }

    private static Session record(Session session, Attempt entry) {
        return session.recordAttempt(entry, BurialSet.NONE);
    }

    @Nested
    class Open {

        @Test
        void startsEmptyAndOpen() {
            assertThat(SESSION.length()).isEqualTo(0);
            assertThat(SESSION.closed()).isFalse();
            assertThat(SESSION.buried()).isSameAs(BurialSet.NONE);
            assertThat(Identifiers.isId(IdKind.SESSION, SESSION.id())).isTrue();
        }

        @Test
        void derivesTheIdFromTheLearnerDeckDayAndOrdinal() {
            assertThat(SESSION.id()).isEqualTo(Session.idFor("lrn_one", "first-year", DAY, 1));
            assertThat(Session.open("lrn_one", "first-year", DAY, 2).id()).isNotEqualTo(SESSION.id());
        }

        @Test
        void lowerCasesTheDeckKey() {
            assertThat(Session.open("lrn_one", "First-Year", DAY).deckKey()).isEqualTo("first-year");
        }
    }

    @Nested
    class RecordAttempt {

        @Test
        void appendsAndLeavesTheOriginalAlone() {
            Session grown = record(SESSION, makeAttempt());
            assertThat(grown.length()).isEqualTo(1);
            assertThat(SESSION.length()).isEqualTo(0);
        }

        @Test
        void expectsTheSequenceToFollowOn() {
            assertThat(SESSION.nextSequence()).isEqualTo(1);
            Session grown = record(SESSION, makeAttempt());
            assertThat(grown.nextSequence()).isEqualTo(2);
        }

        @Test
        void refusesAnAttemptOutOfOrder() {
            assertThatThrownBy(() -> record(SESSION, makeAttempt(attempt -> attempt.sequence(3))))
                    .hasMessageContaining("expected 1 and got 3");
        }

        @Test
        void refusesAnAttemptFromAnotherSession() {
            Session other = Session.open("lrn_two", "first-year", DAY);
            Attempt stray = Attempt.of(AttemptInput.builder()
                    .learnerId("lrn_two")
                    .sessionId(other.id())
                    .cellId("cell_aaaaaa")
                    .deckKey("first-year")
                    .on(DAY)
                    .sequence(1)
                    .given("x")
                    .expected("y")
                    .verdict(MatchVerdict.WRONG)
                    .toleranceLevel("standard")
                    .revisionId("rev_aaaaaa")
                    .build());
            assertThatThrownBy(() -> record(SESSION, stray)).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesToRecordIntoAClosedSession() {
            Session closed = SESSION.close();
            assertThatThrownBy(() -> record(closed, makeAttempt())).isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    class Close {

        @Test
        void closesAnOpenSessionAndIsIdempotent() {
            Session closed = SESSION.close();
            assertThat(closed.closed()).isTrue();
            assertThat(closed.close()).isSameAs(closed);
        }
    }

    @Nested
    class CellsSeenAndAttemptsForCell {

        private final Session filled = record(
                record(
                        record(SESSION, makeAttempt(attempt -> attempt.sequence(1))),
                        makeAttempt(attempt -> attempt.sequence(2).cellId("cell_bbbbbb"))),
                makeAttempt(attempt ->
                        attempt.sequence(3).verdict(MatchVerdict.WRONG).given("x")));

        @Test
        void countsEachCellOnce() {
            assertThat(filled.cellsSeen()).containsExactly("cell_aaaaaa", "cell_bbbbbb");
        }

        @Test
        void findsEveryAttemptOnOneCell() {
            assertThat(filled.attemptsForCell("cell_aaaaaa")).hasSize(2);
            assertThat(filled.attemptsForCell("cell_zzzzzz")).isEmpty();
        }
    }

    @Nested
    class Summarise {

        private final Session filled = record(
                record(SESSION, makeAttempt(attempt -> attempt.sequence(1).elapsedMs(900))),
                makeAttempt(attempt -> attempt.sequence(2)
                        .verdict(MatchVerdict.WRONG)
                        .given("x")
                        .elapsedMs(1100)));

        @Test
        void countsTheVerdictsAndTheDistinctCells() {
            SessionSummary summary = filled.summarise();
            assertThat(summary.tally().total()).isEqualTo(2);
            assertThat(summary.tally().correct()).isEqualTo(1);
            assertThat(summary.distinctCells()).isEqualTo(1);
            assertThat(summary.accuracy()).isEqualTo(0.5);
            assertThat(summary.medianElapsedMs()).contains(1000.0);
        }

        @Test
        void summarisesAnEmptySessionWithoutDividingByZero() {
            SessionSummary summary = SESSION.summarise();
            assertThat(summary.accuracy()).isEqualTo(0);
            assertThat(summary.firstTryAccuracy()).isEqualTo(0);
            assertThat(summary.medianElapsedMs()).isEmpty();
            assertThat(summary.unresolved()).isEmpty();
        }

        @Test
        void countsOnlyTheFirstAnswerToEachCellForTheFirstTryShare() {
            Session relearnt = record(
                    record(
                            record(SESSION, makeAttempt(attempt -> attempt.verdict(MatchVerdict.WRONG)
                                    .given("x"))),
                            makeAttempt(attempt -> attempt.sequence(2))),
                    makeAttempt(attempt -> attempt.sequence(3).cellId("cell_bbbbbb")));
            SessionSummary summary = relearnt.summarise();
            assertThat(summary.accuracy()).isCloseTo(2.0 / 3, within(1e-9));
            assertThat(summary.firstTryAccuracy()).isEqualTo(0.5);
            assertThat(summary.unresolved()).isEmpty();
        }

        @Test
        void listsTheCellsLeftUnresolved() {
            assertThat(filled.summarise().unresolved()).containsExactly("cell_aaaaaa");
            assertThat(filled.summarise().firstTryAccuracy()).isEqualTo(1);
        }

        @Test
        void carriesTheLearnerDeckAndDayThrough() {
            SessionSummary summary = filled.summarise();
            assertThat(summary.learnerId()).isEqualTo("lrn_one");
            assertThat(summary.deckKey()).isEqualTo("first-year");
            assertThat(summary.on()).isEqualTo(d("2025-03-03"));
        }
    }

    @Nested
    class UnresolvedCells {

        @Test
        void namesACellWhoseLastAnswerWasWrong() {
            Session filled = record(SESSION, makeAttempt(attempt -> attempt.verdict(MatchVerdict.WRONG)
                    .given("x")));
            assertThat(filled.unresolvedCells()).containsExactly("cell_aaaaaa");
        }

        @Test
        void dropsACellThatWasGotRightAfterBeingGotWrong() {
            Session filled = record(
                    record(SESSION, makeAttempt(attempt -> attempt.verdict(MatchVerdict.WRONG)
                            .given("x"))),
                    makeAttempt(attempt -> attempt.sequence(2)));
            assertThat(filled.unresolvedCells()).isEmpty();
        }

        @Test
        void namesACellLeftAtANearMiss() {
            Session filled = record(SESSION, makeAttempt(attempt -> attempt.verdict(MatchVerdict.NEAR)));
            assertThat(filled.unresolvedCells()).containsExactly("cell_aaaaaa");
        }
    }

    @Nested
    class Describe {

        @Test
        void saysSoWhenNothingWasAnswered() {
            assertThat(SESSION.summarise().describe()).isEqualTo("no answers on 2025-03-03");
        }

        @Test
        void readsTheCountsOut() {
            Session filled = record(SESSION, makeAttempt());
            assertThat(filled.summarise().describe())
                    .isEqualTo("1 answers across 1 cells on 2025-03-03, 100 percent right");
        }

        @Test
        void addsTheFirstTryShareAndWhatIsLeftToGoOverWhenTheyTellTheLearnerSomething() {
            Session mixed = record(
                    record(
                            record(SESSION, makeAttempt(attempt -> attempt.verdict(MatchVerdict.WRONG)
                                    .given("x"))),
                            makeAttempt(attempt -> attempt.sequence(2))),
                    makeAttempt(
                            attempt -> attempt.sequence(3).cellId("cell_bbbbbb").verdict(MatchVerdict.NEAR)));
            assertThat(mixed.summarise().describe())
                    .isEqualTo("3 answers across 2 cells on 2025-03-03, 33 percent right,"
                            + " 0 percent at the first try, 1 still to go over");
        }
    }
}
