package io.github.rkaushikdev.stemwright.domain.report;

import static io.github.rkaushikdev.stemwright.support.Drill.DRILL_CONTEXT;
import static io.github.rkaushikdev.stemwright.support.Drill.PUELLA;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.stemwright.domain.drill.Deck;
import io.github.rkaushikdev.stemwright.domain.drill.DeckInput;
import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicy;
import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicyPatch;
import io.github.rkaushikdev.stemwright.domain.drill.Membership;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LadderInput;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ForecastTest {

    private static final LocalDate TERM = d("2024-10-01");
    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3, 7, 16).spread(0).build());

    private static final Membership MEMBERSHIP =
            Membership.resolve(DRILL_CONTEXT, Deck.of(LATIN_NOUN, DeckInput.of("all")), TERM);
    private static final List<Cell> CELLS = MEMBERSHIP.cells();

    private static final MasteryLedger LEDGER = MasteryLedger.of(
            "lrn_one",
            List.of(
                    seenOn(cellOf("case=nom;number=sg").id(), "2024-10-01"),
                    seenOn(cellOf("case=gen;number=sg").id(), "2024-10-01"),
                    seenOn(cellOf("case=dat;number=sg").id(), "2024-10-04")));

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static DayRange r(String from, String to) {
        return DayRange.of(d(from), d(to));
    }

    private static Cell cellOf(String bundle) {
        return CELLS.stream()
                .filter(cell ->
                        cell.lexemeId().equals(PUELLA.id()) && cell.bundle().equals(bundle))
                .findFirst()
                .orElseThrow();
    }

    private static CellStrength seenOn(String cellId, String day) {
        return CellStrength.fresh(cellId).recordCorrect(FLAT, d(day));
    }

    private static DrillPolicy policy(DrillPolicyPatch.Builder patch) {
        return DrillPolicy.of(patch.build());
    }

    private static DrillPolicyPatch.Builder patch() {
        return DrillPolicyPatch.builder();
    }

    private static MasteryLedger empty() {
        return MasteryLedger.empty("lrn_one");
    }

    private static List<ForecastDay> weekOf(DrillPolicy policy) {
        return Forecast.daily(CELLS, LEDGER, r("2024-10-02", "2024-10-06"), policy);
    }

    @Nested
    class Daily {

        @Test
        void countsTheReviewsFallingOnEachDay() {
            List<ForecastDay> days = weekOf(DrillPolicy.DEFAULT);
            assertThat(days.stream().map(ForecastDay::due)).containsExactly(2, 0, 0, 1, 0);
        }

        @Test
        void coversEveryDayOfTheRange() {
            List<ForecastDay> days = weekOf(DrillPolicy.DEFAULT);
            assertThat(days).hasSize(5);
            assertThat(days.getFirst().on()).isEqualTo(d("2024-10-02"));
        }

        @Test
        void foldsAnythingAlreadyOverdueOntoTheFirstDay() {
            List<ForecastDay> days = Forecast.daily(CELLS, LEDGER, r("2024-10-10", "2024-10-12"), DrillPolicy.DEFAULT);
            assertThat(days.getFirst().due()).isEqualTo(3);
        }

        @Test
        void ignoresSuspendedCells() {
            String target = cellOf("case=nom;number=sg").id();
            MasteryLedger suspended = MasteryLedger.of(
                    "lrn_one", List.of(seenOn(target, "2024-10-01").suspend()));
            List<ForecastDay> days =
                    Forecast.daily(CELLS, suspended, r("2024-10-01", "2024-10-05"), DrillPolicy.DEFAULT);
            assertThat(Forecast.totalDue(days)).isEqualTo(0);
        }

        @Test
        void ignoresCellsNobodyHasAnswered() {
            List<ForecastDay> days = Forecast.daily(CELLS, empty(), r("2024-10-01", "2024-10-05"), DrillPolicy.DEFAULT);
            assertThat(Forecast.totalDue(days)).isEqualTo(0);
        }

        @Test
        void marksADayOverTheReviewCeiling() {
            List<ForecastDay> days = weekOf(policy(patch().reviewsPerDay(1)));
            assertThat(Forecast.daysOverCeiling(days).stream().map(ForecastDay::on))
                    .containsExactly(d("2024-10-02"));
        }

        @Test
        void marksARestDayAndCountsWhatFallsOnIt() {
            List<ForecastDay> days = weekOf(policy(patch().restDays(List.of(3))));
            assertThat(days.getFirst().resting()).isTrue();
            assertThat(Forecast.spilledOnRestDays(days)).isEqualTo(2);
            assertThat(days.getFirst().asked()).isZero();
            assertThat(days.getFirst().overCeiling()).isFalse();
        }

        @Test
        void carriesWhatARestDayCouldNotAskOntoTheNextDrillingDay() {
            List<ForecastDay> days = weekOf(policy(patch().restDays(List.of(3, 4))));
            assertThat(days.stream().map(ForecastDay::carried)).containsExactly(0, 2, 2, 0, 0);
            assertThat(days.stream().map(ForecastDay::asked)).containsExactly(0, 0, 2, 1, 0);
        }

        @Test
        void carriesWhatIsPastTheCeilingAndCountsItAgainstTheNextDay() {
            List<ForecastDay> days = weekOf(policy(patch().reviewsPerDay(1)));
            assertThat(days.stream().map(ForecastDay::asked)).containsExactly(1, 1, 0, 1, 0);
            assertThat(days.stream().map(ForecastDay::carried)).containsExactly(0, 1, 0, 0, 0);
            assertThat(days.get(1).overCeiling()).isFalse();
        }

        @Test
        void callsADayOverTheCeilingOnceTheCarriedReviewsPushItThere() {
            List<ForecastDay> days = Forecast.daily(
                    CELLS,
                    LEDGER,
                    r("2024-10-01", "2024-10-06"),
                    policy(patch().reviewsPerDay(1).restDays(List.of(3))));
            assertThat(days.stream().map(ForecastDay::due)).containsExactly(0, 2, 0, 0, 1, 0);
            assertThat(Forecast.daysOverCeiling(days).stream().map(ForecastDay::on))
                    .containsExactly(d("2024-10-03"));
            assertThat(days.get(3).asked()).isEqualTo(1);
            assertThat(days.get(4).carried()).isZero();
        }
    }

    @Nested
    class BusiestDay {

        @Test
        void namesTheDayWithTheMostDue() {
            assertThat(Forecast.busiestDay(weekOf(DrillPolicy.DEFAULT)).map(ForecastDay::on))
                    .contains(d("2024-10-02"));
        }

        @Test
        void countsTheReviewsCarriedOntoADay() {
            List<ForecastDay> days = weekOf(policy(patch().restDays(List.of(3, 4))));
            assertThat(Forecast.busiestDay(days).map(ForecastDay::on)).contains(d("2024-10-02"));
            List<ForecastDay> capped = weekOf(policy(patch().reviewsPerDay(1).restDays(List.of(3, 4, 5))));
            assertThat(Forecast.busiestDay(capped).map(ForecastDay::on)).contains(d("2024-10-05"));
        }

        @Test
        void givesNothingForAnEmptyForecast() {
            assertThat(Forecast.busiestDay(List.of())).isEmpty();
        }
    }

    @Nested
    class OptimisticProjection {

        @Test
        void introducesNewCellsAtTheBudgetUntilTheyRunOut() {
            DrillPolicy policy = policy(patch().newPerDay(4).reviewsPerDay(100));
            List<ProjectionDay> days = Forecast.optimisticProjection(
                    CELLS.subList(0, 6), empty(), FLAT, policy, r("2024-10-01", "2024-10-04"));
            assertThat(days.stream().map(ProjectionDay::introductions)).containsExactly(4, 2, 0, 0);
        }

        @Test
        void bringsEachIntroductionBackAlongTheLadder() {
            DrillPolicy policy = policy(patch().newPerDay(1).reviewsPerDay(100));
            List<ProjectionDay> days = Forecast.optimisticProjection(
                    CELLS.subList(0, 1), empty(), FLAT, policy, r("2024-10-01", "2024-10-07"));
            assertThat(days.stream().map(ProjectionDay::reviews)).containsExactly(0, 1, 0, 0, 1, 0, 0);
        }

        @Test
        void doesNothingAtAllOnARestDay() {
            DrillPolicy policy = policy(patch().newPerDay(2).restDays(List.of(3)));
            List<ProjectionDay> days = Forecast.optimisticProjection(
                    CELLS.subList(0, 4), empty(), FLAT, policy, r("2024-10-01", "2024-10-03"));
            assertThat(days.get(1)).isEqualTo(new ProjectionDay(d("2024-10-02"), 0, 0));
            assertThat(days.get(0).introductions()).isEqualTo(2);
            assertThat(days.get(2).introductions()).isEqualTo(2);
        }

        @Test
        void skipsASuspendedCellEntirely() {
            List<CellStrength> strengths = CELLS.subList(0, 3).stream()
                    .map(cell -> CellStrength.fresh(cell.id()).suspend())
                    .toList();
            MasteryLedger suspended = MasteryLedger.of("lrn_one", strengths);
            List<ProjectionDay> days = Forecast.optimisticProjection(
                    CELLS.subList(0, 3), suspended, FLAT, DrillPolicy.DEFAULT, r("2024-10-01", "2024-10-03"));
            assertThat(days.stream().allMatch(day -> day.introductions() == 0 && day.reviews() == 0))
                    .isTrue();
        }

        @Test
        void carriesAnExistingDueDateIntoTheProjection() {
            MasteryLedger one = MasteryLedger.of(
                    "lrn_one", List.of(seenOn(cellOf("case=nom;number=sg").id(), "2024-10-01")));
            List<ProjectionDay> days = Forecast.optimisticProjection(
                    List.of(cellOf("case=nom;number=sg")),
                    one,
                    FLAT,
                    policy(patch().newPerDay(0)),
                    r("2024-10-01", "2024-10-03"));
            assertThat(days.stream().map(ProjectionDay::reviews)).containsExactly(0, 1, 0);
        }

        @Test
        void climbsACellAlreadyOverdueByTheGapItWasHeldOver() {
            Cell nominative = cellOf("case=nom;number=sg");
            CellStrength held = CellStrength.fresh(nominative.id())
                    .recordCorrect(FLAT, d("2024-09-01"))
                    .recordCorrect(FLAT, d("2024-09-02"));
            List<ProjectionDay> days = Forecast.optimisticProjection(
                    List.of(nominative),
                    MasteryLedger.of("lrn_one", List.of(held)),
                    FLAT,
                    policy(patch().newPerDay(0)),
                    r("2024-10-01", "2024-10-25"));
            List<Integer> reviewed = new ArrayList<>();
            for (int index = 0; index < days.size(); index += 1) {
                if (days.get(index).reviews() > 0) {
                    reviewed.add(index);
                }
            }
            assertThat(reviewed).containsExactly(0, 16);
        }
    }

    @Nested
    class DaysToClearBacklog {

        @Test
        void findsTheFirstQuietDay() {
            DrillPolicy policy = policy(patch().newPerDay(2).reviewsPerDay(100));
            List<ProjectionDay> days = Forecast.optimisticProjection(
                    CELLS.subList(0, 2), empty(), FLAT, policy, r("2024-10-01", "2024-10-05"));
            assertThat(Forecast.daysToClearBacklog(days)).isEqualTo(2);
        }

        @Test
        void givesTheWholeSpanWhenNoDayIsQuiet() {
            List<ProjectionDay> days = Forecast.optimisticProjection(
                    CELLS, empty(), FLAT, policy(patch().newPerDay(1)), r("2024-10-01", "2024-10-03"));
            assertThat(Forecast.daysToClearBacklog(days)).isEqualTo(3);
        }
    }
}
