package io.github.rkaushikdev.stemwright.domain.mastery;

import static io.github.rkaushikdev.stemwright.support.Problems.problemMessages;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.byLessThan;

import io.github.rkaushikdev.stemwright.domain.errors.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LadderTest {

    private static final Ladder STANDARD = Ladder.STANDARD;

    @Nested
    class Of {

        @Test
        void keepsTheRungsItWasGiven() {
            assertThat(STANDARD.rungCount()).isEqualTo(8);
            assertThat(STANDARD.topRung()).isEqualTo(7);
        }

        @Test
        void defaultsTheLabelTheLapseDropAndTheSpread() {
            Ladder built = Ladder.of(LadderInput.of("x", 1, 2));
            assertThat(built.label()).isEqualTo("x");
            assertThat(built.lapseDrop()).isEqualTo(2);
            assertThat(built.spread()).isCloseTo(0.1, byLessThan(0.005));
        }

        @Test
        void refusesAnEmptyLadder() {
            assertThat(problemMessages(() -> Ladder.of(LadderInput.of("x"))))
                    .containsExactly("rungs must hold at least one rung");
        }

        @Test
        void refusesRungsThatDoNotIncrease() {
            assertThat(problemMessages(() -> Ladder.of(LadderInput.of("x", 3, 3))))
                    .containsExactly("rungs.1 must be longer than the rung before it: 3");
            assertThat(problemMessages(() -> Ladder.of(LadderInput.of("x", 3, 2))))
                    .hasSize(1);
        }

        /**
         * A rung of a day and a half cannot be written, because a rung is an {@code int} and the
         * type refuses it; the second case is a rung below a day that also follows a longer rung,
         * which must be reported once, as below a day, and not again as shorter than the rung
         * before.
         */
        @Test
        void refusesARungBelowADayOrAFractionalOne() {
            assertThat(problemMessages(() -> Ladder.of(LadderInput.of("x", 0))))
                    .containsExactly("rungs.0 must be a whole number of days above zero: 0");
            assertThat(problemMessages(() -> Ladder.of(LadderInput.of("x", 3, -1))))
                    .containsExactly("rungs.1 must be a whole number of days above zero: -1");
        }

        @Test
        void refusesALapseDropBelowOne() {
            assertThat(problemMessages(() -> Ladder.of(
                            LadderInput.builder("x", 1, 2).lapseDrop(0).build())))
                    .containsExactly("lapseDrop must be a whole number above zero: 0");
        }

        @Test
        void refusesASpreadOutsideNoughtToOneHalf() {
            assertThat(problemMessages(() ->
                            Ladder.of(LadderInput.builder("x", 1, 2).spread(0.9).build())))
                    .containsExactly("spread must be between zero and one half: 0.9");
            assertThat(problemMessages(() -> Ladder.of(
                            LadderInput.builder("x", 1, 2).spread(-0.1).build())))
                    .hasSize(1);
            assertThat(problemMessages(() -> Ladder.of(
                            LadderInput.builder("x", 1, 2).spread(Double.NaN).build())))
                    .containsExactly("spread must be between zero and one half: NaN");
        }

        @Test
        void acceptsASpreadOfExactlyNoughtOrOneHalf() {
            assertThat(Ladder.of(LadderInput.builder("x", 1, 2).spread(0).build())
                            .spread())
                    .isEqualTo(0.0);
            assertThat(Ladder.of(LadderInput.builder("x", 1, 2).spread(0.5).build())
                            .spread())
                    .isEqualTo(0.5);
        }
    }

    @Nested
    class ClampRung {

        @Test
        void holdsARungInsideTheLadder() {
            assertThat(STANDARD.clampRung(3)).isEqualTo(3);
        }

        @Test
        void clampsBelowAndAbove() {
            assertThat(STANDARD.clampRung(-5)).isEqualTo(0);
            assertThat(STANDARD.clampRung(99)).isEqualTo(7);
        }

        /**
         * A fractional rung or a NaN cannot be an {@code int}; the rung an {@code int} can break is
         * one at the edge of its range, where climbing or lapsing would wrap round to the opposite
         * end of the ladder.
         */
        @Test
        void rescuesABrokenRung() {
            assertThat(STANDARD.clampRung(Integer.MIN_VALUE)).isEqualTo(0);
            assertThat(STANDARD.climb(Integer.MAX_VALUE)).isEqualTo(7);
            assertThat(STANDARD.lapse(Integer.MIN_VALUE)).isEqualTo(0);
        }
    }

    @Nested
    class IntervalAt {

        @Test
        void readsTheDaysOffTheRung() {
            assertThat(STANDARD.intervalAt(0)).isEqualTo(1);
            assertThat(STANDARD.intervalAt(3)).isEqualTo(16);
        }

        @Test
        void clampsRatherThanFallingOffEitherEnd() {
            assertThat(STANDARD.intervalAt(-1)).isEqualTo(1);
            assertThat(STANDARD.intervalAt(99)).isEqualTo(340);
        }
    }

    @Nested
    class ByKey {

        @Test
        void findsEachBuiltInLadderByItsKey() {
            assertThat(Ladder.of("standard")).isSameAs(Ladder.STANDARD);
            assertThat(Ladder.of("slow-start")).isSameAs(Ladder.SLOW);
        }

        @Test
        void refusesAKeyNoBuiltInLadderHas() {
            assertThatThrownBy(() -> Ladder.of("glacial"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("glacial");
        }
    }

    @Nested
    class ClimbAndLapse {

        @Test
        void climbsOneRungAtATime() {
            assertThat(STANDARD.climb(0)).isEqualTo(1);
            assertThat(STANDARD.climb(3)).isEqualTo(4);
        }

        @Test
        void findsTheHighestRungAGapCovers() {
            assertThat(STANDARD.rungHeldFor(0)).isZero();
            assertThat(STANDARD.rungHeldFor(2)).isZero();
            assertThat(STANDARD.rungHeldFor(3)).isEqualTo(1);
            assertThat(STANDARD.rungHeldFor(34)).isEqualTo(3);
            assertThat(STANDARD.rungHeldFor(35)).isEqualTo(4);
            assertThat(STANDARD.rungHeldFor(10_000)).isEqualTo(STANDARD.topRung());
        }

        @Test
        void stopsClimbingAtTheTop() {
            assertThat(STANDARD.climb(7)).isEqualTo(7);
            assertThat(STANDARD.isAtTop(7)).isTrue();
            assertThat(STANDARD.isAtTop(6)).isFalse();
        }

        @Test
        void dropsByTheConfiguredNumberOfRungs() {
            assertThat(STANDARD.lapse(5)).isEqualTo(3);
            assertThat(Ladder.SLOW.lapse(5)).isEqualTo(4);
        }

        @Test
        void neverDropsBelowTheBottom() {
            assertThat(STANDARD.lapse(1)).isEqualTo(0);
            assertThat(STANDARD.lapse(0)).isEqualTo(0);
        }

        @Test
        void doesNotSendALongKnownCellBackToTheFirstRung() {
            assertThat(STANDARD.intervalAt(STANDARD.lapse(7))).isEqualTo(75);
        }
    }

    @Nested
    class SpreadInterval {

        @Test
        void isThePlainIntervalWhenTheLadderDoesNotSpread() {
            Ladder flat =
                    Ladder.of(LadderInput.builder("flat", 1, 3, 7).spread(0).build());
            assertThat(flat.spreadInterval(2, "cell_a")).isEqualTo(7);
        }

        @Test
        void leavesAOneDayIntervalAlone() {
            assertThat(STANDARD.spreadInterval(0, "cell_a")).isEqualTo(1);
        }

        @Test
        void isTheSameForTheSameSeed() {
            assertThat(STANDARD.spreadInterval(5, "cell_a")).isEqualTo(STANDARD.spreadInterval(5, "cell_a"));
        }

        @Test
        void separatesTwoCellsAtTheSameRung() {
            Set<Integer> spread = List.of("a", "b", "c", "d", "e", "f").stream()
                    .map(seed -> STANDARD.spreadInterval(6, seed))
                    .collect(Collectors.toSet());
            assertThat(spread.size()).isGreaterThan(1);
        }

        @Test
        void staysInsideTheConfiguredSwing() {
            for (String seed : List.of("a", "b", "c", "d", "e", "f", "g", "h")) {
                int value = STANDARD.spreadInterval(6, seed);
                assertThat(value).isGreaterThanOrEqualTo(160 - 16);
                assertThat(value).isLessThanOrEqualTo(160 + 16);
            }
        }

        @Test
        void neverReturnsLessThanADay() {
            Ladder wide =
                    Ladder.of(LadderInput.builder("wide", 1, 2).spread(0.5).build());
            for (String seed : List.of("a", "b", "c", "d")) {
                assertThat(wide.spreadInterval(1, seed)).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Nested
    class Describe {

        @Test
        void showsTheFirstRungsAndTheLastOne() {
            assertThat(STANDARD.describe())
                    .isEqualTo("Standard waits 1, 3, 7, 16, up to 340 days and drops 2 rungs on a lapse");
        }

        @Test
        void showsAShortLadderInFull() {
            assertThat(Ladder.of(LadderInput.of("short", 1, 4)).describe())
                    .isEqualTo("short waits 1, 4 days and drops 2 rungs on a lapse");
        }
    }
}
