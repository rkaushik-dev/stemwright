package io.github.rkaushikdev.stemwright.domain.paradigm;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.domain.features.FeatureSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The form a lexeme takes in one slot, with everything needed to say why: the stem and the affix
 * it was built from and the join rules that fired, or the fact that the profile stated it outright.
 *
 * @param bundle the canonical bundle key
 * @param form the realised form, which is the expected answer
 * @param stemRole the role of the stem used, which may be the base stem a missing role fell back to
 * @param stem the stem form used, empty for a literal
 * @param affix the affix attached, empty for a literal
 * @param fromLiteral whether the profile stated the form outright
 * @param steps the join rules that fired
 * @param variants the other forms the slot accepts, each its variant ending joined to the same stem
 *     by the same rules, in the order the endings were written
 */
public record RealisedCell(
        String bundle,
        String form,
        String stemRole,
        String stem,
        String affix,
        boolean fromLiteral,
        List<JoinStep> steps,
        List<String> variants) {

    public RealisedCell {
        steps = List.copyOf(steps);
        variants = List.copyOf(variants);
    }

    /** The form and then every variant, which is every answer the cell accepts. */
    List<String> acceptedForms() {
        List<String> forms = new ArrayList<>(variants.size() + 1);
        forms.add(form);
        forms.addAll(variants);
        return List.copyOf(forms);
    }

    /**
     * The sentence an instructor is shown when a learner asks why the expected answer is what it
     * is, such as {@code reg plus sis gives rexis in latin after g-before-s}, naming the variants a
     * slot also accepts after it.
     *
     * @throws ValidationException when the bundle is not the one this cell was realised for
     */
    public String explain(FeatureSchema schema, FeatureBundle bundle) {
        if (!bundle.key().equals(this.bundle)) {
            throw ValidationException.field("bundle", "does not match the realised cell");
        }
        if (fromLiteral) {
            return form + " is stated outright by the profile";
        }
        String seam = stem + " plus " + (affix.isEmpty() ? "no ending" : affix);
        String also = variants.isEmpty() ? "" : ", and also " + String.join(" or ", variants);
        if (steps.isEmpty()) {
            return seam + " gives " + form + " in " + schema.language() + also;
        }
        String applied = steps.stream().map(JoinStep::rule).collect(Collectors.joining(", "));
        return seam + " gives " + form + " in " + schema.language() + " after " + applied + also;
    }
}
