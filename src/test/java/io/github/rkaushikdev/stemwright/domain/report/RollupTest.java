package io.github.rkaushikdev.stemwright.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.byLessThan;

import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.AttemptInput;
import io.github.rkaushikdev.stemwright.domain.ortho.MatchVerdict;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RollupTest {

    private int sequence = 0;

    private final List<Attempt> attempts = List.of(
            make(input -> input.on(d("2025-03-03")).cellId("cell_a").verdict(MatchVerdict.EXACT)),
            make(input -> input.on(d("2025-03-03"))
                    .cellId("cell_b")
                    .verdict(MatchVerdict.WRONG)
                    .given("x")),
            make(input -> input.on(d("2025-03-05"))
                    .cellId("cell_b")
                    .verdict(MatchVerdict.WRONG)
                    .given("y")),
            make(input -> input.on(d("2025-03-05")).cellId("cell_b").verdict(MatchVerdict.EXACT)),
            make(input -> input.on(d("2025-03-05"))
                    .cellId("cell_c")
                    .verdict(MatchVerdict.NEAR)
                    .given("z")
                    .deckKey("second-year")));

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static DayRange r(String from, String to) {
        return DayRange.of(d(from), d(to));
    }

    /** A fresh attempt with the next sequence number, and the given parts changed. */
    private Attempt make(UnaryOperator<AttemptInput.Builder> patch) {
        sequence += 1;
        AttemptInput.Builder input = AttemptInput.builder()
                .learnerId("lrn_one")
                .sessionId("sess_aaaaaa")
                .cellId("cell_aaaaaa")
                .deckKey("first-year")
                .on(d("2025-03-03"))
                .sequence(sequence)
                .given("puellae")
                .expected("puellae")
                .verdict(MatchVerdict.EXACT)
                .toleranceLevel("standard")
                .revisionId("rev_aaaaaa");
        return Attempt.of(patch.apply(input).build());
    }

    private CellRollup cellB() {
        return Rollup.byCell(attempts).stream()
                .filter(entry -> entry.cellId().equals("cell_b"))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class Daily {

        @Test
        void hasARowForEveryDayOfTheRange() {
            List<DayRollup> rows = Rollup.daily(attempts, r("2025-03-03", "2025-03-06"));
            assertThat(rows.stream().map(DayRollup::on))
                    .containsExactly(d("2025-03-03"), d("2025-03-04"), d("2025-03-05"), d("2025-03-06"));
        }

        @Test
        void countsTheAttemptsOfEachDay() {
            List<DayRollup> rows = Rollup.daily(attempts, r("2025-03-03", "2025-03-05"));
            assertThat(rows.get(0).tally().total()).isEqualTo(2);
            assertThat(rows.get(1).tally().total()).isEqualTo(0);
            assertThat(rows.get(2).tally().total()).isEqualTo(3);
        }

        @Test
        void countsDistinctCellsRatherThanAttempts() {
            List<DayRollup> rows = Rollup.daily(attempts, r("2025-03-05", "2025-03-05"));
            assertThat(rows.get(0).distinctCells()).isEqualTo(2);
            assertThat(rows.get(0).tally().total()).isEqualTo(3);
        }

        @Test
        void readsAccuracyOffEachDay() {
            List<DayRollup> rows = Rollup.daily(attempts, r("2025-03-03", "2025-03-03"));
            assertThat(rows.get(0).accuracy()).isEqualTo(0.5);
        }

        @Test
        void isZeroRatherThanUndefinedOnADayWithNothing() {
            List<DayRollup> rows = Rollup.daily(attempts, r("2025-03-04", "2025-03-04"));
            assertThat(rows.get(0).accuracy()).isEqualTo(0.0);
        }

        @Test
        void countsACellSeenOnAnEarlierDayAsAReviewAndJudgesItByItsFirstAnswer() {
            List<DayRollup> rows = Rollup.daily(attempts, r("2025-03-05", "2025-03-05"));
            assertThat(rows.get(0).reviews()).isEqualTo(1);
            assertThat(rows.get(0).retained()).isZero();
        }

        @Test
        void callsARightFirstAnswerToAReviewRetained() {
            List<Attempt> log = new ArrayList<>(attempts);
            log.add(make(input -> input.on(d("2025-03-05")).cellId("cell_a").verdict(MatchVerdict.TOLERATED)));
            List<DayRollup> rows = Rollup.daily(log, r("2025-03-03", "2025-03-05"));
            assertThat(rows.stream().map(DayRollup::reviews)).containsExactly(0, 0, 2);
            assertThat(rows.stream().map(DayRollup::retained)).containsExactly(0, 0, 1);
        }

        @Test
        void readsTheLogBeforeTheRangeToTellAReviewFromAFirstSighting() {
            List<DayRollup> later = Rollup.daily(attempts, r("2025-03-04", "2025-03-05"));
            assertThat(later.get(1).reviews()).isEqualTo(1);
            List<DayRollup> cut = Rollup.daily(
                    Rollup.inRange(attempts, r("2025-03-05", "2025-03-05")), r("2025-03-05", "2025-03-05"));
            assertThat(cut.get(0).reviews()).isZero();
        }

        @Test
        void leavesOutAttemptsOutsideTheRange() {
            List<DayRollup> rows = Rollup.daily(attempts, r("2025-03-05", "2025-03-05"));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).tally().total()).isEqualTo(3);
        }
    }

    @Nested
    class ByCell {

        @Test
        void hasOneRowPerCellInKeyOrder() {
            assertThat(Rollup.byCell(attempts).stream().map(CellRollup::cellId))
                    .containsExactly("cell_a", "cell_b", "cell_c");
        }

        @Test
        void countsEveryAttemptOnACell() {
            CellRollup row = cellB();
            assertThat(row.tally().total()).isEqualTo(3);
            assertThat(row.tally().wrong()).isEqualTo(2);
            assertThat(row.accuracy()).isCloseTo(1.0 / 3, byLessThan(0.005));
        }

        @Test
        void reportsTheLatestVerdictAndTheDayItHappened() {
            CellRollup row = cellB();
            assertThat(row.lastVerdict().key()).isEqualTo("exact");
            assertThat(row.lastSeenOn()).isEqualTo(d("2025-03-05"));
        }

        @Test
        void givesNothingForAnEmptyLog() {
            assertThat(Rollup.byCell(List.of())).isEmpty();
        }
    }

    @Nested
    class HardestCells {

        @Test
        void ranksTheCellsWithTheMostWrongAnswersFirst() {
            assertThat(Rollup.hardestCells(Rollup.byCell(attempts)).stream().map(CellRollup::cellId))
                    .containsExactly("cell_b", "cell_c");
        }

        @Test
        void leavesOutCellsThatWereNeverMissed() {
            assertThat(Rollup.hardestCells(Rollup.byCell(attempts)).stream().map(CellRollup::cellId))
                    .doesNotContain("cell_a");
        }

        @Test
        void honoursTheLimit() {
            assertThat(Rollup.hardestCells(Rollup.byCell(attempts), 1)).hasSize(1);
        }

        @Test
        void givesNothingWhenEverythingWasRight() {
            List<Attempt> clean = List.of(make(input -> input.cellId("cell_a")), make(input -> input.cellId("cell_b")));
            assertThat(Rollup.hardestCells(Rollup.byCell(clean))).isEmpty();
        }
    }

    @Nested
    class ByDeck {

        @Test
        void splitsTheLogByDeck() {
            List<DeckRollup> rows = Rollup.byDeck(attempts);
            assertThat(rows.stream().map(DeckRollup::deckKey)).containsExactly("first-year", "second-year");
            assertThat(rows.get(0).tally().total()).isEqualTo(4);
        }

        @Test
        void countsTheDistinctDaysEachDeckWasDrilled() {
            assertThat(Rollup.byDeck(attempts).get(0).days()).isEqualTo(2);
            assertThat(Rollup.byDeck(attempts).get(1).days()).isEqualTo(1);
        }
    }

    @Nested
    class ActiveDays {

        @Test
        void namesEachDaySomethingWasAnsweredOnce() {
            assertThat(Rollup.activeDays(attempts).stream().sorted()).containsExactly(d("2025-03-03"), d("2025-03-05"));
        }

        @Test
        void isEmptyForAnEmptyLog() {
            assertThat(Rollup.activeDays(List.of())).isEmpty();
        }
    }

    @Nested
    class FirstCorrectOn {

        @Test
        void findsTheFirstDayACellWasAnsweredRight() {
            assertThat(Rollup.firstCorrectOn(attempts, "cell_b")).contains(d("2025-03-05"));
        }

        @Test
        void givesNothingForACellThatWasNeverRight() {
            assertThat(Rollup.firstCorrectOn(attempts, "cell_c")).isEmpty();
            assertThat(Rollup.firstCorrectOn(attempts, "cell_zzz")).isEmpty();
        }
    }

    @Nested
    class InRange {

        @Test
        void keepsOnlyTheAttemptsInsideTheRange() {
            assertThat(Rollup.inRange(attempts, r("2025-03-05", "2025-03-09"))).hasSize(3);
            assertThat(Rollup.inRange(attempts, r("2025-04-01", "2025-04-30"))).isEmpty();
        }
    }
}
