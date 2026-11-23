package io.github.rkaushikdev.stemwright.cli;

import io.github.rkaushikdev.stemwright.app.Application;
import io.github.rkaushikdev.stemwright.app.LanguageBundle;
import io.github.rkaushikdev.stemwright.app.Loaders;
import io.github.rkaushikdev.stemwright.domain.drill.DrillContext;
import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmProfile;
import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmTable;
import io.github.rkaushikdev.stemwright.domain.paradigm.ResolvedProfile;
import io.github.rkaushikdev.stemwright.domain.paradigm.TableRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The table a lexeme was graded against on a day, which is the command this cli exists for. An
 * instructor checking why a learner was marked wrong wants the table the learner was graded
 * against, on the day they were graded, and that is one call once the revision resolution lives in
 * the layer below.
 *
 * <p>{@code --trace} adds the reason for every form under the table: the stem and ending it was
 * built from and the join rules that fired, or that the profile states it outright. That is the
 * answer to a learner who asks why {@code rexis} rather than {@code regis}.
 */
final class ParadigmCommand {

    private ParadigmCommand() {}

    static Command of(Application app) {
        return Command.of(
                "paradigm",
                "print the table a lexeme was graded against on a day",
                "stemwright paradigm --language latin --lexeme lex_x --rows case --columns number --trace",
                FlagSpecs.of(
                        Map.entry("language", Flags.LANGUAGE),
                        Map.entry("lexeme", requiredString("the lexeme id")),
                        Map.entry("rows", requiredString("the dimension down the side")),
                        Map.entry("columns", requiredString("the dimension across the top")),
                        Map.entry("on", Flags.DAY),
                        Map.entry("trace", FlagSpec.of(FlagKind.BOOLEAN, "say how every form was built"))),
                (args, context) -> show(app, args));
    }

    private static String show(Application app, ParsedArgs args) {
        String language = args.requireFlag("language");
        LocalDate on = Flags.dayFrom(app.clock(), args);
        LanguageBundle bundle = Loaders.requireLanguage(app.stores(), language);
        DrillContext drill = app.drill().context(language);
        Lexeme entry = drill.lexicon().requireLexeme(args.requireFlag("lexeme"));
        ResolvedProfile resolved = drill.book().requireProfileFor(drill.classes(), entry.classKey(), on);
        ParadigmProfile profile = resolved.revision().profile();

        ParadigmTable grid = ParadigmTable.build(
                bundle.schema(),
                bundle.rules(),
                entry,
                profile,
                TableRequest.of(args.requireFlag("rows"), args.requireFlag("columns")));
        String heading = entry.citationForm() + " under "
                + resolved.revision().profileKey() + " revision "
                + resolved.revision().ordinal();
        String table = String.join("\n", heading, "", grid.toText());
        if (!args.booleanFlag("trace")) {
            return table;
        }
        return String.join("\n", table, "", trace(bundle, entry, profile));
    }

    /** One line per slot the lexeme has a form for, in slot order, saying how the form was built. */
    private static String trace(LanguageBundle bundle, Lexeme entry, ParadigmProfile profile) {
        List<List<String>> rows = profile.slots().keySet().stream()
                .map(key -> FeatureBundle.parseKey(bundle.schema(), key))
                .filter(cell -> !entry.contradictsInherent(cell))
                .map(cell -> List.of(
                        cell.key(), bundle.rules().realise(entry, profile, cell).explain(bundle.schema(), cell)))
                .toList();
        return Render.section(
                "How each form is built", Render.table(List.of(Column.of("bundle"), Column.of("built")), rows));
    }

    private static FlagSpec requiredString(String describe) {
        return FlagSpec.of(FlagKind.STRING, describe).asRequired();
    }
}
