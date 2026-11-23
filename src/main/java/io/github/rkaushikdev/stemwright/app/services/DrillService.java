package io.github.rkaushikdev.stemwright.app.services;

import io.github.rkaushikdev.stemwright.app.Clock;
import io.github.rkaushikdev.stemwright.app.DomainEvent;
import io.github.rkaushikdev.stemwright.app.EventBus;
import io.github.rkaushikdev.stemwright.app.LanguageBundle;
import io.github.rkaushikdev.stemwright.app.Loaders;
import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.drill.Deck;
import io.github.rkaushikdev.stemwright.domain.drill.DrillContext;
import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicy;
import io.github.rkaushikdev.stemwright.domain.drill.Membership;
import io.github.rkaushikdev.stemwright.domain.drill.Queue;
import io.github.rkaushikdev.stemwright.domain.drill.QueueOptions;
import io.github.rkaushikdev.stemwright.domain.drill.ResumeAction;
import io.github.rkaushikdev.stemwright.domain.drill.ResumePlan;
import io.github.rkaushikdev.stemwright.domain.drill.Review;
import io.github.rkaushikdev.stemwright.domain.drill.ReviewRequest;
import io.github.rkaushikdev.stemwright.domain.drill.ReviewResult;
import io.github.rkaushikdev.stemwright.domain.drill.ReviewSettings;
import io.github.rkaushikdev.stemwright.domain.drill.Session;
import io.github.rkaushikdev.stemwright.domain.errors.NotFoundException;
import io.github.rkaushikdev.stemwright.domain.mastery.CellStrength;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LeechPolicy;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.ortho.Hint;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The drill service is where a request meets the domain. It loads a context, builds the queue,
 * marks an answer and writes the three things an answer changes: the attempt log, the open session
 * and the ledger.
 *
 * <p>Those three writes are not atomic here, and the order matters. The ledger goes last, because
 * an attempt recorded without a ledger update is a lost schedule change that the next review will
 * fix, while a ledger update without the attempt is a mark nobody can account for.
 *
 * <p>Every method that takes no day works on the clock's day.
 */
public final class DrillService {

    private final Stores stores;
    private final EventBus bus;
    private final Clock clock;

    public DrillService(Stores stores, EventBus bus, Clock clock) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * What happens to the learner's open session of the deck when they come back today.
     *
     * @see #resumePlan(String, String, LocalDate)
     */
    public ResumePlan resumePlan(String learnerId, String deckKey) {
        return resumePlan(learnerId, deckKey, clock.today());
    }

    /** What happens to the learner's open session of the deck when they come back on the day. */
    public ResumePlan resumePlan(String learnerId, String deckKey, LocalDate on) {
        List<Session> open = stores.sessions().listOpen(learnerId);
        // Falls back to a stale session of the same deck so the plan can say it will be closed.
        // Reporting nothing to resume when there is an open session sitting there is technically
        // true and useless.
        Optional<Session> candidate = ResumePlan.pickResumable(open, deckKey, on)
                .or(() -> ResumePlan.staleSessions(open, on).stream()
                        .filter(session -> session.deckKey().equals(deckKey))
                        .findFirst());
        return ResumePlan.of(candidate, on);
    }

    /**
     * The drill context of the language.
     *
     * @throws NotFoundException for the language or its profile book
     */
    public DrillContext context(String language) {
        return Loaders.loadContext(stores, language);
    }

    /**
     * The deck with the key in the language.
     *
     * @throws NotFoundException naming {@code language/key} when it is not stored
     */
    public Deck deck(String language, String key) {
        return Loaders.requireDeck(stores, language, key);
    }

    /**
     * The cells the deck takes on the day.
     *
     * @throws NotFoundException for the language, its profile book or the deck
     */
    public Membership membership(String language, String deckKey, LocalDate on) {
        return Membership.resolve(context(language), deck(language, deckKey), on);
    }

    /** Today's queue under the default settings. */
    public Queue queue(String learnerId, String language, String deckKey) {
        return queue(learnerId, language, deckKey, clock.today(), DrillSettings.DEFAULT);
    }

    /** The day's queue under the default settings. */
    public Queue queue(String learnerId, String language, String deckKey, LocalDate on) {
        return queue(learnerId, language, deckKey, on, DrillSettings.DEFAULT);
    }

    /** Today's queue under the settings. */
    public Queue queue(String learnerId, String language, String deckKey, DrillSettings settings) {
        return queue(learnerId, language, deckKey, clock.today(), settings);
    }

    /**
     * The learner's queue of the deck on the day, leaving out what the session they would answer
     * into has buried. Building it closes the learner's stale open sessions when there is no
     * session of the day to resume.
     *
     * @throws NotFoundException for the language, its profile book or the deck
     */
    public Queue queue(String learnerId, String language, String deckKey, LocalDate on, DrillSettings settings) {
        DrillContext context = context(language);
        Membership membership = membership(language, deckKey, on);
        MasteryLedger ledger = ledgerOf(learnerId);
        Session session = openFor(learnerId, deckKey, on);
        return Queue.assemble(
                context, membership, policyOf(settings), ledger, on, QueueOptions.DEFAULT.withBuried(session.buried()));
    }

    /** Marks and records the answer under the default settings. */
    public AnswerOutcome answer(AnswerRequest request) {
        return answer(request, DrillSettings.DEFAULT);
    }

    /**
     * Marks the answer against the revision in force on its day, at the tolerance level the deck
     * runs at, and records it: the attempt is appended, the open session grows by it, and the
     * ledger is rescheduled, in that order. The attempt is then announced, and so is a suspension
     * when the answer left the cell suspended. A wrong answer earns the next rung of the hint
     * ladder for the cell in this sitting.
     *
     * @throws NotFoundException for the language, its profile book or the deck, or naming the cell
     *     when the deck does not take it on the day
     */
    public AnswerOutcome answer(AnswerRequest request, DrillSettings settings) {
        LocalDate day = request.on().orElseGet(clock::today);
        DrillContext context = context(request.language());
        Deck deck = deck(request.language(), request.deckKey());
        Membership membership = Membership.resolve(context, deck, day);
        Cell cell = membership.cells().stream()
                .filter(entry -> entry.id().equals(request.cellId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("cell", request.cellId()));

        Session session = openFor(request.learnerId(), request.deckKey(), day);
        MasteryLedger ledger = ledgerOf(request.learnerId());
        ReviewSettings reviewSettings = settingsFor(request.language(), settings);

        ReviewRequest.Builder asked = ReviewRequest.builder()
                .cell(cell)
                .given(request.given())
                .on(day)
                .learnerId(request.learnerId())
                .sessionId(session.id())
                .sequence(session.nextSequence())
                .deckKey(request.deckKey());
        deck.toleranceLevel().ifPresent(asked::deckTolerance);
        request.elapsedMs().ifPresent(asked::elapsedMs);
        request.hinted().ifPresent(level -> asked.given(request.given(), level));
        ReviewResult review = Review.answer(context, reviewSettings, ledger, session.buried(), asked.build());
        Optional<Hint> hint = HintRung.forAnswer(session, cell.id(), review.match());

        Session grown = session.recordAttempt(review.attempt(), review.buried());
        stores.attempts().append(List.of(review.attempt()));
        stores.sessions().put(grown);
        stores.ledgers().put(review.ledger());

        bus.publish(new DomainEvent.AttemptRecorded(clock.now(), request.learnerId(), review.attempt()));

        CellStrength after = review.ledger().strengthFor(cell.id());
        if (after.suspended()) {
            bus.publish(new DomainEvent.CellSuspended(
                    clock.now(), request.learnerId(), request.learnerId(), cell.id(), after.lapses()));
        }

        return new AnswerOutcome(review, grown, hint);
    }

    /**
     * Closes the learner's session and announces it with the number of answers it holds.
     *
     * @throws NotFoundException naming the session when it is not stored or is another learner's
     */
    public Session finish(String learnerId, String sessionId) {
        Session session = stores.sessions()
                .get(sessionId)
                .filter(found -> found.learnerId().equals(learnerId))
                .orElseThrow(() -> new NotFoundException("session", sessionId));
        Session closed = session.close();
        stores.sessions().put(closed);
        bus.publish(new DomainEvent.SessionClosed(
                clock.now(),
                learnerId,
                sessionId,
                learnerId,
                closed.summarise().tally().total()));
        return closed;
    }

    private MasteryLedger ledgerOf(String learnerId) {
        return stores.ledgers().get(learnerId).orElseGet(() -> MasteryLedger.empty(learnerId));
    }

    private static DrillPolicy policyOf(DrillSettings settings) {
        return DrillPolicy.of(settings.policy());
    }

    /**
     * The language's marking and transliteration with the caller's ladder and leech policy, under the
     * drill policy the
     * settings patch: the policy's {@code buryTwins} decides whether an answer buries the cell and
     * its twins, a patch that names a leech threshold moves the leech policy to it while keeping
     * what the leech policy does to a leech, and a patch that names a ladder key climbs the built in
     * ladder it names instead of the caller's. The credit rule is left at its default.
     */
    private ReviewSettings settingsFor(String language, DrillSettings settings) {
        LanguageBundle bundle = Loaders.requireLanguage(stores, language);
        DrillPolicy policy = policyOf(settings);
        LeechPolicy leech = settings.policy()
                .leechThreshold()
                .map(threshold -> withThreshold(settings.leech(), threshold))
                .orElse(settings.leech());
        Ladder ladder = settings.policy().ladderKey().map(Ladder::of).orElse(settings.ladder());
        return ReviewSettings.of(bundle.ortho(), bundle.rules(), ladder, leech, bundle.transliteration())
                .withBuryTwins(policy.buryTwins());
    }

    private static LeechPolicy withThreshold(LeechPolicy leech, int threshold) {
        return LeechPolicy.builder()
                .threshold(threshold)
                .action(leech.action())
                .consecutiveOnly(leech.consecutiveOnly())
                .demotionRest(leech.demotionRest())
                .build();
    }

    /**
     * Finds the session to answer into. A stale one is closed rather than resumed, because its
     * buried twins were buried for a day that has passed, and resuming into it would hide cells that
     * are due now.
     */
    private Session openFor(String learnerId, String deckKey, LocalDate on) {
        List<Session> open = stores.sessions().listOpen(learnerId);
        Optional<Session> candidate = ResumePlan.pickResumable(open, deckKey, on);
        ResumePlan plan = ResumePlan.of(candidate, on);

        if (plan.action() == ResumeAction.RESUME && candidate.isPresent()) {
            return candidate.get();
        }

        for (Session stale : ResumePlan.staleSessions(open, on)) {
            stores.sessions().put(stale.close());
        }
        return Session.open(learnerId, deckKey, on);
    }
}
