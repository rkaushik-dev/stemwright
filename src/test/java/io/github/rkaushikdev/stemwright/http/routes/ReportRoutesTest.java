package io.github.rkaushikdev.stemwright.http.routes;

import static io.github.rkaushikdev.stemwright.support.Http.DIRECTORY;
import static io.github.rkaushikdev.stemwright.support.Http.httpHarness;
import static io.github.rkaushikdev.stemwright.support.Http.itemsAt;
import static io.github.rkaushikdev.stemwright.support.Http.makeRequest;
import static io.github.rkaushikdev.stemwright.support.Http.numberAt;
import static io.github.rkaushikdev.stemwright.support.Http.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.app.Application;
import io.github.rkaushikdev.stemwright.app.ApplicationOptions;
import io.github.rkaushikdev.stemwright.app.services.AnswerRequest;
import io.github.rkaushikdev.stemwright.domain.drill.Queue;
import io.github.rkaushikdev.stemwright.domain.errors.ForbiddenException;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import io.github.rkaushikdev.stemwright.domain.time.DayRange;
import io.github.rkaushikdev.stemwright.http.HttpRequest;
import io.github.rkaushikdev.stemwright.http.HttpResponse;
import io.github.rkaushikdev.stemwright.http.Method;
import io.github.rkaushikdev.stemwright.http.Query;
import io.github.rkaushikdev.stemwright.http.Router;
import io.github.rkaushikdev.stemwright.infra.json.JsonObject;
import io.github.rkaushikdev.stemwright.support.Http;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReportRoutesTest {

    private static final LocalDate DAY = d("2024-10-21");
    private static final String COVERAGE = "/learners/lrn_one/languages/latin/decks/all/coverage";
    private static final String ACTIVITY = "/learners/lrn_one/activity";
    private static final Map<String, String> WINDOW =
            Map.of("course", "latin-1a", "from", "2024-10-21", "to", "2024-10-27");

    private static LocalDate d(String value) {
        return CivilDates.parseCivilDate(value);
    }

    /** The application the routes run over, and a router over the report routes alone. */
    private record Built(Application app, Router handle) {}

    private static Built build() {
        Http.Harness harness = httpHarness();
        Application app = Application.create(harness.stores(), ApplicationOptions.of(harness.bus(), harness.clock()));
        return new Built(
                app, Router.of(ReportRoutes.routes(new ReportRouteDeps(DIRECTORY, app.reports(), harness.scopeOf()))));
    }

    @Nested
    class RangeFrom {

        @Test
        void readsBothEnds() {
            assertThat(Query.rangeFrom(Map.of("from", "2024-10-21", "to", "2024-10-27")))
                    .isEqualTo(DayRange.of(d("2024-10-21"), d("2024-10-27")));
        }

        @Test
        void refusesAWindowWithOnlyOneEnd() {
            assertThatThrownBy(() -> Query.rangeFrom(Map.of("from", "2024-10-21")))
                    .hasMessageContaining("needs both a from and a to day");
            assertThatThrownBy(() -> Query.rangeFrom(Map.of())).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesAWindowThatEndsBeforeItStarts() {
            assertThatThrownBy(() -> Query.rangeFrom(Map.of("from", "2024-10-27", "to", "2024-10-21")))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class TheCoverageRoute {

        @Test
        void reportsTheCountsAndTheWeakestRows() {
            Router handle = build().handle();
            HttpResponse response = handle.handle(
                    makeRequest(Method.GET, COVERAGE, token("tok-student").withQuery(WINDOW)));
            assertThat(response.status()).isEqualTo(200);
            assertThat(numberAt(response.body(), "counts.known")).isEqualTo(30);
            assertThat(numberAt(response.body(), "counts.untouched")).isEqualTo(30);
            assertThat(itemsAt(response.body(), "weakest")).isNotEmpty();
            assertThat(itemsAt(response.body(), "forecast")).hasSize(7);
            assertThat(itemsAt(response.body(), "forecast")).allSatisfy(day -> assertThat(((JsonObject) day).keys())
                    .containsExactly("on", "due", "carried", "asked"));
        }

        @Test
        void refusesARequestWithNoWindow() {
            Router handle = build().handle();
            HttpRequest request =
                    makeRequest(Method.GET, COVERAGE, token("tok-student").withQuery(Map.of("course", "latin-1a")));
            assertThatThrownBy(() -> handle.handle(request)).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesARequestWithNoCourse() {
            Router handle = build().handle();
            HttpRequest request = makeRequest(
                    Method.GET,
                    COVERAGE,
                    token("tok-student").withQuery(Map.of("from", "2024-10-21", "to", "2024-10-27")));
            assertThatThrownBy(() -> handle.handle(request)).hasMessageContaining("course must be given");
        }

        @Test
        void refusesALearnerReadingSomebodyElse() {
            Router handle = build().handle();
            HttpRequest request =
                    makeRequest(Method.GET, COVERAGE, token("tok-other").withQuery(WINDOW));
            assertThatThrownBy(() -> handle.handle(request)).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void letsTheInstructorReadALearner() {
            Router handle = build().handle();
            HttpResponse response = handle.handle(
                    makeRequest(Method.GET, COVERAGE, token("tok-teacher").withQuery(WINDOW)));
            assertThat(response.status()).isEqualTo(200);
        }
    }

    @Nested
    class TheActivityRoute {

        @Test
        void reportsARowForEveryDayOfTheWindow() {
            Router handle = build().handle();
            HttpResponse response = handle.handle(
                    makeRequest(Method.GET, ACTIVITY, token("tok-student").withQuery(WINDOW)));
            assertThat(itemsAt(response.body(), "days")).hasSize(7);
            assertThat(itemsAt(response.body(), "hardest")).isEmpty();
        }

        @Test
        void countsAnAnswerOnTheDayItHappened() {
            Built built = build();
            Queue queue = built.app().drill().queue("lrn_one", "latin", "all", DAY);
            built.app()
                    .drill()
                    .answer(AnswerRequest.of(
                                    "lrn_one",
                                    "latin",
                                    "all",
                                    queue.entries().getFirst().cell().id(),
                                    "nonsense")
                            .withOn(DAY));

            HttpResponse response = built.handle()
                    .handle(makeRequest(
                            Method.GET, ACTIVITY, token("tok-student").withQuery(WINDOW)));
            assertThat(numberAt(response.body(), "days.0.answers")).isEqualTo(1);
            assertThat(itemsAt(response.body(), "hardest")).hasSize(1);
        }

        @Test
        void refusesALearnerReadingSomebodyElse() {
            Router handle = build().handle();
            HttpRequest request =
                    makeRequest(Method.GET, ACTIVITY, token("tok-other").withQuery(WINDOW));
            assertThatThrownBy(() -> handle.handle(request)).isInstanceOf(ForbiddenException.class);
        }
    }
}
