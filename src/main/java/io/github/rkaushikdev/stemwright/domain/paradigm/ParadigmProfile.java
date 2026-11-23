package io.github.rkaushikdev.stemwright.domain.paradigm;

import io.github.rkaushikdev.stemwright.domain.Identifiers;
import io.github.rkaushikdev.stemwright.domain.errors.ProblemCollector;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.domain.features.FeatureSchema;
import io.github.rkaushikdev.stemwright.domain.features.Notation;
import io.github.rkaushikdev.stemwright.domain.lexicon.Stem;
import io.github.rkaushikdev.stemwright.domain.text.Normalise;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A paradigm profile says how every cell of one inflection class is built. One slot per bundle:
 * which stem to attach to, what to attach, and whether the form is irregular enough that the
 * profile just states it outright.
 *
 * <p>A profile is not a table of answers. It is a table of recipes, and the answer only exists once
 * a lexeme is put through it. That is what lets one profile serve six hundred nouns and what makes
 * a profile edit a change to all six hundred at once, which is why profiles are revised rather than
 * mutated: {@link #withSlot} returns a new profile, and {@link RevisionHistory} decides when it
 * takes effect.
 *
 * @param key the profile key
 * @param language the language key, taken from the schema
 * @param classKey the key of the inflection class the profile was written for
 * @param label the printed name, the key itself when none was given
 * @param slots every slot by its canonical bundle key, in the order the slots were written
 */
public record ParadigmProfile(
        String key, String language, String classKey, String label, Map<String, ParadigmSlot> slots) {

    private static final int LITERAL_LIMIT = 128;
    private static final int NOTE_LIMIT = 240;
    private static final int LABEL_LIMIT = 96;

    public ParadigmProfile {
        slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
    }

    /**
     * Accepts both spellings of a bundle so a hand written profile can say {@code gen.sg} and a
     * machine written one can say {@code case=gen;number=sg}.
     */
    public static FeatureBundle readBundle(FeatureSchema schema, String text, String path) {
        String cleaned = Normalise.normaliseInput(text);
        return cleaned.contains("=")
                ? FeatureBundle.parseKey(schema, cleaned)
                : Notation.parseBundleNotation(schema, cleaned, path);
    }

    /**
     * Checks a profile as written, reporting every unreadable, incomplete or repeated bundle, every
     * slot with neither an affix nor a literal and every literal slot with variant endings at once. A
     * key, class key, stem role, literal, note, label or repeated variant that is not usable fails on
     * the spot.
     */
    public static ParadigmProfile of(FeatureSchema schema, ParadigmProfileInput input) {
        ProblemCollector problems = new ProblemCollector();
        String key = Identifiers.asSlug("profile.key", input.key());
        String classKey = Identifiers.asSlug("profile.classKey", input.classKey());
        Map<String, ParadigmSlot> slots = new LinkedHashMap<>();

        if (input.slots().isEmpty()) {
            problems.add("slots", "must hold at least one slot");
        }

        for (int index = 0; index < input.slots().size(); index += 1) {
            ParadigmSlotInput slotInput = input.slots().get(index);
            String path = "slots." + index;
            Optional<FeatureBundle> read =
                    problems.absorbFlat(path, () -> readBundle(schema, slotInput.bundle(), path));
            if (read.isEmpty()) {
                continue;
            }

            FeatureBundle bundle = read.get();
            String canonical = bundle.key();
            if (!bundle.isComplete(schema)) {
                problems.add(path, "does not fix every required dimension: " + slotInput.bundle());
                continue;
            }
            if (slots.containsKey(canonical)) {
                problems.add(path, "repeats the bundle " + canonical);
                continue;
            }
            if (slotInput.affix().isEmpty() && slotInput.literal().isEmpty()) {
                problems.add(path, "must give either an affix or a literal form");
                continue;
            }
            if (slotInput.literal().isPresent() && !slotInput.variants().isEmpty()) {
                problems.add(path, "states a literal form, which takes no variant endings");
                continue;
            }

            slots.put(canonical, slotOf(path, canonical, slotInput));
        }

        problems.throwIfAny("paradigm profile " + key + " is not usable");

        String label = input.label()
                .map(text -> Normalise.requireText("profile.label", text, LABEL_LIMIT))
                .orElse(key);
        return new ParadigmProfile(key, schema.language(), classKey, label, slots);
    }

    /** Checks the parts of a slot whose bundle has already passed, failing on the first bad one. */
    private static ParadigmSlot slotOf(String path, String canonical, ParadigmSlotInput input) {
        String stemRole =
                Identifiers.asSlug(path + ".stemRole", input.stemRole().orElse(Stem.DEFAULT_ROLE));
        String affix = Normalise.normaliseInput(input.affix().orElse(""));
        Optional<String> literal =
                input.literal().map(text -> Normalise.requireText(path + ".literal", text, LITERAL_LIMIT));
        Optional<String> note = input.note().map(text -> Normalise.requireText(path + ".note", text, NOTE_LIMIT));
        return new ParadigmSlot(canonical, stemRole, affix, literal, note, variantsOf(path, affix, input.variants()));
    }

    /**
     * The variant endings normalised, each checked against the main ending and the variants before
     * it, because a variant that repeats an ending accepts nothing new and is a typing slip.
     */
    private static List<String> variantsOf(String path, String affix, List<String> written) {
        Set<String> seen = new LinkedHashSet<>(List.of(affix));
        for (int index = 0; index < written.size(); index += 1) {
            String variant = Normalise.normaliseInput(written.get(index));
            if (!seen.add(variant)) {
                throw ValidationException.field(path + ".variants." + index, "repeats the ending " + variant);
            }
        }
        return List.copyOf(seen).subList(1, seen.size());
    }

    public Optional<ParadigmSlot> slotFor(FeatureBundle bundle) {
        return Optional.ofNullable(slots.get(bundle.key()));
    }

    /**
     * The slot for a bundle.
     *
     * @throws ValidationException when the profile has no slot for it
     */
    public ParadigmSlot requireSlot(FeatureBundle bundle) {
        return slotFor(bundle)
                .orElseThrow(() ->
                        ValidationException.field("bundle", "has no slot in profile " + key + ": " + bundle.key()));
    }

    public int slotCount() {
        return slots.size();
    }

    /** The canonical bundle keys of the slots, in slot order. */
    public List<String> slotBundles() {
        return List.copyOf(slots.keySet());
    }

    /** Every stem role some slot attaches to, once each, sorted. */
    public List<String> stemRolesUsed() {
        return slots.values().stream()
                .map(ParadigmSlot::stemRole)
                .distinct()
                .sorted()
                .toList();
    }

    /** The slots that state a form outright, in slot order. */
    public List<ParadigmSlot> literalSlots() {
        return slots.values().stream()
                .filter(slot -> slot.literal().isPresent())
                .toList();
    }

    /** The bundles the schema can express that the profile says nothing about, in table order. */
    public List<String> holesIn(FeatureSchema schema) {
        return FeatureBundle.enumerate(schema).stream()
                .map(FeatureBundle::key)
                .filter(candidate -> !slots.containsKey(candidate))
                .toList();
    }

    /** True when the profile has a slot for every bundle the schema can express. */
    public boolean isTotal(FeatureSchema schema) {
        return holesIn(schema).isEmpty();
    }

    /**
     * A copy with the slot added, or put in place of the slot for the same bundle however either was
     * spelled. The copy is checked again in full, and the slot goes last.
     */
    public ParadigmProfile withSlot(FeatureSchema schema, ParadigmSlotInput slot) {
        String replaced = readBundle(schema, slot.bundle(), "slot").key();
        List<ParadigmSlotInput> next = new ArrayList<>();
        for (ParadigmSlot existing : slots.values()) {
            if (!existing.bundle().equals(replaced)) {
                next.add(inputOf(existing));
            }
        }
        next.add(slot);
        return of(schema, new ParadigmProfileInput(key, classKey, Optional.of(label), next));
    }

    private static ParadigmSlotInput inputOf(ParadigmSlot slot) {
        return new ParadigmSlotInput(
                slot.bundle(),
                Optional.of(slot.stemRole()),
                Optional.of(slot.affix()),
                slot.literal(),
                slot.note(),
                slot.variants());
    }
}
