package io.github.rkaushikdev.stemwright.domain.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MasteryLedgerTest {

    private static final LocalDate DAY = d("2025-03-03");
    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3, 7).spread(0).build());

    private static final List<String> CELLS = List.of("cell_a", "cell_b", "cell_c", "cell_d");

    private static final MasteryLedger LEDGER = MasteryLedger.of(
            "lrn_one",
            List.of(
                    seen("cell_a", 1),
                    seen("cell_b", 3),
                    CellStrength.fresh("cell_c").recordLapse(FLAT, DAY).suspend()));

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static CellStrength seen(String cellId, int times) {
        CellStrength strength = CellStrength.fresh(cellId);
        for (int step = 0; step < times; step += 1) {
            strength = strength.recordCorrect(FLAT, DAY);
        }
        return strength;
    }

    private static List<String> idsOf(List<CellStrength> strengths) {
        return strengths.stream().map(CellStrength::cellId).toList();
    }

    @Nested
    class EmptyAndOf {

        @Test
        void startsWithNothingStored() {
            assertThat(MasteryLedger.empty("lrn_one").size()).isEqualTo(0);
        }

        @Test
        void keysWhatItWasGivenByCell() {
            assertThat(LEDGER.size()).isEqualTo(3);
            assertThat(LEDGER.hasStrength("cell_a")).isTrue();
            assertThat(LEDGER.hasStrength("cell_d")).isFalse();
        }
    }

    @Nested
    class StrengthFor {

        @Test
        void readsAStoredStrengthBack() {
            assertThat(LEDGER.strengthFor("cell_b").rung()).isEqualTo(2);
        }

        @Test
        void inventsAFreshStrengthForACellNobodyHasAnswered() {
            CellStrength fresh = LEDGER.strengthFor("cell_d");
            assertThat(fresh.cellId()).isEqualTo("cell_d");
            assertThat(fresh.seen()).isEqualTo(0);
            assertThat(fresh.dueOn()).isEmpty();
        }

        @Test
        void givesTheSameAnswerForAnAbsentCellAndOneStoredFresh() {
            MasteryLedger stored = LEDGER.withStrength(CellStrength.fresh("cell_d"));
            assertThat(stored.strengthFor("cell_d")).isEqualTo(LEDGER.strengthFor("cell_d"));
        }
    }

    @Nested
    class WithStrengthAndForgetCell {

        @Test
        void addsWithoutTouchingTheOriginal() {
            MasteryLedger grown = LEDGER.withStrength(seen("cell_d", 1));
            assertThat(grown.size()).isEqualTo(4);
            assertThat(LEDGER.size()).isEqualTo(3);
        }

        @Test
        void replacesAStrengthThatWasAlreadyThere() {
            MasteryLedger changed = LEDGER.withStrength(seen("cell_a", 3));
            assertThat(changed.strengthFor("cell_a").rung()).isEqualTo(2);
            assertThat(changed.size()).isEqualTo(3);
        }

        @Test
        void takesSeveralAtOnce() {
            MasteryLedger grown = LEDGER.withStrengths(List.of(seen("cell_d", 1), seen("cell_e", 1)));
            assertThat(grown.size()).isEqualTo(5);
        }

        @Test
        void forgetsACellAndToleratesOneItNeverHad() {
            assertThat(LEDGER.forgetCell("cell_a").size()).isEqualTo(2);
            assertThat(LEDGER.forgetCell("cell_z")).isSameAs(LEDGER);
        }
    }

    @Nested
    class DueCells {

        @Test
        void takesOnlyTheOnesThatHaveComeRound() {
            assertThat(idsOf(LEDGER.dueCells(CELLS, d("2025-03-04")))).containsExactly("cell_a");
        }

        @Test
        void sortsTheMostOverdueFirstAmongCellsOnTheSameInterval() {
            MasteryLedger mixed =
                    LEDGER.withStrength(CellStrength.fresh("cell_d").recordCorrect(FLAT, d("2025-03-01")));
            assertThat(idsOf(mixed.dueCells(CELLS, d("2025-03-05")))).containsExactly("cell_d", "cell_a");
        }

        @Test
        void putsACellFurtherPastItsOwnIntervalAheadOfOneMoreDaysLate() {
            CellStrength shortInterval = CellStrength.fresh("cell_short").recordCorrect(FLAT, d("2025-03-10"));
            CellStrength longInterval = new CellStrength(
                    "cell_long", 3, Optional.of(d("2025-03-01")), 4, 4, 0, 4, 0, Optional.of(d("2025-02-13")), false);
            MasteryLedger both = MasteryLedger.of("lrn_one", List.of(longInterval, shortInterval));
            LocalDate on = d("2025-03-13");

            assertThat(longInterval.daysOverdue(on)).isGreaterThan(shortInterval.daysOverdue(on));
            assertThat(shortInterval.overdueShare(on)).isGreaterThan(longInterval.overdueShare(on));
            assertThat(idsOf(both.dueCells(List.of("cell_long", "cell_short"), on)))
                    .containsExactly("cell_short", "cell_long");
        }

        @Test
        void fallsBackToTheDaysOverdueAndThenTheIdOnAnEqualShare() {
            MasteryLedger twins = MasteryLedger.of(
                    "lrn_one",
                    List.of(
                            CellStrength.fresh("cell_y").recordCorrect(FLAT, d("2025-03-01")),
                            CellStrength.fresh("cell_x").recordCorrect(FLAT, d("2025-03-01"))));
            assertThat(idsOf(twins.dueCells(List.of("cell_y", "cell_x"), d("2025-03-04"))))
                    .containsExactly("cell_x", "cell_y");
        }

        @Test
        void leavesOutASuspendedCellHoweverOverdue() {
            assertThat(idsOf(LEDGER.dueCells(CELLS, d("2030-01-01")))).doesNotContain("cell_c");
        }

        @Test
        void leavesOutACellNobodyHasAnswered() {
            assertThat(idsOf(LEDGER.dueCells(CELLS, d("2030-01-01")))).doesNotContain("cell_d");
        }

        @Test
        void onlyLooksAtTheCellsItWasAskedAbout() {
            assertThat(idsOf(LEDGER.dueCells(List.of("cell_b"), d("2030-01-01"))))
                    .containsExactly("cell_b");
        }
    }

    @Nested
    class NewCellsAndSuspendedCells {

        @Test
        void namesTheCellsThatHaveNeverBeenAnswered() {
            assertThat(idsOf(LEDGER.newCells(CELLS))).containsExactly("cell_d");
        }

        @Test
        void namesTheSuspendedCells() {
            assertThat(idsOf(LEDGER.suspendedCells(CELLS))).containsExactly("cell_c");
        }

        @Test
        void countsASuspendedCellAsNeitherNewNorDue() {
            assertThat(idsOf(LEDGER.newCells(CELLS))).doesNotContain("cell_c");
        }
    }

    @Nested
    class Counts {

        @Test
        void splitsTheCellsIntoTheFiveBuckets() {
            LedgerCounts counts = LEDGER.counts(CELLS, FLAT, d("2025-03-04"));
            assertThat(counts).isEqualTo(new LedgerCounts(4, 2, 1, 1, 1, 1));
        }

        @Test
        void countsNothingForAnEmptyCandidateList() {
            assertThat(LEDGER.counts(List.of(), FLAT, DAY).known()).isEqualTo(0);
        }

        @Test
        void countsEveryCellAsUntouchedForAFreshLedger() {
            LedgerCounts counts = MasteryLedger.empty("lrn_one").counts(CELLS, FLAT, DAY);
            assertThat(counts.untouched()).isEqualTo(4);
            assertThat(counts.introduced()).isEqualTo(0);
        }
    }

    @Nested
    class NextDueDate {

        @Test
        void findsTheSoonestDueDay() {
            assertThat(LEDGER.nextDueDate(CELLS)).contains(d("2025-03-04"));
        }

        @Test
        void ignoresSuspendedAndUntouchedCells() {
            assertThat(LEDGER.nextDueDate(List.of("cell_c", "cell_d"))).isEmpty();
        }

        @Test
        void givesNothingForAnEmptyLedger() {
            assertThat(MasteryLedger.empty("lrn_one").nextDueDate(CELLS)).isEmpty();
        }
    }
}
