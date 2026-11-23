package io.github.rkaushikdev.stemwright.domain.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LeechPolicyTest {

    private static final LocalDate DAY = d("2025-03-03");
    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 2, 6, 14).spread(0).build());

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static CellStrength lapsedTimes(String cellId, int times) {
        CellStrength strength = CellStrength.fresh(cellId);
        for (int step = 0; step < times; step += 1) {
            strength = strength.recordLapse(FLAT, d("2025-01-01"));
        }
        return strength;
    }

    private static LeechPolicy.Builder policy() {
        return LeechPolicy.builder();
    }

    @Nested
    class Build {

        @Test
        void takesTheDefaultWhenNothingIsPatched() {
            assertThat(policy().build()).isEqualTo(LeechPolicy.DEFAULT);
        }

        /**
         * A threshold is an {@code int}, so a threshold of two and a half cannot be written; the
         * message is checked on a negative threshold instead.
         */
        @Test
        void refusesAThresholdBelowOne() {
            assertThatThrownBy(() -> policy().threshold(0).build()).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> policy().threshold(-3).build()).hasMessageContaining("whole number above zero");
        }

        @Test
        void refusesANegativeDemotionRestAndAcceptsZero() {
            assertThatThrownBy(() -> policy().demotionRest(-1).build()).hasMessageContaining("must not be negative");
            assertThat(policy().demotionRest(0).build().demotionRest()).isEqualTo(0);
        }
    }

    @Nested
    class LapseCount {

        private final CellStrength strength = lapsedTimes("cell_a", 4);

        @Test
        void countsEveryLapseByDefault() {
            assertThat(policy().build().lapseCount(strength)).isEqualTo(4);
        }

        @Test
        void countsNoneOnceTheLastAnswerWasRight() {
            CellStrength recovered = strength.recordCorrect(FLAT, DAY);
            assertThat(policy().consecutiveOnly(true).build().lapseCount(recovered))
                    .isEqualTo(0);
            assertThat(policy().build().lapseCount(recovered)).isEqualTo(4);
        }

        @Test
        void countsOnlyTheLapsesSinceTheStreakBroke() {
            CellStrength relapsed = strength.recordCorrect(FLAT, DAY).recordLapse(FLAT, DAY);
            assertThat(policy().consecutiveOnly(true).build().lapseCount(relapsed))
                    .isEqualTo(1);
            assertThat(policy().build().lapseCount(relapsed)).isEqualTo(5);
        }

        @Test
        void keepsTheRunThroughANearMissWithoutAddingToIt() {
            CellStrength nearly = strength.recordNearMiss(FLAT, DAY);
            assertThat(policy().consecutiveOnly(true).build().lapseCount(nearly))
                    .isEqualTo(4);
            assertThat(policy().consecutiveOnly(true).build().lapseCount(nearly.recordLapse(FLAT, DAY)))
                    .isEqualTo(5);
        }

        @Test
        void doesNotSuspendARecoveredCellOnItsFirstLapseBack() {
            LeechPolicy consecutive =
                    policy().threshold(3).consecutiveOnly(true).build();
            CellStrength relapsed =
                    lapsedTimes("cell_b", 3).recordCorrect(FLAT, DAY).recordLapse(FLAT, DAY);
            assertThat(consecutive.isLeech(relapsed)).isFalse();
            assertThat(consecutive.isLeech(relapsed.recordLapse(FLAT, DAY).recordLapse(FLAT, DAY)))
                    .isTrue();
        }
    }

    @Nested
    class AfterAnAnswer {

        private CellStrength lapseOnce(CellStrength strength) {
            return strength.recordLapse(FLAT, DAY);
        }

        @Test
        void firesWhenTheLapseReachesTheThreshold() {
            LeechPolicy policy = policy().threshold(3).build();
            CellStrength before = lapsedTimes("cell_a", 2);
            LeechPolicy.Applied applied = policy.applyTo(before, lapseOnce(before), FLAT, DAY);
            assertThat(applied.outcome().fired()).isTrue();
            assertThat(applied.strength().suspended()).isTrue();
        }

        @Test
        void givesABroughtBackLeechHalfTheThresholdBeforeCatchingItAgain() {
            LeechPolicy policy = policy().threshold(4).build();
            CellStrength back = lapsedTimes("cell_a", 4).suspend().unsuspend(FLAT, DAY);
            CellStrength fifth = lapseOnce(back);
            CellStrength sixth = lapseOnce(fifth);
            assertThat(policy.applyTo(back, fifth, FLAT, DAY).outcome().fired()).isFalse();
            assertThat(policy.applyTo(back, fifth, FLAT, DAY).strength()).isSameAs(fifth);
            assertThat(policy.applyTo(fifth, sixth, FLAT, DAY).outcome().fired())
                    .isTrue();
            assertThat(policy.applyTo(sixth, lapseOnce(sixth), FLAT, DAY)
                            .outcome()
                            .fired())
                    .isFalse();
        }

        @Test
        void neverFiresOnARightAnswerOrANearMissPastTheThreshold() {
            LeechPolicy tagging = policy().threshold(3).action(LeechAction.TAG).build();
            CellStrength tagged = lapsedTimes("cell_a", 3);
            assertThat(tagging.applyTo(tagged, tagged.recordCorrect(FLAT, DAY), FLAT, DAY)
                            .outcome()
                            .fired())
                    .isFalse();
            assertThat(tagging.applyTo(tagged, tagged.recordNearMiss(FLAT, DAY), FLAT, DAY)
                            .outcome()
                            .fired())
                    .isFalse();
            assertThat(tagging.isLeech(tagged.recordCorrect(FLAT, DAY))).isTrue();
        }
    }

    @Nested
    class IsLeech {

        @Test
        void firesAtTheThresholdAndNotBefore() {
            LeechPolicy policy = policy().threshold(3).build();
            assertThat(policy.isLeech(lapsedTimes("cell_a", 2))).isFalse();
            assertThat(policy.isLeech(lapsedTimes("cell_a", 3))).isTrue();
        }

        @Test
        void neverFiresOnACellThatIsAlreadySuspended() {
            assertThat(policy().threshold(3)
                            .build()
                            .isLeech(lapsedTimes("cell_a", 9).suspend()))
                    .isFalse();
        }

        @Test
        void neverFiresOnACellNobodyHasAnswered() {
            assertThat(policy().threshold(1).build().isLeech(CellStrength.fresh("cell_a")))
                    .isFalse();
        }
    }

    @Nested
    class ApplyTo {

        private final CellStrength leech = lapsedTimes("cell_a", 4);

        @Test
        void changesNothingWhenTheCellIsNotALeech() {
            CellStrength quiet = lapsedTimes("cell_a", 1);
            LeechPolicy.Applied applied = policy().threshold(3).build().applyTo(quiet, FLAT, DAY);
            assertThat(applied.strength()).isSameAs(quiet);
            assertThat(applied.outcome().fired()).isFalse();
            assertThat(applied.outcome().action()).isEmpty();
        }

        @Test
        void suspendsUnderTheSuspendAction() {
            LeechPolicy.Applied applied = policy().threshold(3).build().applyTo(leech, FLAT, DAY);
            assertThat(applied.strength().suspended()).isTrue();
            assertThat(applied.outcome())
                    .isEqualTo(new LeechOutcome("cell_a", true, Optional.of(LeechAction.SUSPEND), 4));
        }

        @Test
        void sendsTheCellToTheBottomRungUnderTheDemoteAction() {
            CellStrength climbed = leech.recordCorrect(FLAT, DAY).recordCorrect(FLAT, DAY);
            LeechPolicy policy = policy().threshold(3)
                    .action(LeechAction.DEMOTE)
                    .demotionRest(5)
                    .build();
            LeechPolicy.Applied applied = policy.applyTo(climbed, FLAT, DAY);
            assertThat(applied.strength().rung()).isEqualTo(0);
            assertThat(applied.strength().suspended()).isFalse();
            assertThat(applied.strength().dueOn()).contains(d("2025-03-08"));
        }

        @Test
        void waitsAtLeastTheBottomIntervalWhenTheRestIsShorter() {
            LeechPolicy policy = policy().threshold(3)
                    .action(LeechAction.DEMOTE)
                    .demotionRest(0)
                    .build();
            assertThat(policy.applyTo(leech, FLAT, DAY).strength().dueOn()).contains(d("2025-03-05"));
        }

        @Test
        void changesNothingButTheReportUnderTheTagAction() {
            LeechPolicy policy = policy().threshold(3).action(LeechAction.TAG).build();
            LeechPolicy.Applied applied = policy.applyTo(leech, FLAT, DAY);
            assertThat(applied.strength()).isSameAs(leech);
            assertThat(applied.outcome().fired()).isTrue();
            assertThat(applied.outcome().action()).contains(LeechAction.TAG);
        }
    }

    @Nested
    class LeechesIn {

        private final MasteryLedger ledger = MasteryLedger.of(
                "lrn_one", List.of(lapsedTimes("cell_a", 5), lapsedTimes("cell_b", 3), lapsedTimes("cell_c", 1)));
        private final List<String> cells = List.of("cell_a", "cell_b", "cell_c", "cell_d");

        @Test
        void namesTheCellsOverTheThresholdWorstFirst() {
            List<CellStrength> found = policy().threshold(3).build().leechesIn(ledger, cells);
            assertThat(found).extracting(CellStrength::cellId).containsExactly("cell_a", "cell_b");
        }

        @Test
        void namesNoneWhenTheThresholdIsAboveThemAll() {
            assertThat(policy().threshold(9).build().leechesIn(ledger, cells)).isEmpty();
        }

        @Test
        void onlyLooksAtTheCellsItWasAskedAbout() {
            assertThat(policy().threshold(3).build().leechesIn(ledger, List.of("cell_b")))
                    .hasSize(1);
        }
    }

    @Nested
    class Sweep {

        private final MasteryLedger ledger =
                MasteryLedger.of("lrn_one", List.of(lapsedTimes("cell_a", 5), lapsedTimes("cell_b", 3)));
        private final List<String> cells = List.of("cell_a", "cell_b", "cell_c");

        @Test
        void actsOnEveryLeechAndReportsEachOne() {
            LeechPolicy.Sweep swept = policy().threshold(3).build().sweep(ledger, cells, FLAT, DAY);
            assertThat(swept.outcomes()).hasSize(2);
            assertThat(swept.ledger().strengthFor("cell_a").suspended()).isTrue();
            assertThat(swept.ledger().strengthFor("cell_b").suspended()).isTrue();
        }

        @Test
        void leavesTheOriginalLedgerAlone() {
            policy().threshold(3).build().sweep(ledger, cells, FLAT, DAY);
            assertThat(ledger.strengthFor("cell_a").suspended()).isFalse();
        }

        @Test
        void changesNothingUnderTheTagAction() {
            LeechPolicy policy = policy().threshold(3).action(LeechAction.TAG).build();
            LeechPolicy.Sweep swept = policy.sweep(ledger, cells, FLAT, DAY);
            assertThat(swept.ledger()).isSameAs(ledger);
            assertThat(swept.outcomes()).hasSize(2);
        }

        @Test
        void reportsNothingWhenThereAreNoLeeches() {
            LeechPolicy.Sweep swept = policy().threshold(9).build().sweep(ledger, cells, FLAT, DAY);
            assertThat(swept.outcomes()).isEmpty();
        }
    }

    @Nested
    class Describe {

        @Test
        void readsEachActionOut() {
            assertThat(policy().threshold(4).build().describe())
                    .isEqualTo("suspend a cell after 4 lapses and again every 2 after");
            assertThat(policy().threshold(4).action(LeechAction.DEMOTE).build().describe())
                    .isEqualTo("send a cell back to the bottom rung after 4 lapses and again every 2 after");
            assertThat(policy().threshold(4).action(LeechAction.TAG).build().describe())
                    .isEqualTo("flag a cell for review after 4 lapses and again every 2 after, changing nothing");
        }

        @Test
        void saysWhenItIsCountingConsecutiveLapses() {
            assertThat(policy().threshold(2).consecutiveOnly(true).build().describe())
                    .isEqualTo("suspend a cell after 2 consecutive lapses and again every 1 after");
        }

        @Test
        void roundsHalfAnOddThresholdUp() {
            assertThat(policy().threshold(5).build().describe())
                    .isEqualTo("suspend a cell after 5 lapses and again every 3 after");
        }
    }
}
