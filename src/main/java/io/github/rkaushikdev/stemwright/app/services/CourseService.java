package io.github.rkaushikdev.stemwright.app.services;

import io.github.rkaushikdev.stemwright.app.Clock;
import io.github.rkaushikdev.stemwright.app.DomainEvent;
import io.github.rkaushikdev.stemwright.app.EventBus;
import io.github.rkaushikdev.stemwright.app.LanguageBundle;
import io.github.rkaushikdev.stemwright.app.Loaders;
import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.course.Actor;
import io.github.rkaushikdev.stemwright.domain.course.Assignment;
import io.github.rkaushikdev.stemwright.domain.course.AssignmentInput;
import io.github.rkaushikdev.stemwright.domain.course.AssignmentProgress;
import io.github.rkaushikdev.stemwright.domain.course.Course;
import io.github.rkaushikdev.stemwright.domain.course.CourseInput;
import io.github.rkaushikdev.stemwright.domain.course.CourseScope;
import io.github.rkaushikdev.stemwright.domain.course.Enrolment;
import io.github.rkaushikdev.stemwright.domain.course.EnrolmentInput;
import io.github.rkaushikdev.stemwright.domain.course.Permission;
import io.github.rkaushikdev.stemwright.domain.course.Roster;
import io.github.rkaushikdev.stemwright.domain.drill.Deck;
import io.github.rkaushikdev.stemwright.domain.drill.DrillContext;
import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicy;
import io.github.rkaushikdev.stemwright.domain.errors.ConflictException;
import io.github.rkaushikdev.stemwright.domain.errors.ForbiddenException;
import io.github.rkaushikdev.stemwright.domain.errors.NotFoundException;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.mastery.ShieldSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The course service. Everything it does needs a scope, because every permission it checks is about
 * a relationship to one course rather than about a role, so {@link #scopeOf} is the first call in
 * nearly every method.
 *
 * <p>Every progress method that takes no day reads the clock's day, and every one that takes no
 * ladder measures mastery against {@link Ladder#STANDARD}.
 */
public final class CourseService {

    private final Stores stores;
    private final EventBus bus;
    private final Clock clock;
    private final DrillService drill;

    /**
     * @param drill the drill service the context and deck of an assignment are loaded through
     */
    public CourseService(Stores stores, EventBus bus, Clock clock, DrillService drill) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.drill = Objects.requireNonNull(drill, "drill");
    }

    /**
     * The course with the key and the roster of its stored enrolments, which is what every
     * permission on the course is decided against.
     *
     * @throws NotFoundException of kind {@code course} when the course is not stored
     */
    public CourseScope scopeOf(String courseKey) {
        return Loaders.requireScope(stores, courseKey);
    }

    /**
     * The course with the key.
     *
     * @throws NotFoundException of kind {@code course}, naming the key, when none is stored
     */
    public Course get(String key) {
        return Loaders.requireCourse(stores, key);
    }

    /** Every course, by key as a reader sorts it. */
    public List<Course> list() {
        return stores.courses().list();
    }

    /**
     * Checks a new course and stores it, in place of any course stored under its key, provided the
     * actor is one of the instructors it names. The permission is decided against the course as
     * built and an empty roster, since nobody can be enrolled on a course that does not exist yet.
     *
     * @throws ValidationException when the domain refuses the course, before the actor is checked
     * @throws ForbiddenException when the actor may not edit the course
     */
    public Course create(Actor actor, CourseInput input) {
        Course built = Course.of(input);
        actor.requirePermission(Permission.EDIT_COURSE, new CourseScope(built, new Roster(input.key(), List.of())));
        stores.courses().put(built);
        return built;
    }

    /**
     * Enrols somebody on the course and stores the enrolment, in place of any enrolment they had on
     * it. The reviews of theirs that fall due inside the enrolment's shields are pushed out past
     * them, no more onto one day than the drill policy with the enrolment's patch asks in a day.
     *
     * @throws NotFoundException of kind {@code course} when the course is not stored
     * @throws ForbiddenException when the actor may not edit the course
     * @throws ValidationException when the domain refuses the enrolment
     */
    public Enrolment enrol(Actor actor, String courseKey, EnrolmentInput input) {
        CourseScope scope = scopeOf(courseKey);
        actor.requirePermission(Permission.EDIT_COURSE, scope);
        Enrolment enrolment = Enrolment.of(scope.course(), input);
        stores.courses().putEnrolment(enrolment);
        deferShielded(enrolment);
        return enrolment;
    }

    private void deferShielded(Enrolment enrolment) {
        if (enrolment.shields().windows().isEmpty()) {
            return;
        }
        stores.ledgers().get(enrolment.learnerId()).ifPresent(ledger -> {
            int perDay = DrillPolicy.DEFAULT.applyPatch(enrolment.policyPatch()).reviewsPerDay();
            ShieldSet.Applied applied = enrolment
                    .shields()
                    .applyTo(ledger, List.copyOf(ledger.byCell().keySet()), Math.max(1, perDay));
            if (!applied.outcomes().isEmpty()) {
                stores.ledgers().put(applied.ledger());
            }
        });
    }

    /**
     * Withdraws a learner from the course and stores the withdrawn enrolment.
     *
     * @throws NotFoundException of kind {@code course} when the course is not stored, or of kind
     *     {@code enrolment}, naming {@code courseKey/learnerId}, when the learner was never enrolled
     * @throws ForbiddenException when the actor may not edit the course
     * @throws ConflictException when the learner has already withdrawn
     */
    public Enrolment withdrawLearner(Actor actor, String courseKey, String learnerId) {
        CourseScope scope = scopeOf(courseKey);
        actor.requirePermission(Permission.EDIT_COURSE, scope);
        Enrolment existing = scope.roster()
                .enrolmentOf(learnerId)
                .orElseThrow(() -> new NotFoundException("enrolment", courseKey + "/" + learnerId));
        Enrolment gone = existing.withdraw();
        stores.courses().putEnrolment(gone);
        return gone;
    }

    /**
     * Checks an assignment against the course and its language's schema and stores it, in place of an
     * earlier assignment with its key.
     *
     * @throws NotFoundException of kind {@code course} when the course is not stored, or for the
     *     course's language when it is not configured
     * @throws ForbiddenException when the actor may not edit the course
     * @throws ValidationException when the domain refuses the assignment, as it does one on a deck
     *     the course does not teach
     */
    public Assignment addAssignment(Actor actor, String courseKey, AssignmentInput input) {
        CourseScope scope = scopeOf(courseKey);
        actor.requirePermission(Permission.EDIT_COURSE, scope);
        LanguageBundle bundle = Loaders.requireLanguage(stores, scope.course().language());
        Assignment built = Assignment.of(bundle.schema(), scope.course(), input);
        stores.courses().putAssignment(built);
        return built;
    }

    /**
     * The assignments stored on the course, the most recently stored last. The course itself is not
     * loaded, so a key nobody stored has no assignments rather than failing.
     */
    public List<Assignment> assignments(String courseKey) {
        return stores.courses().assignments(courseKey);
    }

    /** One learner's progress on the assignment today, against the standard ladder. */
    public AssignmentProgress progressFor(Actor actor, String courseKey, String assignmentKey, String learnerId) {
        return progressFor(actor, courseKey, assignmentKey, learnerId, clock.today(), Ladder.STANDARD);
    }

    /** One learner's progress on the assignment on the day, against the standard ladder. */
    public AssignmentProgress progressFor(
            Actor actor, String courseKey, String assignmentKey, String learnerId, LocalDate on) {
        return progressFor(actor, courseKey, assignmentKey, learnerId, on, Ladder.STANDARD);
    }

    /**
     * One learner's progress on the assignment on the day, with mastery measured against the ladder.
     * When the progress meets the assignment's target, that is announced as an assignment met by the
     * actor who asked.
     *
     * @throws NotFoundException of kind {@code course} when the course is not stored, of kind {@code
     *     assignment} naming {@code courseKey/assignmentKey} when the course has no such assignment, of
     *     kind {@code enrolment} naming {@code courseKey/learnerId} when the learner is not on the
     *     roster, or for the language, its profile book or the assignment's deck
     * @throws ForbiddenException when the actor may not read the learner's work on the course
     */
    public AssignmentProgress progressFor(
            Actor actor, String courseKey, String assignmentKey, String learnerId, LocalDate on, Ladder ladder) {
        CourseScope scope = scopeOf(courseKey);
        actor.requireReadLearner(learnerId, scope);
        Assignment entry = loadAssignment(courseKey, assignmentKey);
        Enrolment enrolment = scope.roster()
                .enrolmentOf(learnerId)
                .orElseThrow(() -> new NotFoundException("enrolment", courseKey + "/" + learnerId));
        AssignmentProgress progress = progressOf(scope, entry, enrolment, on, ladder);
        if (progress.met()) {
            bus.publish(new DomainEvent.AssignmentMet(clock.now(), actor.id(), assignmentKey, learnerId, on));
        }
        return progress;
    }

    /** The progress of every graded learner on the assignment today, against the standard ladder. */
    public List<AssignmentProgress> cohortProgress(Actor actor, String courseKey, String assignmentKey) {
        return cohortProgress(actor, courseKey, assignmentKey, clock.today(), Ladder.STANDARD);
    }

    /** The progress of every graded learner on the assignment on the day, against the standard ladder. */
    public List<AssignmentProgress> cohortProgress(Actor actor, String courseKey, String assignmentKey, LocalDate on) {
        return cohortProgress(actor, courseKey, assignmentKey, on, Ladder.STANDARD);
    }

    /**
     * The progress of every graded learner on the assignment on the day, one row each in learner id
     * order, with mastery measured against the ladder. Auditors, assistants and withdrawn learners
     * have no row. Unlike {@link #progressFor}, this announces nothing, whichever rows meet the
     * target.
     *
     * @throws NotFoundException of kind {@code course} when the course is not stored, of kind {@code
     *     assignment} naming {@code courseKey/assignmentKey} when the course has no such assignment, or
     *     for the language, its profile book or the assignment's deck
     * @throws ForbiddenException when the actor may not read the course's cohort
     */
    public List<AssignmentProgress> cohortProgress(
            Actor actor, String courseKey, String assignmentKey, LocalDate on, Ladder ladder) {
        CourseScope scope = scopeOf(courseKey);
        actor.requirePermission(Permission.READ_COHORT, scope);
        Assignment entry = loadAssignment(courseKey, assignmentKey);
        List<AssignmentProgress> rows = new ArrayList<>();
        for (String learnerId : scope.roster().gradedLearners()) {
            Enrolment enrolment = scope.roster().enrolmentOf(learnerId).orElseThrow();
            rows.add(progressOf(scope, entry, enrolment, on, ladder));
        }
        return List.copyOf(rows);
    }

    private Assignment loadAssignment(String courseKey, String key) {
        return stores.courses().assignments(courseKey).stream()
                .filter(row -> row.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("assignment", courseKey + "/" + key));
    }

    private AssignmentProgress progressOf(
            CourseScope scope, Assignment entry, Enrolment enrolment, LocalDate on, Ladder ladder) {
        String language = scope.course().language();
        DrillContext context = drill.context(language);
        Deck deck = drill.deck(language, entry.deckKey());
        MasteryLedger ledger =
                stores.ledgers().get(enrolment.learnerId()).orElseGet(() -> MasteryLedger.empty(enrolment.learnerId()));
        List<Attempt> attempts = stores.attempts().listByLearner(enrolment.learnerId());
        return AssignmentProgress.of(context, entry, deck, enrolment, ledger, attempts, ladder, on);
    }
}
