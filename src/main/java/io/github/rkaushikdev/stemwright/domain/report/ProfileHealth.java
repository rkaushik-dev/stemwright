package io.github.rkaushikdev.stemwright.domain.report;

import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmProfile;
import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmSlot;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileRevision;
import io.github.rkaushikdev.stemwright.domain.paradigm.RevisionHistory;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The health checks over the profile book's revisions, which {@link Health#check} runs after the
 * classes. Every finding is in the {@code profiles} area.
 *
 * <p>A profile with no revision at all is an error, and one with nothing in force yet is a warning
 * naming when the first starts. For the revision in force, a pair it declares one form that its
 * endings build differently is a warning. And a revision that takes effect in the next four weeks
 * is a note saying what it changes, because from that morning every answer is marked against it and
 * an instructor would rather tell the class on Friday than explain on Monday.
 */
final class ProfileHealth {

    private static final String PROFILES = "profiles";

    /** How far ahead an upcoming revision is announced. */
    private static final int NOTICE_DAYS = 28;

    private ProfileHealth() {}

    static List<Finding> check(HealthInput input) {
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, RevisionHistory> entry :
                input.context().book().histories().entrySet()) {
            String profileKey = entry.getKey();
            List<ProfileRevision> revisions = entry.getValue().revisions();
            if (revisions.isEmpty()) {
                findings.add(new Finding(FindingLevel.ERROR, PROFILES, profileKey + " holds no revisions at all"));
                continue;
            }
            Optional<ProfileRevision> inForce = entry.getValue().revisionInForce(input.on());
            if (inForce.isEmpty()) {
                findings.add(new Finding(
                        FindingLevel.WARNING,
                        PROFILES,
                        profileKey + " has nothing in force on " + input.on() + ", the first starts "
                                + revisions.getFirst().effectiveFrom()));
                continue;
            }
            checkSyncretism(profileKey, inForce.get(), findings);
            upcoming(revisions, input.on()).ifPresent(next -> announce(profileKey, inForce.get(), next, findings));
        }
        return List.copyOf(findings);
    }

    /**
     * A pair the revision declares one form and its endings build differently is a warning, because
     * burial hides the second cell once the first is answered and the learner never practises an
     * ending that is not in fact the same. A pair the endings happen to build alike without being
     * declared is left alone: asking it twice costs a question and hides nothing.
     */
    private static void checkSyncretism(String profileKey, ProfileRevision revision, List<Finding> findings) {
        List<String> unbuilt = revision.syncretism().reconcileWith(revision.profile()).stream()
                .filter(pair -> pair.declared() && !pair.produced())
                .map(pair -> pair.first() + " and " + pair.second())
                .toList();
        if (!unbuilt.isEmpty()) {
            findings.add(new Finding(
                    FindingLevel.WARNING,
                    PROFILES,
                    profileKey + " revision " + revision.ordinal() + " declares " + String.join(", ", unbuilt)
                            + " one form, but its endings build them differently"));
        }
    }

    /** The first revision after the day that takes effect within the notice window. */
    private static Optional<ProfileRevision> upcoming(List<ProfileRevision> revisions, LocalDate on) {
        LocalDate horizon = CivilDates.addDays(on, NOTICE_DAYS);
        return revisions.stream()
                .filter(revision -> revision.effectiveFrom().isAfter(on)
                        && !revision.effectiveFrom().isAfter(horizon))
                .findFirst();
    }

    /**
     * Says what the next revision changes against the one in force: the slots whose recipe differs,
     * counting a slot added or taken away, and the bundles whose twins differ.
     */
    private static void announce(
            String profileKey, ProfileRevision current, ProfileRevision next, List<Finding> findings) {
        Set<String> bundles = new LinkedHashSet<>(current.profile().slotBundles());
        bundles.addAll(next.profile().slotBundles());
        int endings = 0;
        int twins = 0;
        for (String bundle : bundles) {
            if (!Objects.equals(recipeOf(current.profile(), bundle), recipeOf(next.profile(), bundle))) {
                endings += 1;
            }
            if (!Set.copyOf(current.syncretism().twinsOf(bundle))
                    .equals(Set.copyOf(next.syncretism().twinsOf(bundle)))) {
                twins += 1;
            }
        }
        findings.add(new Finding(
                FindingLevel.NOTE,
                PROFILES,
                profileKey + " revision " + next.ordinal() + " takes effect on " + next.effectiveFrom() + ", changing "
                        + endings + " endings and the twins of " + twins + " bundles"));
    }

    /** What a slot builds, ignoring its note, or nothing when the profile has no slot for the bundle. */
    private static Optional<List<Object>> recipeOf(ParadigmProfile profile, String bundle) {
        return Optional.ofNullable(profile.slots().get(bundle))
                .map((ParadigmSlot slot) -> List.of(slot.stemRole(), slot.affix(), slot.literal(), slot.variants()));
    }
}
