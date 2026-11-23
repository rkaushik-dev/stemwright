package io.github.rkaushikdev.stemwright.io;

import io.github.rkaushikdev.stemwright.domain.errors.ProblemCollector;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.features.FeatureSchema;
import io.github.rkaushikdev.stemwright.domain.lexicon.Stem;
import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmProfile;
import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmProfileInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmSlot;
import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmSlotInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.SyncretismGroup;
import io.github.rkaushikdev.stemwright.domain.paradigm.SyncretismGroupInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.SyncretismMap;
import io.github.rkaushikdev.stemwright.domain.text.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A text format for paradigms, because the people who write them are linguists with a text editor
 * rather than programmers with a json schema.
 *
 * <pre>
 * profile first-declension
 * class   first-declension
 * label   First declension
 *
 * nom.sg  -a
 * gen.sg  -ae
 * nom.sg  = vis        a literal form rather than an ending
 * gen.sg  aorist -os   an ending on a named stem
 * acc.pl  -es|-is      an ending and the variants also accepted
 *
 * syncretism ae: gen.sg dat.sg nom.pl
 * </pre>
 *
 * <p>A line beginning with a hash is a comment, and so is the rest of any line from a hash on; a
 * blank line is nothing. Every failure names its line number, because the person fixing it is looking
 * at the file in an editor with the line numbers switched on.
 *
 * @param profile the profile the file declares
 * @param syncretism the syncretism groups it declares, {@link SyncretismMap#NONE} when it declares none
 */
public record ParadigmFile(ParadigmProfile profile, SyncretismMap syncretism) {

    /** What separates an ending from the variants written after it. */
    private static final String VARIANT_BAR = "|";

    public ParadigmFile {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(syncretism, "syncretism");
    }

    /**
     * Reads a paradigm file against the schema its bundles are written in.
     *
     * <p>Every malformed slot or syncretism line and a missing {@code profile} or {@code class} line
     * are reported together, each slot and group problem under {@code line <n>}. The profile and the
     * groups are then checked by {@link ParadigmProfile#of} and {@link SyncretismMap#of}, which refuse
     * a bundle the schema does not admit.
     *
     * @throws ValidationException when the file is not usable
     */
    public static ParadigmFile parse(FeatureSchema schema, String text) {
        ProblemCollector problems = new ProblemCollector();
        Optional<String> key = Optional.empty();
        Optional<String> classKey = Optional.empty();
        Optional<String> label = Optional.empty();
        List<ParadigmSlotInput> slots = new ArrayList<>();
        List<SyncretismGroupInput> groups = new ArrayList<>();

        List<String> lines = Text.split(text, "\n");
        for (int index = 0; index < lines.size(); index += 1) {
            int line = index + 1;
            List<String> parts = splitLine(Text.split(lines.get(index), "#").get(0));
            if (parts.isEmpty()) {
                continue;
            }

            String path = "line " + line;
            switch (parts.get(0)) {
                case "profile" -> key = secondOf(parts);
                case "class" -> classKey = secondOf(parts);
                case "label" -> label = Optional.of(String.join(" ", parts.subList(1, parts.size())));
                case "syncretism" ->
                    problems.absorbFlat(path, () -> groupFrom(parts, line)).ifPresent(groups::add);
                default ->
                    problems.absorbFlat(path, () -> slotFrom(parts, line)).ifPresent(slots::add);
            }
        }

        problems.addIf(key.isEmpty(), "profile", "is missing, the file needs a profile line");
        problems.addIf(classKey.isEmpty(), "class", "is missing, the file needs a class line");
        problems.throwIfAny("the paradigm file is not usable");

        ParadigmProfileInput input = new ParadigmProfileInput(
                key.orElseThrow(), classKey.orElseThrow(), label.filter(written -> !written.isEmpty()), slots);
        return new ParadigmFile(
                ParadigmProfile.of(schema, input),
                groups.isEmpty() ? SyncretismMap.NONE : SyncretismMap.of(schema, groups));
    }

    /**
     * The file as {@link #parse} reads it: the header lines, a blank line, one slot per line in slot
     * order with each bundle as its canonical key, and the syncretism groups after another blank line
     * when there are any. The text ends with a line end.
     */
    public String write() {
        List<String> lines = new ArrayList<>(
                List.of("profile " + profile.key(), "class   " + profile.classKey(), "label   " + profile.label(), ""));

        for (ParadigmSlot slot : profile.slots().values()) {
            if (slot.literal().isPresent()) {
                lines.add(slot.bundle() + " = " + slot.literal().get());
                continue;
            }
            String stem = slot.stemRole().equals(Stem.DEFAULT_ROLE) ? "" : slot.stemRole() + " ";
            List<String> endings = new ArrayList<>(List.of(slot.affix()));
            endings.addAll(slot.variants());
            lines.add(slot.bundle() + " " + stem + "-" + String.join(VARIANT_BAR + "-", endings));
        }

        if (!syncretism.groups().isEmpty()) {
            lines.add("");
            for (SyncretismGroup group : syncretism.groups()) {
                lines.add("syncretism " + group.key() + ": " + String.join(" ", group.members()));
            }
        }

        return String.join("\n", lines) + "\n";
    }

    /** The words of a line, split at runs of spacing. */
    private static List<String> splitLine(String line) {
        return Arrays.stream(Text.SPACE_RUN.split(Text.trim(line)))
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** The word after the keyword, or nothing when the keyword stands alone. */
    private static Optional<String> secondOf(List<String> parts) {
        return parts.size() > 1 ? Optional.of(parts.get(1)) : Optional.empty();
    }

    /**
     * A slot line: a bundle, then an ending with or without its dash, a named stem and an ending, or
     * an equals sign and a literal form that runs to the end of the line. An ending may be followed by
     * the variants the slot also accepts, each after a bar.
     */
    private static ParadigmSlotInput slotFrom(List<String> parts, int line) {
        String bundle = parts.get(0);
        List<String> rest = parts.subList(1, parts.size());

        if (rest.isEmpty()) {
            throw ValidationException.field("line " + line, "gives a bundle with nothing after it");
        }
        if (rest.get(0).equals("=")) {
            String literal = String.join(" ", rest.subList(1, rest.size()));
            if (literal.isEmpty()) {
                throw ValidationException.field("line " + line, "has an equals sign with no form after it");
            }
            return ParadigmSlotInput.literal(bundle, literal);
        }
        ParadigmSlotInput.Builder slot = ParadigmSlotInput.builder(bundle);
        if (rest.size() > 1) {
            slot.stemRole(rest.get(0));
        }
        List<String> endings = Text.split(rest.get(rest.size() > 1 ? 1 : 0), VARIANT_BAR).stream()
                .map(ParadigmFile::withoutDash)
                .toList();
        return slot.affix(endings.getFirst(), endings.subList(1, endings.size()).toArray(String[]::new))
                .build();
    }

    /** A {@code syncretism <name>: <bundle> <bundle> ...} line, which needs two bundles at least. */
    private static SyncretismGroupInput groupFrom(List<String> parts, int line) {
        if (parts.size() < 2 || !parts.get(1).endsWith(":")) {
            throw ValidationException.field("line " + line, "needs a group name followed by a colon");
        }
        String head = parts.get(1);
        List<String> members = parts.subList(2, parts.size());
        if (members.size() < 2) {
            throw ValidationException.field("line " + line, "needs at least two bundles");
        }
        return SyncretismGroupInput.of(head.substring(0, head.length() - 1), members);
    }

    private static String withoutDash(String affix) {
        return affix.startsWith("-") ? affix.substring(1) : affix;
    }
}
