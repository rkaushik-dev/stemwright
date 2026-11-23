package io.github.rkaushikdev.stemwright.http.routes;

import static io.github.rkaushikdev.stemwright.support.Drill.PUELLA;
import static io.github.rkaushikdev.stemwright.support.Http.DIRECTORY;
import static io.github.rkaushikdev.stemwright.support.Http.at;
import static io.github.rkaushikdev.stemwright.support.Http.httpHarness;
import static io.github.rkaushikdev.stemwright.support.Http.makeRequest;
import static io.github.rkaushikdev.stemwright.support.Http.textAt;
import static io.github.rkaushikdev.stemwright.support.Http.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.http.HttpResponse;
import io.github.rkaushikdev.stemwright.http.Method;
import io.github.rkaushikdev.stemwright.http.Route;
import io.github.rkaushikdev.stemwright.http.Router;
import io.github.rkaushikdev.stemwright.infra.json.Json;
import io.github.rkaushikdev.stemwright.infra.json.JsonNumber;
import io.github.rkaushikdev.stemwright.infra.json.JsonObject;
import io.github.rkaushikdev.stemwright.support.Http;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The bodies the routes write, as text. A client reads them as text too, so the names, their order
 * and the printed numbers are pinned here.
 */
class RouteBodiesTest {

    private static final String DECK = "/learners/lrn_one/languages/latin/decks/all";
    private static final Map<String, String> COURSE = Map.of("course", "latin-1a");

    private static Router router() {
        Http.Harness harness = httpHarness();
        DrillRouteDeps deps = new DrillRouteDeps(DIRECTORY, harness.drill(), harness.scopeOf());
        List<Route> routes = new ArrayList<>(DrillRoutes.routes(deps));
        routes.addAll(BulkRoutes.routes(deps));
        return Router.of(routes);
    }

    private static HttpResponse post(Router handle, String path, Json body) {
        return handle.handle(makeRequest(
                Method.POST, path, token("tok-student").withQuery(COURSE).withBody(body)));
    }

    private static JsonObject answer(String cellId, String given) {
        return Json.object().put("cellId", cellId).put("given", given).build();
    }

    private static List<String> keysOf(Json value) {
        return List.copyOf(value.asObject().orElseThrow().keys());
    }

    private static String firstCellId(Router handle) {
        return textAt(
                handle.handle(makeRequest(
                                Method.GET,
                                DECK + "/queue",
                                token("tok-student").withQuery(COURSE)))
                        .body(),
                "cells.0.id");
    }

    @Test
    void writesTheQueueWithItsFieldsInTheirFixedOrder() {
        HttpResponse response = router().handle(makeRequest(
                Method.GET, DECK + "/queue", token("tok-student").withQuery(COURSE)));
        assertThat(keysOf(response.body())).containsExactly("on", "deckKey", "cells", "excluded");
        assertThat(keysOf(at(response.body(), "cells.0"))).containsExactly("id", "lexemeId", "bundle");
        assertThat(at(response.body(), "excluded")).isInstanceOf(JsonNumber.class);
    }

    @Test
    void writesAnAnswerWithANullMissWhenThereWasNothingToMiss() {
        Router handle = router();
        String cellId = firstCellId(handle);
        String expected =
                textAt(post(handle, DECK + "/answers", answer(cellId, "x")).body(), "expected");
        HttpResponse right = post(handle, DECK + "/answers", answer(cellId, expected));

        assertThat(keysOf(right.body()))
                .containsExactly("verdict", "expected", "miss", "twinsCredited", "sessionId", "sequence", "variants");
        assertThat(Json.stringify(right.body()))
                .startsWith("{\"verdict\":\"exact\",\"expected\":\"" + expected + "\",\"miss\":null,")
                .endsWith(",\"sequence\":2,\"variants\":[]}");
    }

    @Test
    void writesTheTallyOfAFinishedSessionWithItsAccuracyAsAFraction() {
        Router handle = router();
        String cellId = firstCellId(handle);
        HttpResponse wrong = post(handle, DECK + "/answers", answer(cellId, "x"));
        post(handle, DECK + "/answers", answer(cellId, textAt(wrong.body(), "expected")));
        String sessionId = textAt(wrong.body(), "sessionId");

        HttpResponse finished = handle.handle(makeRequest(
                Method.POST,
                "/learners/lrn_one/sessions/" + sessionId + "/finish",
                token("tok-student").withQuery(COURSE)));
        assertThat(Json.stringify(finished.body()))
                .isEqualTo("{\"sessionId\":\"" + sessionId + "\",\"answers\":2,\"correct\":1,\"accuracy\":0.5}");
    }

    @Test
    void writesOneResultPerAnswerOfABatch() {
        Router handle = router();
        String cellId = firstCellId(handle);
        HttpResponse response = post(
                handle,
                DECK + "/answers/bulk",
                Json.object()
                        .put("answers", Json.array(answer(cellId, "x"), answer("cell_zzzzzz", "x")))
                        .build());

        assertThat(keysOf(response.body())).containsExactly("accepted", "refused", "results");
        assertThat(keysOf(at(response.body(), "results.0"))).containsExactly("cellId", "ok", "verdict", "expected");
        assertThat(Json.stringify(at(response.body(), "results.1")))
                .isEqualTo("{\"cellId\":\"cell_zzzzzz\",\"ok\":false,\"message\":\"cell cell_zzzzzz was not found\"}");
    }

    @Test
    void refusesARequestWithNoBodyAsABodyThatIsNotAnObject() {
        Router handle = router();
        assertThatThrownBy(() -> handle.handle(makeRequest(
                        Method.POST, DECK + "/answers", token("tok-student").withQuery(COURSE))))
                .hasMessage("body is not an object");
        assertThatThrownBy(() -> handle.handle(makeRequest(
                        Method.POST,
                        DECK + "/answers/bulk",
                        token("tok-student").withQuery(COURSE))))
                .hasMessage("body is not an object");
    }

    @Test
    void writesALexemeWithItsFieldsInTheirFixedOrder() {
        assertThat(Json.stringify(LexiconRoutes.lexemeJson(PUELLA)))
                .isEqualTo("{\"id\":\"" + PUELLA.id() + "\",\"lemma\":\"puella\",\"citation\":\"puella\","
                        + "\"language\":\"latin\",\"classKey\":\"first-declension\",\"homograph\":1,"
                        + "\"glosses\":[\"girl\"],\"tags\":[\"core\"],"
                        + "\"stems\":[{\"role\":\"base\",\"form\":\"puell\",\"suppletive\":false}],\"inherent\":\"\"}");
    }
}
