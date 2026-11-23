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
import io.github.rkaushikdev.stemwright.domain.course.Assignment;
import io.github.rkaushikdev.stemwright.domain.course.AssignmentInput;
import io.github.rkaushikdev.stemwright.domain.course.AssignmentProgress;
import io.github.rkaushikdev.stemwright.domain.course.Course;
import io.github.rkaushikdev.stemwright.domain.course.CourseInput;
import io.github.rkaushikdev.stemwright.domain.course.CourseScope;
import io.github.rkaushikdev.stemwright.domain.course.Enrolment;
import io.github.rkaushikdev.stemwright.domain.course.EnrolmentInput;
import io.github.rkaushikdev.stemwright.domain.course.EnrolmentRole;
import io.github.rkaushikdev.stemwright.domain.drill.Deck;
import io.github.rkaushikdev.stemwright.domain.drill.DeckInput;
import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicyPatch;
import io.github.rkaushikdev.stemwright.domain.errors.ForbiddenException;
import io.github.rkaushikdev.stemwright.domain.errors.NotFoundException;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LadderInput;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.mastery.ShieldInput;
import io.github.rkaushikdev.stemwright.domain.mastery.ShieldSet;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import io.github.rkaushikdev.stemwright.support.App.Harness;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CourseServiceTest {

    private static final LocalDate DAY = d("2024-10-21");

    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3).spread(0).build());

    private static final Actor TEACHER = new Actor("lrn_teacher", AccountRole.INSTRUCTOR);
    private static final Actor STUDENT = new Actor("lrn_one", AccountRole.LEARNER);
    private static final Actor OTHER = new Actor("lrn_two", AccountRole.LEARNER);

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    /** The seeded stores with the decks {@code all} and {@code core} and the Michaelmas course. */
    private static CourseService build() {
        return build(seededStores());
    }

    private static CourseService build(Stores stores) {
        stores.decks().put(Deck.of(LATIN_NOUN, DeckInput.of("all")));
        stores.decks().put(Deck.of(LATIN_NOUN, DeckInput.of("core")));
        stores.courses().put(MICHAELMAS);
        Harness harness = testHarness();
        DrillService drill = new DrillService(stores, harness.bus(), harness.clock());
        return new CourseService(stores, harness.bus(), harness.clock(), drill);
    }

    /** Week three on the deck: its singulars, five cells to master by the twenty seventh. */
    private static AssignmentInput weekOn(String deckKey) {
        return AssignmentInput.builder("week-3", deckKey, d("2024-10-21"), d("2024-10-27"))
                .narrowing("number=sg")
                .masteredCells(5)
                .build();
    }

    private static final AssignmentInput WEEK_INPUT = weekOn("all");

    @Nested
    class GetListAndScopeOf {

        @Test
        void readsACourseBack() {
            CourseService service = build();
            assertThat(service.get("latin-1a").title()).isEqualTo("Latin 1A");
            assertThat(service.list()).hasSize(1);
        }

        @Test
        void refusesACourseNobodyStored() {
            CourseService service = build();
            assertThatThrownBy(() -> service.get("nowhere")).isInstanceOf(NotFoundException.class);
        }

        @Test
        void buildsAScopeWithTheRosterInIt() {
            CourseService service = build();
            service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_one"));
            CourseScope scope = service.scopeOf("latin-1a");
            assertThat(scope.roster().enrolments()).hasSize(1);
        }
    }

    @Nested
    class Create {

        @Test
        void storesACourseTheActorTeaches() {
            CourseService service = build();
            Course created = service.create(
                    TEACHER,
                    CourseInput.builder("latin-1b", "latin", d("2025-01-06"), d("2025-03-14"))
                            .instructorIds("lrn_teacher")
                            .build());
            assertThat(created.key()).isEqualTo("latin-1b");
            assertThat(service.list()).hasSize(2);
        }

        @Test
        void refusesACourseTheActorDoesNotTeach() {
            CourseService service = build();
            assertThatThrownBy(() -> service.create(
                            TEACHER,
                            CourseInput.builder("greek-1", "greek", d("2025-01-06"), d("2025-03-14"))
                                    .instructorIds("lrn_someone")
                                    .build()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    class EnrolAndWithdraw {

        @Test
        void enrolsALearner() {
            CourseService service = build();
            Enrolment enrolment = service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_one"));
            assertThat(enrolment.window()).isEqualTo(MICHAELMAS.term());
        }

        @Test
        void pushesTheReviewsDueInsideAShieldOutPastItAtTheDailyCeiling() {
            Stores stores = seededStores();
            List<CellStrength> due = new ArrayList<>();
            for (String cellId : List.of("cell_a", "cell_b", "cell_c")) {
                due.add(CellStrength.fresh(cellId).recordCorrect(FLAT, d("2024-11-04")));
            }
            stores.ledgers().put(MasteryLedger.of("lrn_one", due));
            EnrolmentInput shielded = EnrolmentInput.builder("lrn_one")
                    .shields(ShieldSet.of(
                            List.of(ShieldInput.of(DayRange.of(d("2024-11-05"), d("2024-11-08")), "reading week"))))
                    .policyPatch(DrillPolicyPatch.builder().reviewsPerDay(2).build())
                    .build();

            build(stores).enrol(TEACHER, "latin-1a", shielded);

            MasteryLedger after = stores.ledgers().get("lrn_one").orElseThrow();
            assertThat(after.strengthFor("cell_a").dueOn()).contains(d("2024-11-09"));
            assertThat(after.strengthFor("cell_b").dueOn()).contains(d("2024-11-09"));
            assertThat(after.strengthFor("cell_c").dueOn()).contains(d("2024-11-10"));
        }

        @Test
        void leavesTheLedgerAloneForAnEnrolmentWithNoShields() {
            Stores stores = seededStores();
            MasteryLedger before = MasteryLedger.of(
                    "lrn_one", List.of(CellStrength.fresh("cell_a").recordCorrect(FLAT, d("2024-11-04"))));
            stores.ledgers().put(before);
            build(stores).enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_one"));
            assertThat(stores.ledgers().get("lrn_one")).contains(before);
        }

        @Test
        void refusesALearnerEnrollingThemselves() {
            CourseService service = build();
            assertThatThrownBy(() -> service.enrol(STUDENT, "latin-1a", EnrolmentInput.of("lrn_one")))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void withdrawsSomebodyWhoIsEnrolled() {
            CourseService service = build();
            service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_one"));
            assertThat(service.withdrawLearner(TEACHER, "latin-1a", "lrn_one").withdrawn())
                    .isTrue();
        }

        @Test
        void refusesToWithdrawSomebodyWhoWasNeverEnrolled() {
            CourseService service = build();
            assertThatThrownBy(() -> service.withdrawLearner(TEACHER, "latin-1a", "lrn_nine"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Assignments {

        @Test
        void addsOneAndReadsItBack() {
            CourseService service = build();
            Assignment added = service.addAssignment(TEACHER, "latin-1a", WEEK_INPUT);
            assertThat(added.key()).isEqualTo("week-3");
            assertThat(service.assignments("latin-1a")).hasSize(1);
        }

        @Test
        void refusesALearner() {
            CourseService service = build();
            assertThatThrownBy(() -> service.addAssignment(STUDENT, "latin-1a", WEEK_INPUT))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void refusesADeckTheCourseDoesNotTeach() {
            CourseService service = build();
            AssignmentInput elsewhere = weekOn("nowhere");
            assertThatThrownBy(() -> service.addAssignment(TEACHER, "latin-1a", elsewhere))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class ProgressFor {

        @Test
        void reportsALearnerTheirOwnProgress() {
            CourseService service = build();
            service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_one"));
            service.addAssignment(TEACHER, "latin-1a", WEEK_INPUT);
            AssignmentProgress progress = service.progressFor(STUDENT, "latin-1a", "week-3", "lrn_one", DAY, FLAT);
            assertThat(progress.cells()).isEqualTo(15);
            assertThat(progress.mastered()).isZero();
        }

        @Test
        void refusesALearnerReadingSomebodyElse() {
            CourseService service = build();
            service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_one"));
            service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_two"));
            service.addAssignment(TEACHER, "latin-1a", WEEK_INPUT);
            assertThatThrownBy(() -> service.progressFor(OTHER, "latin-1a", "week-3", "lrn_one", DAY, FLAT))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void refusesAnAssignmentNobodySet() {
            CourseService service = build();
            service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_one"));
            assertThatThrownBy(() -> service.progressFor(TEACHER, "latin-1a", "week-9", "lrn_one", DAY, FLAT))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void refusesALearnerWhoIsNotOnTheRegister() {
            CourseService service = build();
            service.addAssignment(TEACHER, "latin-1a", WEEK_INPUT);
            assertThatThrownBy(() -> service.progressFor(TEACHER, "latin-1a", "week-3", "lrn_nine", DAY, FLAT))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class CohortProgress {

        @Test
        void reportsARowForEveryGradedLearner() {
            CourseService service = build();
            service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_one"));
            service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_two"));
            service.enrol(
                    TEACHER,
                    "latin-1a",
                    EnrolmentInput.builder("lrn_three")
                            .role(EnrolmentRole.AUDITOR)
                            .build());
            service.addAssignment(TEACHER, "latin-1a", WEEK_INPUT);
            List<AssignmentProgress> rows = service.cohortProgress(TEACHER, "latin-1a", "week-3", DAY, FLAT);
            assertThat(rows).extracting(AssignmentProgress::learnerId).containsExactly("lrn_one", "lrn_two");
        }

        @Test
        void refusesALearner() {
            CourseService service = build();
            service.enrol(TEACHER, "latin-1a", EnrolmentInput.of("lrn_one"));
            service.addAssignment(TEACHER, "latin-1a", WEEK_INPUT);
            assertThatThrownBy(() -> service.cohortProgress(STUDENT, "latin-1a", "week-3", DAY, FLAT))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void reportsNothingForACourseWithNobodyOnIt() {
            CourseService service = build();
            service.addAssignment(TEACHER, "latin-1a", WEEK_INPUT);
            assertThat(service.cohortProgress(TEACHER, "latin-1a", "week-3", DAY, FLAT))
                    .isEmpty();
        }
    }
}
