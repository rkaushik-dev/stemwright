package io.github.rkaushikdev.stemwright.app.services;

import static io.github.rkaushikdev.stemwright.support.App.seededStores;
import static io.github.rkaushikdev.stemwright.support.App.testHarness;
import static io.github.rkaushikdev.stemwright.support.Course.MICHAELMAS;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.course.AccountRole;
import io.github.rkaushikdev.stemwright.domain.course.Actor;
import io.github.rkaushikdev.stemwright.domain.course.AssignmentProgress;
import io.github.rkaushikdev.stemwright.domain.course.CourseScope;
import io.github.rkaushikdev.stemwright.domain.course.Enrolment;
import io.github.rkaushikdev.stemwright.domain.course.EnrolmentInput;
import io.github.rkaushikdev.stemwright.domain.course.Roster;
import io.github.rkaushikdev.stemwright.domain.drill.Deck;
import io.github.rkaushikdev.stemwright.domain.drill.DeckInput;
import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicyPatch;
import io.github.rkaushikdev.stemwright.domain.errors.ForbiddenException;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LadderInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.report.CellRollup;
import io.github.rkaushikdev.stemwright.domain.report.CohortReport;
import io.github.rkaushikdev.stemwright.domain.report.Forecast;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import io.github.rkaushikdev.stemwright.support.App.Harness;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReportServiceTest {

    private static final LocalDate DAY = d("2024-10-21");

    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3).spread(0).build());

    private static final Actor TEACHER = new Actor("lrn_teacher", AccountRole.INSTRUCTOR);
    private static final Actor STUDENT = new Actor("lrn_one", AccountRole.LEARNER);
    private static final Actor OTHER = new Actor("lrn_two", AccountRole.LEARNER);

    private static final CourseScope SCOPE = new CourseScope(
            MICHAELMAS,
            Roster.of(
                    MICHAELMAS,
                    List.of(
                            Enrolment.of(MICHAELMAS, EnrolmentInput.of("lrn_one")),
                            Enrolment.of(MICHAELMAS, EnrolmentInput.of("lrn_two")))));

    private static final DrillSettings SETTINGS = DrillSettings.builder()
            .policy(DrillPolicyPatch.builder()
                    .newPerDay(3)
                    .perLexemeCap(10)
                    .rowSpacing(0)
                    .build())
            .ladder(FLAT)
            .build();

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    private static DayRange r(String from, String to) {
        return DayRange.of(d(from), d(to));
    }

    /** The seeded stores with a deck that takes the whole language, and the services over them. */
    private record Built(DrillService drill, ReportService service) {}

    private static Built build() {
        Stores stores = seededStores();
        stores.decks().put(Deck.of(LATIN_NOUN, DeckInput.of("all")));
        Harness harness = testHarness();
        DrillService drill = new DrillService(stores, harness.bus(), harness.clock());
        return new Built(drill, new ReportService(stores, harness.clock(), drill));
    }

    /** Answers the first cell of the learner's queue wrong, and says which cell it was. */
    private static Cell drillOnce(DrillService drill) {
        Cell target = drill.queue("lrn_one", "latin", "all", DAY, SETTINGS)
                .entries()
                .get(0)
                .cell();
        drill.answer(
                AnswerRequest.of("lrn_one", "latin", "all", target.id(), "x").withOn(DAY), SETTINGS);
        return target;
    }

    @Nested
    class Learner {

        @Test
        void countsTheWholeDeckAsUntouchedBeforeAnythingIsAnswered() {
            ReportService service = build().service();
            LearnerReport report =
                    service.learner(STUDENT, SCOPE, "lrn_one", "latin", "all", r("2024-10-21", "2024-10-27"), FLAT);
            assertThat(report.counts().known()).isEqualTo(30);
            assertThat(report.counts().untouched()).isEqualTo(30);
            assertThat(report.counts().introduced()).isZero();
        }

        @Test
        void breaksCoverageDownBothWays() {
            ReportService service = build().service();
            LearnerReport report =
                    service.learner(STUDENT, SCOPE, "lrn_one", "latin", "all", r("2024-10-21", "2024-10-27"), FLAT);
            assertThat(report.byBundle()).hasSize(10);
            assertThat(report.byLexeme()).hasSize(3);
            assertThat(report.weakest().size()).isLessThanOrEqualTo(5);
        }

        @Test
        void showsAReviewInTheForecastOnceOneIsScheduled() {
            Built built = build();
            drillOnce(built.drill());
            LearnerReport report = built.service()
                    .learner(STUDENT, SCOPE, "lrn_one", "latin", "all", r("2024-10-21", "2024-10-27"), FLAT);
            assertThat(Forecast.totalDue(report.forecast())).isEqualTo(1);
        }

        @Test
        void letsTheInstructorReadALearner() {
            ReportService service = build().service();
            LearnerReport report =
                    service.learner(TEACHER, SCOPE, "lrn_one", "latin", "all", r("2024-10-21", "2024-10-27"), FLAT);
            assertThat(report.learnerId()).isEqualTo("lrn_one");
        }

        @Test
        void refusesALearnerReadingSomebodyElse() {
            ReportService service = build().service();
            assertThatThrownBy(() -> service.learner(
                            OTHER, SCOPE, "lrn_one", "latin", "all", r("2024-10-21", "2024-10-27"), FLAT))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    class Activity {

        @Test
        void reportsNothingForALearnerWhoHasNotDrilled() {
            ReportService service = build().service();
            ActivityReport report = service.activity(STUDENT, SCOPE, "lrn_one", r("2024-10-21", "2024-10-23"));
            assertThat(report.days()).hasSize(3);
            assertThat(report.days()).allMatch(day -> day.tally().total() == 0);
            assertThat(report.hardest()).isEmpty();
        }

        @Test
        void countsAnAnswerOnTheDayItHappened() {
            Built built = build();
            drillOnce(built.drill());
            ActivityReport report = built.service().activity(STUDENT, SCOPE, "lrn_one", r("2024-10-21", "2024-10-23"));
            assertThat(report.days().get(0).tally().total()).isEqualTo(1);
            assertThat(report.days().get(1).tally().total()).isZero();
        }

        @Test
        void measuresRetentionOverTheReviewsAloneReadingTheLogBeforeTheRange() {
            Built built = build();
            Cell target = drillOnce(built.drill());
            String expected = built.drill()
                    .answer(
                            AnswerRequest.of("lrn_one", "latin", "all", target.id(), "x")
                                    .withOn(DAY),
                            SETTINGS)
                    .review()
                    .expected();
            built.drill()
                    .answer(
                            AnswerRequest.of("lrn_one", "latin", "all", target.id(), expected)
                                    .withOn(DAY.plusDays(1)),
                            SETTINGS);

            ActivityReport within = built.service().activity(STUDENT, SCOPE, "lrn_one", r("2024-10-21", "2024-10-23"));
            ActivityReport after = built.service().activity(STUDENT, SCOPE, "lrn_one", r("2024-10-22", "2024-10-23"));
            ActivityReport before = built.service().activity(STUDENT, SCOPE, "lrn_one", r("2024-10-21", "2024-10-21"));

            assertThat(within.retention()).isEqualTo(1);
            assertThat(within.days().get(1).reviews()).isEqualTo(1);
            assertThat(after.retention()).isEqualTo(1);
            assertThat(after.hardest()).isEmpty();
            assertThat(before.retention()).isZero();
            assertThat(before.days().getFirst().tally().total()).isEqualTo(2);
        }

        @Test
        void namesACellThatWasGotWrong() {
            Built built = build();
            Cell target = drillOnce(built.drill());
            ActivityReport report = built.service().activity(STUDENT, SCOPE, "lrn_one", r("2024-10-21", "2024-10-23"));
            assertThat(report.hardest()).extracting(CellRollup::cellId).contains(target.id());
        }

        @Test
        void refusesALearnerReadingSomebodyElse() {
            ReportService service = build().service();
            assertThatThrownBy(() -> service.activity(OTHER, SCOPE, "lrn_one", r("2024-10-21", "2024-10-23")))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    class Cohort {

        private final List<AssignmentProgress> rows = List.of(
                new AssignmentProgress("week-3", "lrn_one", DAY, 10, 4, 2, false, true, false, false, false),
                new AssignmentProgress("week-3", "lrn_two", DAY, 10, 10, 4, true, true, true, false, false));

        @Test
        void showsTheInstructorEveryRow() {
            ReportService service = build().service();
            CohortReport report = service.cohort(TEACHER, SCOPE, rows, "week-3", DAY);
            assertThat(report.rows()).hasSize(2);
            assertThat(report.hidden()).isZero();
        }

        @Test
        void showsALearnerOnlyTheirOwn() {
            ReportService service = build().service();
            CohortReport report = service.cohort(STUDENT, SCOPE, rows, "week-3", DAY);
            assertThat(report.rows()).hasSize(1);
            assertThat(report.hidden()).isEqualTo(1);
        }

        @Test
        void takesTheDayOffTheClockWhenNoneIsGiven() {
            ReportService service = build().service();
            assertThat(service.cohort(TEACHER, SCOPE, rows, "week-3").on()).isEqualTo(d("2024-10-21"));
        }
    }
}
