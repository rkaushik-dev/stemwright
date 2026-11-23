package io.github.rkaushikdev.stemwright.cli;

import io.github.rkaushikdev.stemwright.app.Application;
import io.github.rkaushikdev.stemwright.app.LanguageBundle;
import io.github.rkaushikdev.stemwright.app.Loaders;
import io.github.rkaushikdev.stemwright.domain.drill.DrillContext;
import io.github.rkaushikdev.stemwright.domain.drill.Membership;
import io.github.rkaushikdev.stemwright.domain.errors.PolicyException;
import io.github.rkaushikdev.stemwright.domain.report.Finding;
import io.github.rkaushikdev.stemwright.domain.report.FindingCounts;
import io.github.rkaushikdev.stemwright.domain.report.Health;
import io.github.rkaushikdev.stemwright.domain.report.HealthInput;
import io.github.rkaushikdev.stemwright.domain.report.HealthInput.ResolvedDeck;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The sweep somebody runs before a term starts.
 *
 * <p>It exits non zero when it found an error, so it can be a step in a deployment rather than
 * something a person has to read. {@code --strict} widens that to warnings, which is what a
 * repository of teaching material wants in its own ci, where a shadowed binding is a mistake even
 * though nothing is broken yet.
 */
public final class DoctorCommand {

    private DoctorCommand() {}

    /**
     * {@code doctor}: the health findings for a language on a day, with what was looked at.
     *
     * <p>The command fails with a {@link PolicyException} of rule {@code doctor.errors} when anything
     * was found at the error level, and of rule {@code doctor.strict} when {@code --strict} was given
     * and anything was found at the warning level. Either way the failure's message is the whole
     * report with the reason under it.
     */
    public static Command of(Application app) {
        return Command.of(
                "doctor",
                "check a language for the holes that leave a learner with nothing to drill",
                "stemwright doctor --language latin --strict",
                FlagSpecs.of(
                        Map.entry("language", Flags.LANGUAGE),
                        Map.entry("on", Flags.DAY),
                        Map.entry("strict", FlagSpec.of(FlagKind.BOOLEAN, "treat a warning as a failure too"))),
                (args, context) -> check(app, args));
    }

    private static String check(Application app, ParsedArgs args) {
        String language = args.requireFlag("language");
        LanguageBundle bundle = Loaders.requireLanguage(app.stores(), language);
        LocalDate on = Flags.dayFrom(app.clock(), args);

        DrillContext drill = app.drill().context(language);
        List<ResolvedDeck> decks = app.decks().list(language).stream()
                .map(deck -> new ResolvedDeck(deck, Membership.resolve(drill, deck, on)))
                .toList();

        List<Finding> findings = Health.check(new HealthInput(drill, bundle.ortho(), decks, on));
        FindingCounts counts = Health.countLevels(findings);
        boolean strict = args.booleanFlag("strict");

        String body = findings.isEmpty()
                ? Render.section("Findings", "")
                : Render.section(
                        "Findings",
                        Render.table(
                                List.of(Column.of("level"), Column.of("area"), Column.of("what was found")),
                                findings.stream()
                                        .map(finding ->
                                                List.of(finding.level().key(), finding.area(), finding.message()))
                                        .toList()));

        String report = String.join(
                "\n",
                Health.describe(findings, language),
                "",
                Render.keyValues(List.of(
                        Map.entry("on", on.toString()),
                        Map.entry(
                                "classes",
                                String.valueOf(drill.classes().classes().size())),
                        Map.entry(
                                "profiles",
                                String.valueOf(drill.book().histories().size())),
                        Map.entry("lexemes", String.valueOf(drill.lexicon().size())),
                        Map.entry("decks", String.valueOf(decks.size())))),
                "",
                body);

        // The report is carried on the failure rather than printed ahead of it, so a caller reading
        // only the failure stream still gets the whole of it.
        if (!Health.isHealthy(findings)) {
            throw new PolicyException(
                    "doctor.errors", report + "\n\n" + counts.errors() + " errors stop a class drilling");
        }
        if (strict && counts.warnings() > 0) {
            throw new PolicyException(
                    "doctor.strict", report + "\n\n" + counts.warnings() + " warnings, and strict was asked for");
        }
        return report;
    }
}
