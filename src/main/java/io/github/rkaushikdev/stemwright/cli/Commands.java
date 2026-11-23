package io.github.rkaushikdev.stemwright.cli;

import io.github.rkaushikdev.stemwright.app.Application;
import io.github.rkaushikdev.stemwright.app.Loaders;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.http.AccountDirectory;
import io.github.rkaushikdev.stemwright.http.Api;
import io.github.rkaushikdev.stemwright.http.ApiOptions;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The commands. Each one loads what it needs, asks the domain, and formats the answer, and none of
 * them holds a rule of its own.
 *
 * <p>Nothing here goes through the report service, because that service takes a course scope so
 * it can check who is asking. A local operator running this binary has already been trusted by the
 * file system, and inventing a fake scope to satisfy a check that means nothing here would make the
 * real check look optional.
 */
public final class Commands {

    private Commands() {}

    /**
     * {@code queue}: the cells a learner would be asked on a day, today unless {@code --on} says,
     * and with {@code --explain} why the rest were left out.
     */
    public static Command queue(Application app) {
        return QueueCommand.of(app);
    }

    /**
     * {@code paradigm}: the table a lexeme was graded against on a day, headed by the profile and
     * the revision in force then, and with {@code --trace} how each form was built.
     */
    public static Command paradigm(Application app) {
        return ParadigmCommand.of(app);
    }

    /**
     * {@code coverage}: how a learner's ledger stands over a deck on a day, and the bundles or the
     * lexemes that need the most work.
     */
    public static Command coverage(Application app) {
        return CoverageCommand.of(app);
    }

    /** {@code lexemes}: the lexicon of a language, or the lexemes a search term matches. */
    public static Command lexemes(Application app) {
        return Command.of(
                "lexemes",
                "list or search the lexicon of a language",
                "stemwright lexemes --language latin --search girl",
                FlagSpecs.of(
                        Map.entry("language", Flags.LANGUAGE),
                        Map.entry(
                                "search",
                                FlagSpec.of(FlagKind.STRING, "match a lemma or a gloss")
                                        .withAlias("s"))),
                (args, context) -> {
                    String language = args.requireFlag("language");
                    // Checked before the listing, so a language nobody configured fails rather than
                    // printing an empty table that reads like an empty lexicon.
                    Loaders.requireLanguage(app.stores(), language);
                    Optional<String> term = args.stringFlag("search");
                    List<Lexeme> entries = term.map(search -> app.lexicon().search(language, search))
                            .orElseGet(() -> List.copyOf(
                                    app.lexicon().load(language).byId().values()));
                    return Render.table(
                            List.of(Column.of("lemma"), Column.of("class"), Column.of("gloss")),
                            entries.stream()
                                    .map(entry -> List.of(
                                            entry.citationForm(),
                                            entry.classKey(),
                                            entry.primaryGloss().orElse("")))
                                    .toList());
                });
    }

    /**
     * {@code routes}: what the http layer serves, so an operator can see the surface of a running
     * build without reading its source.
     */
    public static Command routes(Application app) {
        return Command.of(
                "routes", "list the http routes this build serves", "stemwright routes", Map.of(), (args, context) -> {
                    AccountDirectory nobody = token -> Optional.empty();
                    List<String> lines = Api.routeTable(app, ApiOptions.of(nobody));
                    return Render.table(
                            List.of(Column.of("method"), Column.of("path")),
                            lines.stream()
                                    .map(line -> {
                                        int space = line.indexOf(' ');
                                        return List.of(line.substring(0, space), line.substring(space + 1));
                                    })
                                    .toList());
                });
    }

    /** Every command of the cli, over the one application. */
    public static List<Command> all(Application app) {
        return List.of(
                queue(app),
                paradigm(app),
                coverage(app),
                lexemes(app),
                routes(app),
                ImportCommand.of(app),
                DeckCommand.of(app),
                DeckCommand.decks(app),
                DoctorCommand.of(app));
    }
}
