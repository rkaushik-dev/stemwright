package io.github.rkaushikdev.stemwright.domain.mastery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import java.time.LocalDate;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ShieldSetTest {

    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3, 7).spread(0).build());

    private static final ShieldSet SHIELDS = ShieldSet.of(List.of(
            ShieldInput.of(r("2025-03-10", "2025-03-16"), "reading week"),
            ShieldInput.of(r("2025-04-02", "2025-04-04"), "field trip to the epigraphy museum")));

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static DayRange r(String from, String to) {
        return DayRange.of(d(from), d(to));
    }

    @Nested
    class Of {

        @Test
        void keepsTheWindowsItWasGiven() {
            assertThat(SHIELDS.shields()).hasSize(2);
            assertThat(SHIELDS.windows()).hasSize(2);
        }

        @Test
        void defaultsAMissingReason() {
            ShieldSet bare = ShieldSet.of(List.of(ShieldInput.of(r("2025-03-10", "2025-03-11"))));
            assertThat(bare.shields().get(0).reason()).isEqualTo("no reason recorded");
        }

        @Test
        void mergesWindowsThatTouch() {
            ShieldSet merged = ShieldSet.of(List.of(
                    ShieldInput.of(r("2025-03-10", "2025-03-12")), ShieldInput.of(r("2025-03-13", "2025-03-15"))));
            assertThat(merged.shields()).hasSize(2);
            assertThat(merged.windows()).hasSize(1);
        }

        @Test
        void buildsAnEmptySet() {
            assertThat(ShieldSet.of(List.of()).windows()).isEmpty();
        }
    }

    @Nested
    class IsShielded {

        @Test
        void coversBothEndpoints() {
            assertThat(SHIELDS.isShielded(d("2025-03-10"))).isTrue();
            assertThat(SHIELDS.isShielded(d("2025-03-16"))).isTrue();
        }

        @Test
        void leavesTheDaysEitherSideOpen() {
            assertThat(SHIELDS.isShielded(d("2025-03-09"))).isFalse();
            assertThat(SHIELDS.isShielded(d("2025-03-17"))).isFalse();
        }

        @Test
        void shieldsNothingWhenTheSetIsEmpty() {
            assertThat(ShieldSet.NONE.isShielded(d("2025-03-12"))).isFalse();
        }

        @Test
        void namesTheShieldCoveringADay() {
            assertThat(SHIELDS.covering(d("2025-04-03")).map(Shield::reason))
                    .contains("field trip to the epigraphy museum");
            assertThat(SHIELDS.covering(d("2025-05-01"))).isEmpty();
        }
    }

    @Nested
    class FirstOpenDay {

        @Test
        void leavesAnOpenDayWhereItIs() {
            assertThat(SHIELDS.firstOpenDay(d("2025-03-09"))).isEqualTo(d("2025-03-09"));
        }

        @Test
        void stepsPastTheEndOfAShield() {
            assertThat(SHIELDS.firstOpenDay(d("2025-03-12"))).isEqualTo(d("2025-03-17"));
        }

        @Test
        void stepsPastTwoShieldsThatAbutAfterNormalising() {
            ShieldSet back = ShieldSet.of(List.of(
                    ShieldInput.of(r("2025-03-10", "2025-03-12")), ShieldInput.of(r("2025-03-13", "2025-03-15"))));
            assertThat(back.firstOpenDay(d("2025-03-11"))).isEqualTo(d("2025-03-16"));
        }
    }

    @Nested
    class ShieldedDaysIn {

        @Test
        void countsTheDaysAShieldCoversInsideARange() {
            assertThat(SHIELDS.shieldedDaysIn(r("2025-03-01", "2025-03-31"))).isEqualTo(7);
        }

        @Test
        void clipsAShieldThatOverhangsTheRange() {
            assertThat(SHIELDS.shieldedDaysIn(r("2025-03-14", "2025-03-20"))).isEqualTo(3);
        }

        @Test
        void countsNothingWhenTheRangeMissesEveryShield() {
            assertThat(SHIELDS.shieldedDaysIn(r("2025-05-01", "2025-05-31"))).isEqualTo(0);
        }

        @Test
        void countsBothShieldsWhenTheRangeSpansThem() {
            assertThat(SHIELDS.shieldedDaysIn(r("2025-01-01", "2025-12-31"))).isEqualTo(10);
        }
    }

    @Nested
    class ApplyTo {

        private final CellStrength inside = CellStrength.fresh("cell_a").recordCorrect(FLAT, d("2025-03-11"));
        private final CellStrength before = CellStrength.fresh("cell_b").recordCorrect(FLAT, d("2025-03-01"));
        private final MasteryLedger ledger = MasteryLedger.of("lrn_one", List.of(inside, before));
        private final List<String> cells = List.of("cell_a", "cell_b", "cell_c");

        @Test
        void pushesAReviewDueInsideAShieldToTheFarSide() {
            ShieldSet.Applied applied = SHIELDS.applyTo(ledger, cells);
            assertThat(applied.ledger().strengthFor("cell_a").dueOn()).contains(d("2025-03-17"));
            assertThat(applied.outcomes())
                    .containsExactly(new ShieldOutcome("cell_a", d("2025-03-12"), d("2025-03-17")));
        }

        @Test
        void leavesAReviewDueBeforeTheShieldAlone() {
            ShieldSet.Applied applied = SHIELDS.applyTo(ledger, cells);
            assertThat(applied.ledger().strengthFor("cell_b").dueOn()).contains(d("2025-03-02"));
        }

        @Test
        void leavesASuspendedCellAlone() {
            MasteryLedger suspended = MasteryLedger.of("lrn_one", List.of(inside.suspend()));
            assertThat(SHIELDS.applyTo(suspended, cells).outcomes()).isEmpty();
        }

        @Test
        void leavesACellNobodyHasAnsweredAlone() {
            assertThat(SHIELDS.applyTo(MasteryLedger.of("lrn_one", List.of()), cells)
                            .outcomes())
                    .isEmpty();
        }

        @Test
        void spreadsTheReviewsAShieldGatheredOverTheOpenDaysAfterIt() {
            List<CellStrength> week = List.of(
                    CellStrength.fresh("cell_m").recordCorrect(FLAT, d("2025-03-10")),
                    CellStrength.fresh("cell_n").recordCorrect(FLAT, d("2025-03-11")),
                    CellStrength.fresh("cell_o").recordCorrect(FLAT, d("2025-03-11")),
                    CellStrength.fresh("cell_p").recordCorrect(FLAT, d("2025-03-14")),
                    before);
            MasteryLedger away = MasteryLedger.of("lrn_one", week);
            ShieldSet.Applied applied =
                    SHIELDS.applyTo(away, List.of("cell_p", "cell_o", "cell_n", "cell_m", "cell_b"), 2);

            assertThat(applied.outcomes())
                    .extracting(ShieldOutcome::cellId, ShieldOutcome::to)
                    .containsExactly(
                            tuple("cell_p", d("2025-03-18")),
                            tuple("cell_o", d("2025-03-18")),
                            tuple("cell_n", d("2025-03-17")),
                            tuple("cell_m", d("2025-03-17")));
            assertThat(applied.ledger().strengthFor("cell_b").dueOn()).contains(d("2025-03-02"));
        }

        @Test
        void rollsAFullDayOnPastTheNextShieldRatherThanIntoIt() {
            ShieldSet backToBack = ShieldSet.of(List.of(
                    ShieldInput.of(r("2025-03-10", "2025-03-16")), ShieldInput.of(r("2025-03-18", "2025-03-20"))));
            MasteryLedger away = MasteryLedger.of(
                    "lrn_one",
                    List.of(
                            CellStrength.fresh("cell_m").recordCorrect(FLAT, d("2025-03-10")),
                            CellStrength.fresh("cell_n").recordCorrect(FLAT, d("2025-03-11"))));
            ShieldSet.Applied applied = backToBack.applyTo(away, List.of("cell_m", "cell_n"), 1);
            assertThat(applied.ledger().strengthFor("cell_m").dueOn()).contains(d("2025-03-17"));
            assertThat(applied.ledger().strengthFor("cell_n").dueOn()).contains(d("2025-03-21"));
        }

        @Test
        void refusesARoomOfNothingADay() {
            assertThatThrownBy(() -> SHIELDS.applyTo(ledger, cells, 0))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("perDay");
        }

        @Test
        void changesNothingWhenThereAreNoShields() {
            ShieldSet.Applied applied = ShieldSet.NONE.applyTo(ledger, cells);
            assertThat(applied.ledger()).isEqualTo(ledger);
        }
    }

    @Nested
    class StreakThrough {

        private final Set<LocalDate> answered =
                Set.of(d("2025-03-08"), d("2025-03-09"), d("2025-03-17"), d("2025-03-18"), d("2025-03-19"));

        @Test
        void countsAPlainRunOfAnsweredDays() {
            assertThat(ShieldSet.NONE.streakThrough(Set.of(d("2025-03-19"), d("2025-03-18")), d("2025-03-19")))
                    .isEqualTo(2);
        }

        @Test
        void stepsOverAShieldedStretch() {
            assertThat(SHIELDS.streakThrough(answered, d("2025-03-19"))).isEqualTo(5);
        }

        @Test
        void breaksOnAnOrdinaryMissedDay() {
            assertThat(ShieldSet.NONE.streakThrough(answered, d("2025-03-19"))).isEqualTo(3);
        }

        @Test
        void isNothingWhenTheLastDayWasMissed() {
            assertThat(SHIELDS.streakThrough(answered, d("2025-03-20"))).isEqualTo(0);
        }

        @Test
        void stopsAtTheLimitRatherThanWalkingForever() {
            Set<LocalDate> always = new AbstractSet<>() {
                @Override
                public boolean contains(Object day) {
                    return true;
                }

                @Override
                public Iterator<LocalDate> iterator() {
                    return Collections.emptyIterator();
                }

                @Override
                public int size() {
                    return 0;
                }
            };
            assertThat(ShieldSet.NONE.streakThrough(always, d("2025-03-19"), 5)).isEqualTo(5);
        }
    }

    @Nested
    class Describe {

        @Test
        void saysSoWhenThereAreNone() {
            assertThat(ShieldSet.NONE.describe()).isEqualTo("no shielded days");
        }

        @Test
        void readsEachWindowAndItsReasonOut() {
            assertThat(SHIELDS.describe())
                    .isEqualTo("2025-03-10 to 2025-03-16 for reading week, "
                            + "2025-04-02 to 2025-04-04 for field trip to the epigraphy museum");
        }
    }
}
