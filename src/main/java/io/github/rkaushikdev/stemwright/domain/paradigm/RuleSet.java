package io.github.rkaushikdev.stemwright.domain.paradigm;

import io.github.rkaushikdev.stemwright.domain.Grouping;
import io.github.rkaushikdev.stemwright.domain.Identifiers;
import io.github.rkaushikdev.stemwright.domain.errors.ProblemCollector;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.lexicon.Stem;
import io.github.rkaushikdev.stemwright.domain.text.Normalise;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The join rules of one language, and realisation: building a lexeme's forms from its stems and a
 * profile's affixes.
 *
 * <p>Joining a stem to an ending is not concatenation. Latin {@code reg} plus {@code si} is {@code
 * rexi}, {@code ag} plus {@code tus} is {@code actus}, and a Greek stem in a stop plus a sigma
 * behaves differently again. The rules that do this are small, ordered and language specific, so
 * they live in data rather than in code.
 *
 * <p>A rule fires on the seam only: the tail of the stem and the head of the affix. That keeps every
 * rule local and makes the trace readable, which matters because the trace is what an instructor
 * is shown when a learner asks why the expected answer is what it is.
 *
 * @param language the language key
 * @param rules the rules, in the order they are tried
 */
public record RuleSet(String language, List<JoinRule> rules) {

    /** The rules of a language that declares none. Every join is plain concatenation under it. */
    public static final RuleSet NONE = new RuleSet("none", List.of());

    private static final int NOTE_LIMIT = 240;

    public RuleSet {
        rules = List.copyOf(rules);
    }

    /**
     * Checks the rules as written, reporting every repeated key and every rule that constrains
     * neither side of the seam at once. A key, note or language that is not usable fails on the
     * spot.
     */
    public static RuleSet of(String language, List<JoinRuleInput> inputs) {
        ProblemCollector problems = new ProblemCollector();
        List<JoinRule> rules = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int index = 0; index < inputs.size(); index += 1) {
            JoinRuleInput input = inputs.get(index);
            String path = "rules." + index;
            String key = Identifiers.asSlug(path + ".key", input.key());
            if (!seen.add(key)) {
                problems.add(path, "repeats the rule key " + key);
                continue;
            }
            String stemEnd = Normalise.normaliseInput(input.stemEnd());
            String affixStart = Normalise.normaliseInput(input.affixStart());
            if (stemEnd.isEmpty() && affixStart.isEmpty()) {
                problems.add(path, "must constrain at least one side of the seam");
                continue;
            }
            rules.add(new JoinRule(
                    key,
                    stemEnd,
                    affixStart,
                    Normalise.normaliseInput(input.becomes()),
                    input.note().map(note -> Normalise.requireText(path + ".note", note, NOTE_LIMIT))));
        }

        problems.throwIfAny("join rules for " + language + " are not usable");
        return new RuleSet(Identifiers.asSlug("language", language), rules);
    }

    /**
     * Applies the first rule that matches the seam, then stops. Rules do not cascade on purpose: a
     * rule that rewrote the seam and then let a second rule fire on the result would make the order
     * of the list into hidden control flow, and the order would then be the thing an editor has to
     * reason about rather than the rules themselves.
     */
    public JoinResult joinAtSeam(String stem, String affix) {
        String plain = stem + affix;
        for (JoinRule rule : rules) {
            if (!stem.endsWith(rule.stemEnd()) || !affix.startsWith(rule.affixStart())) {
                continue;
            }
            String head = stem.substring(0, stem.length() - rule.stemEnd().length());
            String tail = affix.substring(rule.affixStart().length());
            String form = head + rule.becomes() + tail;
            return new JoinResult(form, List.of(new JoinStep(rule.key(), plain, form)));
        }
        return new JoinResult(plain, List.of());
    }

    /**
     * Realises one slot for a lexeme: the literal when the slot states one, otherwise the stem the
     * slot asks for, falling back to the base stem, joined to the affix. Each variant ending is
     * joined to the same stem; a variant the join rules turn into the main form is dropped.
     *
     * @throws ValidationException when the lexeme has neither the stem nor a base stem
     */
    public RealisedCell realiseSlot(Lexeme entry, ParadigmSlot slot) {
        if (slot.literal().isPresent()) {
            return new RealisedCell(
                    slot.bundle(), slot.literal().get(), slot.stemRole(), "", "", true, List.of(), List.of());
        }
        Stem stem = entry.stems().resolve(slot.stemRole());
        JoinResult joined = joinAtSeam(stem.form(), slot.affix());
        List<String> variants = slot.variants().stream()
                .map(variant -> joinAtSeam(stem.form(), variant).form())
                .filter(form -> !form.equals(joined.form()))
                .distinct()
                .toList();
        return new RealisedCell(
                slot.bundle(), joined.form(), stem.role(), stem.form(), slot.affix(), false, joined.steps(), variants);
    }

    /**
     * Realises the profile's slot for the bundle.
     *
     * @throws ValidationException when the profile has no slot for the bundle
     */
    public RealisedCell realise(Lexeme entry, ParadigmProfile profile, FeatureBundle bundle) {
        return realiseSlot(entry, profile.requireSlot(bundle));
    }

    /** Every cell of the profile realised for one lexeme, in slot order. */
    public List<RealisedCell> realiseAll(Lexeme entry, ParadigmProfile profile) {
        return profile.slots().values().stream()
                .map(slot -> realiseSlot(entry, slot))
                .toList();
    }

    /** The expected form of the lexeme in the bundle. */
    public String formOf(Lexeme entry, ParadigmProfile profile, FeatureBundle bundle) {
        return realise(entry, profile, bundle).form();
    }

    /**
     * The bundles whose realised forms collide for this lexeme, by form, both in slot order. This
     * is syncretism as the learner meets it, after the join rules have run, which can differ from
     * both the declaration and what the raw endings suggest.
     */
    public Map<String, List<String>> collidingForms(Lexeme entry, ParadigmProfile profile) {
        Map<String, List<RealisedCell>> grouped = Grouping.groupBy(realiseAll(entry, profile), RealisedCell::form);
        Map<String, List<String>> byForm = new LinkedHashMap<>();
        grouped.forEach((form, realised) -> {
            if (realised.size() > 1) {
                byForm.put(form, realised.stream().map(RealisedCell::bundle).toList());
            }
        });
        return Collections.unmodifiableMap(byForm);
    }
}
