package io.github.rkaushikdev.stemwright.app.services;

import static io.github.rkaushikdev.stemwright.support.App.seededStores;
import static io.github.rkaushikdev.stemwright.support.App.testHarness;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.app.DomainEvent.AttemptRecorded;
import io.github.rkaushikdev.stemwright.app.DomainEvent.CellSuspended;
import io.github.rkaushikdev.stemwright.app.DomainEvent.SessionClosed;
import io.github.rkaushikdev.stemwright.app.RecordingBus;
import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.drill.Deck;
import io.github.rkaushikdev.stemwright.domain.drill.DeckInput;
import io.github.rkaushikdev.stemwright.domain.drill.DrillContext;
import io.github.rkaushikdev.stemwright.domain.drill.DrillPolicyPatch;
import io.github.rkaushikdev.stemwright.domain.drill.ExclusionReason;
import io.github.rkaushikdev.stemwright.domain.drill.Queue;
import io.github.rkaushikdev.stemwright.domain.drill.ResumeAction;
import io.github.rkaushikdev.stemwright.domain.drill.ResumePlan;
import io.github.rkaushikdev.stemwright.domain.drill.Session;
import io.github.rkaushikdev.stemwright.domain.errors.NotFoundException;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LadderInput;
import io.github.rkaushikdev.stemwright.domain.mastery.LeechPolicy;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.support.App.Harness;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DrillServiceTest {

    private static final LocalDate DAY = d("2024-10-21");

    private static final Ladder FLAT =
            Ladder.of(LadderInput.builder("flat", 1, 3, 7).spread(0).build());

    private static final DrillSettings SETTINGS = DrillSettings.builder()
            .policy(DrillPolicyPatch.builder()
                    .newPerDay(3)
                    .perLexemeCap(10)
                    .rowSpacing(0)
                    .build())
            .ladder(FLAT)
            .leech(LeechPolicy.builder().threshold(2).build())
            .build();

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    /** The seeded stores with a deck that takes the whole language, and a service over them. */
    private record Built(Stores stores, RecordingBus bus, DrillService service) {}

    private static Built build() {
        Stores stores = seededStores();
        stores.decks().put(Deck.of(LATIN_NOUN, DeckInput.of("all")));
        Harness harness = testHarness();
        return new Built(stores, harness.bus(), new DrillService(stores, harness.bus(), harness.clock()));
    }

    private static AnswerRequest ask(String cellId, String given) {
        return AnswerRequest.of("lrn_one", "latin", "all", cellId, given).withOn(DAY);
    }

    private static Queue queueOn(DrillService service, LocalDate on) {
        return service.queue("lrn_one", "latin", "all", on, SETTINGS);
    }

    private static Cell firstCell(Queue queue) {
        return queue.entries().get(0).cell();
    }

    private static Cell secondCell(Queue queue) {
        return queue.entries().get(1).cell();
    }

    @Nested
    class ContextAndDeck {

        @Test
        void assemblesAContextFromTheStores() {
            DrillService service = build().service();
            DrillContext context = service.context("latin");
            assertThat(context.schema().language()).isEqualTo("latin");
            assertThat(context.lexicon().byId()).hasSize(4);
        }

        @Test
        void refusesALanguageOrADeckNobodyStored() {
            DrillService service = build().service();
            assertThatThrownBy(() -> service.context("greek")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.deck("latin", "nowhere")).isInstanceOf(NotFoundException.class);
        }

        @Test
        void resolvesTheMembershipOnADay() {
            DrillService service = build().service();
            assertThat(service.membership("latin", "all", DAY).cells()).hasSize(30);
        }
    }

    @Nested
    class Queueing {

        @Test
        void introducesNewCellsUpToTheBudget() {
            DrillService service = build().service();
            Queue queue = queueOn(service, DAY);
            assertThat(queue.length()).isEqualTo(3);
            assertThat(queue.newCount()).isEqualTo(3);
        }

        @Test
        void takesTheDayOffTheClockWhenNoneIsGiven() {
            DrillService service = build().service();
            Queue queue = service.queue("lrn_one", "latin", "all", SETTINGS);
            assertThat(queue.on()).isEqualTo(d("2024-10-21"));
        }

        @Test
        void leavesOutACellBuriedEarlierInTheSameSession() {
            DrillService service = build().service();
            Cell target = firstCell(queueOn(service, DAY));
            service.answer(ask(target.id(), "x"), SETTINGS);
            Queue second = queueOn(service, DAY);
            assertThat(second.entries()).extracting(entry -> entry.cell().id()).doesNotContain(target.id());
        }
    }

    @Nested
    class Answer {

        @Test
        void marksTheAnswerAndWritesTheThreeRecords() {
            Built built = build();
            DrillService service = built.service();
            Cell target = firstCell(queueOn(service, DAY));
            Optional<Cell> expected = service.membership("latin", "all", DAY).cells().stream()
                    .filter(cell -> cell.id().equals(target.id()))
                    .findFirst();
            assertThat(expected).isPresent();

            AnswerOutcome outcome = service.answer(ask(target.id(), "x").withElapsedMs(900), SETTINGS);

            assertThat(outcome.session().attempts()).hasSize(1);
            assertThat(outcome.review().attempt().elapsedMs()).contains(900.0);
            assertThat(built.stores().attempts().listByLearner("lrn_one")).hasSize(1);
            assertThat(built.stores().ledgers().get("lrn_one").orElseThrow().byCell())
                    .isNotEmpty();
            assertThat(built.bus().ofKind(AttemptRecorded.class)).hasSize(1);
        }

        @Test
        void marksARightAnswerAsRight() {
            DrillService service = build().service();
            Queue queue = queueOn(service, DAY);
            AnswerOutcome outcome = service.answer(ask(firstCell(queue).id(), "x"), SETTINGS);
            AnswerOutcome second =
                    service.answer(ask(secondCell(queue).id(), outcome.review().expected()), SETTINGS);
            assertThat(second.review().match().verdict()).isNotNull();
        }

        @Test
        void keepsAnsweringIntoTheSameSession() {
            DrillService service = build().service();
            Queue queue = queueOn(service, DAY);
            AnswerOutcome first = service.answer(ask(firstCell(queue).id(), "x"), SETTINGS);
            AnswerOutcome second = service.answer(ask(secondCell(queue).id(), "x"), SETTINGS);
            assertThat(second.session().id()).isEqualTo(first.session().id());
            assertThat(second.session().attempts()).hasSize(2);
        }

        @Test
        void refusesACellThatIsNotInTheDeck() {
            DrillService service = build().service();
            assertThatThrownBy(() -> service.answer(ask("cell_zzzzzz", "x"))).isInstanceOf(NotFoundException.class);
        }

        @Test
        void announcesASuspensionOnceTheLeechThresholdIsPassed() {
            Built built = build();
            DrillService service = built.service();
            Cell target = firstCell(queueOn(service, DAY));
            for (int step = 0; step < 2; step += 1) {
                service.answer(ask(target.id(), "nonsense"), SETTINGS);
            }
            assertThat(built.bus().ofKind(CellSuspended.class)).hasSize(1);
            MasteryLedger ledger = built.stores().ledgers().get("lrn_one").orElseThrow();
            assertThat(ledger.strengthFor(target.id()).suspended()).isTrue();
        }
    }

    @Nested
    class UnderThePolicy {

        @Test
        void marksAtTheToleranceLevelTheDeckRunsAt() {
            Built built = build();
            built.stores()
                    .decks()
                    .put(Deck.of(
                            LATIN_NOUN,
                            DeckInput.builder("strict").toleranceLevel("exact").build()));
            DrillService service = built.service();
            Cell target = firstCell(queueOn(service, DAY));
            String expected =
                    service.answer(ask(target.id(), "x"), SETTINGS).review().expected();
            String shouted = expected.toUpperCase(Locale.ROOT);

            AnswerOutcome lenient = service.answer(ask(target.id(), shouted), SETTINGS);
            AnswerOutcome strict = service.answer(
                    AnswerRequest.of("lrn_one", "latin", "strict", target.id(), shouted)
                            .withOn(DAY),
                    SETTINGS);

            assertThat(lenient.review().attempt().toleranceLevel()).isEqualTo("standard");
            assertThat(lenient.review().match().isCorrect()).isTrue();
            assertThat(strict.review().attempt().toleranceLevel()).isEqualTo("exact");
            assertThat(strict.review().match().isCorrect()).isFalse();
        }

        @Test
        void leavesTheTwinsInTheQueueWhenThePolicySaysNotToBury() {
            DrillSettings keepTwins = DrillSettings.builder()
                    .policy(DrillPolicyPatch.builder()
                            .newPerDay(3)
                            .perLexemeCap(10)
                            .rowSpacing(0)
                            .buryTwins(false)
                            .build())
                    .ladder(FLAT)
                    .build();
            DrillService service = build().service();
            Cell target = firstCell(service.queue("lrn_one", "latin", "all", DAY, keepTwins));

            AnswerOutcome outcome = service.answer(ask(target.id(), "x"), keepTwins);

            assertThat(outcome.session().buried().size()).isZero();
            assertThat(service.answer(ask(target.id(), "x"), SETTINGS)
                            .session()
                            .buried()
                            .isBuried(target.id()))
                    .isTrue();
        }

        @Test
        void suspendsAtTheThresholdThePolicyPatchNames() {
            DrillSettings patched = DrillSettings.builder()
                    .policy(DrillPolicyPatch.builder()
                            .newPerDay(3)
                            .perLexemeCap(10)
                            .rowSpacing(0)
                            .leechThreshold(4)
                            .build())
                    .ladder(FLAT)
                    .leech(LeechPolicy.builder().threshold(2).build())
                    .build();
            Built built = build();
            DrillService service = built.service();
            Cell target = firstCell(queueOn(service, DAY));

            for (int step = 0; step < 3; step += 1) {
                service.answer(ask(target.id(), "nonsense"), patched);
            }
            assertThat(built.bus().ofKind(CellSuspended.class)).isEmpty();

            service.answer(ask(target.id(), "nonsense"), patched);
            assertThat(built.bus().ofKind(CellSuspended.class)).hasSize(1);
        }
    }

    @Nested
    class Finish {

        @Test
        void closesTheSessionAndAnnouncesIt() {
            Built built = build();
            DrillService service = built.service();
            AnswerOutcome outcome =
                    service.answer(ask(firstCell(queueOn(service, DAY)).id(), "x"), SETTINGS);
            Session closed = service.finish("lrn_one", outcome.session().id());
            assertThat(closed.closed()).isTrue();
            assertThat(built.bus().ofKind(SessionClosed.class).get(0).answers()).isEqualTo(1);
        }

        @Test
        void refusesASessionBelongingToSomebodyElse() {
            DrillService service = build().service();
            AnswerOutcome outcome =
                    service.answer(ask(firstCell(queueOn(service, DAY)).id(), "x"), SETTINGS);
            assertThatThrownBy(() -> service.finish("lrn_two", outcome.session().id()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void refusesASessionNobodyStored() {
            DrillService service = build().service();
            assertThatThrownBy(() -> service.finish("lrn_one", "sess_zzzzzz")).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Resuming {

        @Test
        void reportsThatThereIsNothingToResume() {
            DrillService service = build().service();
            ResumePlan plan = service.resumePlan("lrn_one", "all", DAY);
            assertThat(plan.action()).isEqualTo(ResumeAction.OPEN);
            assertThat(plan.answered()).isZero();
        }

        @Test
        void reportsASessionFromTodayAsResumable() {
            DrillService service = build().service();
            service.answer(ask(firstCell(queueOn(service, DAY)).id(), "x"), SETTINGS);
            ResumePlan plan = service.resumePlan("lrn_one", "all", DAY);
            assertThat(plan.action()).isEqualTo(ResumeAction.RESUME);
            assertThat(plan.answered()).isEqualTo(1);
        }

        @Test
        void answersIntoTheSameSessionOnTheSameDay() {
            DrillService service = build().service();
            Queue queue = queueOn(service, DAY);
            AnswerOutcome first = service.answer(ask(firstCell(queue).id(), "x"), SETTINGS);
            AnswerOutcome second = service.answer(ask(secondCell(queue).id(), "x"), SETTINGS);
            assertThat(second.session().id()).isEqualTo(first.session().id());
        }

        @Test
        void reportsASessionFromAPreviousDayAsStale() {
            DrillService service = build().service();
            service.answer(ask(firstCell(queueOn(service, DAY)).id(), "x"), SETTINGS);
            LocalDate later = d("2024-10-23");
            ResumePlan plan = service.resumePlan("lrn_one", "all", later);
            assertThat(plan.action()).isEqualTo(ResumeAction.CLOSE_AND_OPEN);
            assertThat(plan.staleFor()).isEqualTo(2);
        }

        @Test
        void opensAFreshSessionOnANewDayAndClosesTheStaleOne() {
            Built built = build();
            DrillService service = built.service();
            AnswerOutcome first =
                    service.answer(ask(firstCell(queueOn(service, DAY)).id(), "x"), SETTINGS);

            LocalDate later = d("2024-10-23");
            Queue nextQueue = queueOn(service, later);
            AnswerOutcome second =
                    service.answer(ask(firstCell(nextQueue).id(), "x").withOn(later), SETTINGS);

            assertThat(second.session().id()).isNotEqualTo(first.session().id());
            assertThat(built.stores().sessions().listOpen("lrn_one")).hasSize(1);
            assertThat(built.stores().sessions().get(first.session().id()).map(Session::closed))
                    .contains(true);
        }

        @Test
        void doesNotCarryYesterdayBurialIntoToday() {
            DrillService service = build().service();
            Cell target = firstCell(queueOn(service, DAY));
            service.answer(ask(target.id(), "x"), SETTINGS);

            LocalDate later = d("2024-10-23");
            Queue nextQueue = queueOn(service, later);
            assertThat(nextQueue.excluded().get(target.id())).isNotEqualTo(ExclusionReason.BURIED);
        }
    }
}
