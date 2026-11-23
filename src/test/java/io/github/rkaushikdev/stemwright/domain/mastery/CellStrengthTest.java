package io.github.rkaushikdev.stemwright.domain.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CellStrengthTest {

    private static final LocalDate DAY = d("2025-03-03");
    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3, 7, 16).spread(0).build());

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    @Nested
    class Fresh {

        @Test
        void startsUnseenUnscheduledAndAtTheBottomRung() {
            CellStrength fresh = CellStrength.fresh("cell_a");
            assertThat(fresh.rung()).isEqualTo(0);
            assertThat(fresh.dueOn()).isEmpty();
            assertThat(fresh.seen()).isEqualTo(0);
            assertThat(fresh.isNew()).isTrue();
            assertThat(fresh.isIntroduced()).isFalse();
        }

        @Test
        void isNotDueBecauseItHasNeverBeenAsked() {
            assertThat(CellStrength.fresh("cell_a").isDue(DAY)).isFalse();
        }
    }

    @Nested
    class RecordCorrect {

        @Test
        void doesNotClimbOnTheFirstSighting() {
            CellStrength first = CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY);
            assertThat(first.rung()).isEqualTo(0);
            assertThat(first.dueOn()).contains(d("2025-03-04"));
            assertThat(first.seen()).isEqualTo(1);
            assertThat(first.streak()).isEqualTo(1);
        }

        @Test
        void climbsOnEverySightingAfterTheFirst() {
            CellStrength first = CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY);
            CellStrength second = first.recordCorrect(FLAT, d("2025-03-04"));
            assertThat(second.rung()).isEqualTo(1);
            assertThat(second.dueOn()).contains(d("2025-03-07"));
        }

        @Test
        void stopsClimbingAtTheTopOfTheLadder() {
            CellStrength strength = CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY);
            for (int step = 0; step < 8; step += 1) {
                strength = strength.recordCorrect(FLAT, DAY);
            }
            assertThat(strength.rung()).isEqualTo(3);
            assertThat(strength.isMature(FLAT)).isTrue();
        }

        @Test
        void recordsTheDayItWasLastSeen() {
            assertThat(CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY).lastSeenOn())
                    .contains(d("2025-03-03"));
        }
    }

    @Nested
    class RecordLapse {

        @Test
        void dropsTheRungAndReschedulesForTheShortestInterval() {
            CellStrength once = CellStrength.fresh("c").recordCorrect(FLAT, DAY);
            CellStrength known = once.recordCorrect(FLAT, DAY).recordCorrect(FLAT, DAY);
            assertThat(known.rung()).isEqualTo(2);
            CellStrength lapsed = known.recordLapse(FLAT, d("2025-03-20"));
            assertThat(lapsed.rung()).isEqualTo(0);
            assertThat(lapsed.dueOn()).contains(d("2025-03-21"));
            assertThat(lapsed.lapses()).isEqualTo(1);
            assertThat(lapsed.streak()).isEqualTo(0);
        }

        @Test
        void countsTheSightingEvenThoughItWasWrong() {
            CellStrength lapsed = CellStrength.fresh("cell_a").recordLapse(FLAT, DAY);
            assertThat(lapsed.seen()).isEqualTo(1);
            assertThat(lapsed.correct()).isEqualTo(0);
        }

        @Test
        void cannotDropBelowTheBottomRung() {
            assertThat(CellStrength.fresh("cell_a").recordLapse(FLAT, DAY).rung())
                    .isEqualTo(0);
        }
    }

    @Nested
    class RecordNearMiss {

        private final CellStrength known =
                CellStrength.fresh("c").recordCorrect(FLAT, DAY).recordCorrect(FLAT, DAY);

        @Test
        void leavesTheRungWhereItWas() {
            assertThat(known.recordNearMiss(FLAT, d("2025-03-10")).rung()).isEqualTo(known.rung());
        }

        @Test
        void bringsTheCellBackAtTheShortestIntervalAndBreaksTheStreak() {
            CellStrength near = known.recordNearMiss(FLAT, d("2025-03-10"));
            assertThat(near.dueOn()).contains(d("2025-03-11"));
            assertThat(near.streak()).isEqualTo(0);
        }

        @Test
        void doesNotCountAsALapseOrAsCorrect() {
            CellStrength near = CellStrength.fresh("cell_a").recordNearMiss(FLAT, DAY);
            assertThat(near.lapses()).isEqualTo(0);
            assertThat(near.correct()).isEqualTo(0);
            assertThat(near.seen()).isEqualTo(1);
        }
    }

    @Nested
    class LapseRun {

        @Test
        void growsWithEachLapseAndClearsOnARightAnswer() {
            CellStrength twice = CellStrength.fresh("c").recordLapse(FLAT, DAY).recordLapse(FLAT, DAY);
            assertThat(twice.lapseRun()).isEqualTo(2);
            CellStrength recovered = twice.recordCorrect(FLAT, DAY);
            assertThat(recovered.lapseRun()).isZero();
            assertThat(recovered.lapses()).isEqualTo(2);
            assertThat(recovered.recordLapse(FLAT, DAY).lapseRun()).isEqualTo(1);
        }

        @Test
        void isLeftAloneByANearMissAndByEveryChangeOfSchedule() {
            CellStrength lapsed = CellStrength.fresh("c").recordLapse(FLAT, DAY);
            assertThat(lapsed.recordNearMiss(FLAT, DAY).lapseRun()).isEqualTo(1);
            assertThat(lapsed.suspend().lapseRun()).isEqualTo(1);
            assertThat(lapsed.suspend().unsuspend(FLAT, DAY).lapseRun()).isEqualTo(1);
            assertThat(lapsed.deferTo(d("2025-04-01")).lapseRun()).isEqualTo(1);
        }
    }

    @Nested
    class IsDueAndDaysOverdue {

        private final CellStrength seen = CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY);

        @Test
        void isDueOnTheDayItComesRoundAndAfter() {
            assertThat(seen.isDue(d("2025-03-03"))).isFalse();
            assertThat(seen.isDue(d("2025-03-04"))).isTrue();
            assertThat(seen.isDue(d("2025-03-09"))).isTrue();
        }

        @Test
        void countsHowFarPastDueItIs() {
            assertThat(seen.daysOverdue(d("2025-03-04"))).isEqualTo(0);
            assertThat(seen.daysOverdue(d("2025-03-09"))).isEqualTo(5);
            assertThat(seen.daysOverdue(d("2025-03-01"))).isEqualTo(0);
        }

        @Test
        void reportsNothingOverdueForACellThatWasNeverAsked() {
            assertThat(CellStrength.fresh("cell_a").daysOverdue(DAY)).isEqualTo(0);
        }
    }

    @Nested
    class Suspension {

        private final CellStrength seen =
                CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY).recordCorrect(FLAT, DAY);

        @Test
        void takesASuspendedCellOutOfTheDueSet() {
            assertThat(seen.suspend().isDue(d("2030-01-01"))).isFalse();
            assertThat(seen.suspend().isNew()).isFalse();
        }

        @Test
        void reschedulesFromTheRungItKeptWhenItComesBack() {
            CellStrength revived = seen.suspend().unsuspend(FLAT, d("2025-06-01"));
            assertThat(revived.suspended()).isFalse();
            assertThat(revived.rung()).isEqualTo(seen.rung());
            assertThat(revived.dueOn()).contains(d("2025-06-04"));
        }

        @Test
        void leavesACellThatWasNotSuspendedAlone() {
            assertThat(seen.unsuspend(FLAT, d("2025-06-01"))).isSameAs(seen);
        }
    }

    @Nested
    class DeferTo {

        private final CellStrength seen = CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY);

        @Test
        void pushesADueDateLater() {
            assertThat(seen.deferTo(d("2025-03-10")).dueOn()).contains(d("2025-03-10"));
        }

        @Test
        void neverPullsADueDateForward() {
            assertThat(seen.deferTo(d("2025-03-01")).dueOn()).contains(d("2025-03-04"));
        }

        @Test
        void leavesANewCellAlone() {
            CellStrength fresh = CellStrength.fresh("cell_a");
            assertThat(fresh.deferTo(d("2025-03-10"))).isSameAs(fresh);
        }
    }

    @Nested
    class Accuracy {

        @Test
        void isZeroForACellNobodyHasAnswered() {
            assertThat(CellStrength.fresh("cell_a").accuracy()).isEqualTo(0.0);
        }

        @Test
        void isTheShareOfSightingsThatWereRight() {
            CellStrength mixed =
                    CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY).recordLapse(FLAT, DAY);
            assertThat(mixed.accuracy()).isEqualTo(0.5);
        }
    }

    @Nested
    class Describe {

        @Test
        void saysANewCellIsNotIntroduced() {
            assertThat(CellStrength.fresh("cell_a").describe(Ladder.STANDARD)).isEqualTo("not introduced yet");
        }

        @Test
        void namesTheRungAndTheNextDay() {
            CellStrength seen = CellStrength.fresh("cell_a").recordCorrect(FLAT, DAY);
            assertThat(seen.describe(FLAT)).isEqualTo("rung 0 of 3, next on 2025-03-04");
        }

        @Test
        void saysHowManyLapsesASuspensionFollowed() {
            CellStrength leech = CellStrength.fresh("cell_a")
                    .recordLapse(FLAT, DAY)
                    .recordLapse(FLAT, DAY)
                    .suspend();
            assertThat(leech.describe(FLAT)).isEqualTo("suspended after 2 lapses");
        }
    }

    @Nested
    class TheFirstInterval {

        @Test
        void isTheBottomRungOfWhateverLadderIsInUse() {
            CellStrength seen = CellStrength.fresh("cell_a").recordCorrect(Ladder.STANDARD, DAY);
            assertThat(seen.dueOn()).contains(d("2025-03-0" + (3 + Ladder.STANDARD.intervalAt(0))));
        }
    }
}
