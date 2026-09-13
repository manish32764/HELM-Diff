package com.helmcompare.engine;

import com.helmcompare.engine.Similarity.Match;
import com.helmcompare.model.Category;
import com.helmcompare.model.ChartRecord;
import com.helmcompare.model.ChartRef;
import com.helmcompare.model.ConfigItem;
import com.helmcompare.model.DiffEntry;
import com.helmcompare.model.DiffEntry.Status;
import com.helmcompare.model.DiffResult;
import com.helmcompare.model.ValueSource;
import com.helmcompare.model.ValueSource.SourceClass;
import com.helmcompare.model.VersionAnalysisResult;
import com.helmcompare.model.VersionAnalysisResult.Assessment;
import com.helmcompare.model.VersionAnalysisResult.Cell;
import com.helmcompare.model.VersionAnalysisResult.CorrelatedChange;
import com.helmcompare.model.VersionAnalysisResult.MatrixRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Historical PROD behaviour (A → B) + new-version changes (A → C) + current PROD implementation (C → D)
 * → assessment. Never assumes a historical difference is automatically required in the new version.
 */
public final class VersionAnalysisEngine {

    private VersionAnalysisEngine() {
    }

    public record Input(ChartRecord chart, List<ConfigItem> items) {
    }

    public static VersionAnalysisResult analyze(Input a, Input b, Input c, Input d) {
        VersionAnalysisResult r = new VersionAnalysisResult();
        r.a = ChartRef.of("A", a.chart());
        r.b = ChartRef.of("B", b.chart());
        r.c = ChartRef.of("C", c.chart());
        r.d = d == null ? null : ChartRef.of("D", d.chart());
        r.validation = d != null;

        r.historicalDiff = PairwiseDiffEngine.diff(a.chart(), a.items(), "A", b.chart(), b.items(), "B");
        if (d != null) r.currentDiff = PairwiseDiffEngine.diff(c.chart(), c.items(), "C", d.chart(), d.items(), "D");

        ItemIndex ia = new ItemIndex(a.items());
        ItemIndex ib = new ItemIndex(b.items());
        ItemIndex ic = new ItemIndex(c.items());
        ItemIndex id = d == null ? null : new ItemIndex(d.items());

        List<DiffEntry> historical = changes(r.historicalDiff);
        if (d != null) r.correlations = correlate(historical, changes(r.currentDiff), ia, ib, ic, id);
        r.nonProdAssessment = assessNonProd(historical, ic);
        r.matrix = matrix(ia, ib, ic, id, r.correlations, r.nonProdAssessment);
        summarize(r, historical.size());
        return r;
    }

    private static List<DiffEntry> changes(DiffResult diff) {
        return diff.entries.stream().filter(e -> e.status != Status.COMMON).toList();
    }

    // ───────────────────────────── Mode 2: diff ↔ diff ─────────────────────────────

    private static List<CorrelatedChange> correlate(List<DiffEntry> historical, List<DiffEntry> current,
                                                    ItemIndex ia, ItemIndex ib, ItemIndex ic, ItemIndex id) {
        List<CorrelatedChange> out = new ArrayList<>();
        Set<String> used = new HashSet<>();

        for (DiffEntry h : historical) {
            CorrelatedChange cc = new CorrelatedChange();
            cc.id = "cc-" + h.id;
            cc.key = h.key;
            cc.subject = h.subject;
            cc.category = h.category;
            cc.historical = h;
            cc.stateA = h.left;
            cc.stateB = h.right;
            ConfigItem ref = h.right != null ? h.right : h.left;
            cc.stateC = item(ic.find(ref));
            cc.stateD = item(id.find(ref));

            MatchedEntry m = findCurrent(h, current, used);
            if (m != null) {
                DiffEntry cur = m.entry();
                used.add(cur.id);
                cc.current = cur;
                cc.stateC = cur.left;
                cc.stateD = cur.right;
                boolean sameIntent = Objects.equals(h.intent, cur.intent) && Objects.equals(h.transition, cur.transition);
                boolean sameFamily = intentFamily(h).equals(intentFamily(cur));
                Match result = Similarity.compareStates(h.right, cur.right);
                boolean blockChange = (h.right != null && h.right.block) || (cur.right != null && cur.right.block);

                SourceClass historicalFrom = Similarity.sourceClass(h.left);
                boolean transitionNotApplied = h.left != null && h.right != null
                        && historicalFrom != Similarity.sourceClass(h.right)
                        && cur.right != null && Similarity.sourceClass(cur.right) == historicalFrom;
                boolean alreadyInBaseline = h.status == Status.ADDED && cur.status == Status.CHANGED
                        && cur.left != null && cur.right != null && cur.right.enabled
                        && Similarity.compareStates(h.right, cur.right) != Match.DIFFERENT;

                if (transitionNotApplied) {
                    set(cc, "MISSING", "DIFFERENT", "MISSING",
                            "Historical PROD changed " + h.subject + " from " + h.left.source.label + " to " + h.right.source.label
                                    + ", but the new PROD chart still uses " + cur.right.source.label + " (" + cur.summary + ").");
                } else if (alreadyInBaseline) {
                    cc.implementationDifferences = Similarity.fieldChanges(h.right, cur.right);
                    set(cc, "ALREADY_IN_BASELINE", "SIMILAR", "IMPLEMENTED",
                            h.subject + " is already part of the new NON-PROD chart; the new PROD chart adjusts it (" + cur.summary + ").");
                } else if (sameIntent && result == Match.SAME) {
                    set(cc, "CARRIED_FORWARD", "SAME", "IMPLEMENTED",
                            "The same PROD change was made in both versions.");
                } else if (sameIntent && !blockChange) {
                    set(cc, "CARRIED_FORWARD", "SIMILAR", "IMPLEMENTED",
                            "Equivalent PROD change carried forward with a different value or reference ("
                                    + display(h.right) + " → " + display(cur.right) + ").");
                } else if (sameIntent) {
                    cc.implementationDifferences = Similarity.fieldChanges(h.right, cur.right);
                    set(cc, "CHANGED_IMPLEMENTATION", "SIMILAR", "IMPLEMENTED_DIFFERENTLY",
                            "Same kind of PROD change, but " + cc.implementationDifferences.size()
                                    + " setting(s) differ from the historical implementation.");
                } else if (sameFamily) {
                    cc.implementationDifferences = Similarity.fieldChanges(h.right, cur.right);
                    set(cc, "CHANGED_IMPLEMENTATION", "SIMILAR", "IMPLEMENTED_DIFFERENTLY",
                            "Similar purpose, different implementation. Historical: " + h.summary + ". Current: " + cur.summary + ".");
                } else {
                    set(cc, "CHANGED_IMPLEMENTATION", "DIFFERENT", "IMPLEMENTED_DIFFERENTLY",
                            "The same configuration was changed differently. Historical: " + h.summary + ". Current: " + cur.summary + ".");
                }
                if (!m.exact()) {
                    if ("SAME".equals(cc.similarity)) cc.similarity = "SIMILAR";
                    cc.explanation += String.format(" Matched by name similarity (%d%%).", Math.round(m.score() * 100));
                    cc.review = true;
                }
            } else {
                classifyWithoutCurrentChange(h, cc);
            }
            cc.security = h.security || (cc.current != null && cc.current.security);
            cc.review = cc.review || switch (cc.classification) {
                case "MISSING", "NO_LONGER_APPLICABLE" -> true;
                case "CHANGED_IMPLEMENTATION" -> !"SIMILAR".equals(cc.similarity) || !cc.implementationDifferences.isEmpty();
                default -> false;
            };
            out.add(cc);
        }

        for (DiffEntry cur : current) {
            if (used.contains(cur.id)) continue;
            CorrelatedChange cc = new CorrelatedChange();
            cc.id = "cc-new-" + cur.id;
            cc.key = cur.key;
            cc.subject = cur.subject;
            cc.category = cur.category;
            cc.current = cur;
            ConfigItem ref = cur.right != null ? cur.right : cur.left;
            cc.stateA = item(ia.find(ref));
            cc.stateB = item(ib.find(ref));
            cc.stateC = cur.left;
            cc.stateD = cur.right;
            cc.similarity = "UNDETERMINED";
            cc.classification = "NEW_PROD_CHANGE";
            cc.security = cur.security;

            boolean unexpected = isRegression(cur);
            if (unexpected) {
                cc.validation = "UNEXPECTED";
                cc.review = true;
                cc.explanation = "Unexpected PROD change in the new version: " + cur.summary + ".";
            } else if (Similarity.compareStates(cc.stateB, cur.right) == Match.SAME
                    && Similarity.compareStates(cc.stateA, cc.stateB) == Match.SAME) {
                cc.validation = "NEW_CHANGE";
                cc.explanation = "Matches the historical PROD configuration; the new NON-PROD chart differs. " + cur.summary + ".";
            } else {
                cc.validation = "NEW_CHANGE";
                cc.review = cur.review || cur.security;
                cc.explanation = "New PROD change not seen historically: " + cur.summary + ".";
            }
            out.add(cc);
        }
        out.sort(Comparator.comparingInt((CorrelatedChange x) -> classificationOrder(x.classification))
                .thenComparing(x -> x.category.ordinal())
                .thenComparing(x -> x.subject == null ? "" : x.subject));
        return out;
    }

    private static void classifyWithoutCurrentChange(DiffEntry h, CorrelatedChange cc) {
        ConfigItem c = cc.stateC;
        ConfigItem d = cc.stateD;
        if (h.status == Status.REMOVED) {
            if (d == null) {
                set(cc, "ALREADY_IN_BASELINE", "SAME", "IMPLEMENTED",
                        h.subject + " was removed for historical PROD and is absent from the new version.");
            } else {
                set(cc, "MISSING", "DIFFERENT", "MISSING",
                        "Historical PROD removed " + h.subject + ", but the new PROD chart still has it (" + display(d) + ").");
            }
            return;
        }
        Match m = Similarity.compareStates(h.right, d);
        if (m == Match.SAME) {
            set(cc, "ALREADY_IN_BASELINE", "SAME", "IMPLEMENTED",
                    "Already part of the new NON-PROD chart and kept in the new PROD chart.");
        } else if (m == Match.SIMILAR) {
            cc.implementationDifferences = Similarity.fieldChanges(h.right, d);
            set(cc, "ALREADY_IN_BASELINE", "SIMILAR", "IMPLEMENTED",
                    "Equivalent configuration already present in both new charts" + (d.block ? " with different settings." : "."));
        } else if (c == null && d == null) {
            set(cc, "NO_LONGER_APPLICABLE", "UNDETERMINED", "NO_LONGER_PRESENT",
                    h.subject + " no longer exists in the new version. Confirm whether the historical PROD change still applies.");
        } else {
            String detail = switch (h.status) {
                case ADDED -> h.subject + " was added for historical PROD but is absent from the new PROD chart.";
                default -> "Historical PROD changed " + h.summary + ", but the new PROD chart has " + display(d) + ".";
            };
            if ("VALUE_CHANGED".equals(h.intent) && c != null && Similarity.compareStates(c, h.left) != Match.SAME) {
                detail += " Note: the NON-PROD value also changed between versions.";
            }
            set(cc, "MISSING", "DIFFERENT", "MISSING", detail);
        }
    }

    private record MatchedEntry(DiffEntry entry, boolean exact, double score) {
    }

    private static MatchedEntry findCurrent(DiffEntry h, List<DiffEntry> current, Set<String> used) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(h.key);
        if (h.left != null) keys.add(h.left.key);
        if (h.right != null) keys.add(h.right.key);
        for (DiffEntry cur : current) {
            if (used.contains(cur.id)) continue;
            if (keys.contains(cur.key) || (cur.left != null && keys.contains(cur.left.key))
                    || (cur.right != null && keys.contains(cur.right.key))) {
                return new MatchedEntry(cur, true, 1);
            }
        }
        if (!"variable".equals(h.family)) return null;
        String subject = Similarity.normalize(h.subject);
        for (DiffEntry cur : current) {
            if (!used.contains(cur.id) && "variable".equals(cur.family) && Similarity.normalize(cur.subject).equals(subject)) {
                return new MatchedEntry(cur, true, 1);
            }
        }
        MatchedEntry best = null;
        for (DiffEntry cur : current) {
            if (used.contains(cur.id) || !"variable".equals(cur.family)) continue;
            double score = Similarity.nameSimilarity(h.subject, cur.subject);
            if (score >= Similarity.NAME_THRESHOLD && (best == null || score > best.score())
                    && intentFamily(h).equals(intentFamily(cur))) {
                best = new MatchedEntry(cur, false, score);
            }
        }
        return best;
    }

    private static boolean isRegression(DiffEntry cur) {
        if ("SECRET_MECHANISM_CHANGED".equals(cur.intent) && Similarity.sourceClass(cur.right) == SourceClass.PLAIN) return true;
        if (cur.status == Status.REMOVED && ("probe".equals(cur.family) || "tsc".equals(cur.family))) return true;
        if (cur.intent != null && cur.intent.endsWith("_DISABLED")) return true;
        return cur.right != null && cur.right.sensitive && cur.right.source == ValueSource.LITERAL;
    }

    static String intentFamily(DiffEntry e) {
        String intent = e.intent == null ? "" : e.intent;
        if ("SECRET_MECHANISM_CHANGED".equals(intent)) {
            return Similarity.sourceClass(e.right) == SourceClass.PLAIN ? "UNSECURE" : "SECURE";
        }
        if (intent.startsWith("PROBE")) return "PROBE";
        if (intent.startsWith("TSC")) return "TSC";
        if (intent.startsWith("SECRET")) return "SECRET";
        return switch (intent) {
            case "ADDED", "REMOVED" -> "PRESENCE:" + intent;
            case "VALUE_CHANGED", "REFERENCE_CHANGED", "SOURCE_CHANGED" -> "VALUE";
            default -> intent;
        };
    }

    // ───────────────────────────── NON-PROD assessment ─────────────────────────────

    private static List<Assessment> assessNonProd(List<DiffEntry> historical, ItemIndex ic) {
        List<Assessment> out = new ArrayList<>();
        for (DiffEntry h : historical) {
            Assessment as = new Assessment();
            as.id = "np-" + h.id;
            as.key = h.key;
            as.subject = h.subject;
            as.category = h.category;
            as.historical = h;
            as.security = h.security;
            ConfigItem ref = h.right != null ? h.right : h.left;
            ItemIndex.Found found = ic.find(ref);
            ConfigItem x = item(found);
            as.candidate = x;

            if (found != null && !found.exact()) {
                status(as, "REVIEW", String.format("A similar configuration %s exists (%d%% name similarity). Confirm whether the historical change applies.",
                        x.subject, Math.round(found.similarity() * 100)));
                out.add(as);
                continue;
            }
            switch (h.status) {
                case REMOVED -> {
                    if (x == null) status(as, "ALREADY_SATISFIED", "Not present in the new NON-PROD chart; nothing to remove.");
                    else if (Similarity.compareStates(x, h.left) == Match.SAME)
                        status(as, "REQUIRES_CHANGE", "Remove " + h.subject + " when preparing PROD (it was removed for historical PROD).");
                    else status(as, "REVIEW", h.subject + " exists with a different configuration; confirm whether it should be removed for PROD.");
                }
                case ADDED -> {
                    Match m = Similarity.compareStates(x, h.right);
                    if (x == null) status(as, "REQUIRES_CHANGE", "Add " + h.subject + " for PROD (historical PROD: " + display(h.right) + ").");
                    else if (m == Match.SAME) status(as, "ALREADY_SATISFIED", "Already present in the new NON-PROD chart.");
                    else if (m == Match.SIMILAR && !x.block) status(as, "ALREADY_SATISFIED", "Already present using " + x.source.label + ".");
                    else status(as, "DIFFERENT_IMPLEMENTATION", "Present with a different configuration: " + display(x)
                                + " (historical PROD: " + display(h.right) + ").");
                }
                default -> {
                    if (x == null) {
                        status(as, "POTENTIALLY_IRRELEVANT", h.subject + " is no longer present in the new NON-PROD chart; the historical PROD change may not apply.");
                    } else if (Similarity.compareStates(x, h.right) == Match.SAME) {
                        status(as, "ALREADY_SATISFIED", "The new NON-PROD chart already has the PROD configuration.");
                    } else if ("SECRET_MECHANISM_CHANGED".equals(h.intent) || "SOURCE_CHANGED".equals(h.intent)) {
                        SourceClass xc = Similarity.sourceClass(x);
                        if (xc == Similarity.sourceClass(h.right)) status(as, "ALREADY_SATISFIED", "Already uses " + x.source.label + ".");
                        else if (xc == Similarity.sourceClass(h.left))
                            status(as, "REQUIRES_CHANGE", "Convert " + h.subject + " from " + x.source.label + " to " + h.right.source.label + " for PROD.");
                        else status(as, "DIFFERENT_IMPLEMENTATION", "Uses " + x.source.label + " (historical PROD used " + h.right.source.label + ").");
                    } else if (Similarity.compareStates(x, h.left) == Match.SAME) {
                        String what = x.block ? "Apply PROD settings: " + PairwiseDiffEngine.describeFields(h.fieldChanges)
                                : "Set the PROD value (historical PROD: " + display(h.right) + ")";
                        status(as, "REQUIRES_CHANGE", what + ".");
                    } else if (x.block) {
                        status(as, "DIFFERENT_IMPLEMENTATION", h.subject + " differs from both historical charts; review the settings.");
                    } else {
                        status(as, "REVIEW", "The NON-PROD value changed between versions (" + display(h.left) + " → " + display(x)
                                + "); determine the PROD value (historical PROD: " + display(h.right) + ").");
                    }
                }
            }
            out.add(as);
        }
        out.sort(Comparator.comparingInt((Assessment x) -> assessmentOrder(x.status))
                .thenComparing(x -> x.category.ordinal()).thenComparing(x -> x.subject));
        return out;
    }

    // ───────────────────────────── four-chart matrix ─────────────────────────────

    private static List<MatrixRow> matrix(ItemIndex ia, ItemIndex ib, ItemIndex ic, ItemIndex id,
                                          List<CorrelatedChange> correlations, List<Assessment> assessments) {
        Map<String, CorrelatedChange> byKey = new HashMap<>();
        for (CorrelatedChange cc : correlations) {
            for (DiffEntry e : new DiffEntry[]{cc.historical, cc.current}) {
                if (e == null) continue;
                byKey.putIfAbsent(e.key, cc);
                if (e.left != null) byKey.putIfAbsent(e.left.key, cc);
                if (e.right != null) byKey.putIfAbsent(e.right.key, cc);
            }
        }
        Map<String, Assessment> assessmentByKey = new HashMap<>();
        for (Assessment as : assessments) {
            assessmentByKey.putIfAbsent(as.key, as);
            if (as.historical.left != null) assessmentByKey.putIfAbsent(as.historical.left.key, as);
        }

        Set<String> keys = new LinkedHashSet<>(ia.keys());
        keys.addAll(ib.keys());
        keys.addAll(ic.keys());
        if (id != null) keys.addAll(id.keys());

        List<MatrixRow> rows = new ArrayList<>();
        for (String key : keys) {
            ConfigItem a = ia.get(key);
            ConfigItem b = ib.get(key);
            ConfigItem c = ic.get(key);
            ConfigItem d = id == null ? null : id.get(key);
            ConfigItem any = firstNonNull(a, b, c, d);
            MatrixRow row = new MatrixRow();
            row.key = key;
            row.subject = any.subject;
            row.category = any.category;
            row.cells.add(cell(a));
            row.cells.add(cell(b));
            row.cells.add(cell(c));
            row.cells.add(id == null ? new Cell(false, "n/a", "No new PROD chart selected", null) : cell(d));

            boolean abSame = Similarity.compareStates(a, b) == Match.SAME;
            boolean bcSame = Similarity.compareStates(b, c) == Match.SAME;
            boolean cdSame = id == null || Similarity.compareStates(c, d) == Match.SAME;
            row.allEqual = abSame && bcSame && cdSame;

            if (id != null) {
                CorrelatedChange cc = byKey.get(key);
                if (cc != null) {
                    row.assessment = "ALREADY_IN_BASELINE".equals(cc.classification) ? "CONSISTENT" : cc.classification;
                    row.referenceId = cc.id;
                } else if (row.allEqual) {
                    row.assessment = "UNCHANGED";
                } else if (abSame && cdSame) {
                    row.assessment = "VERSION_CHANGE";
                } else {
                    row.assessment = "REVIEW";
                }
            } else {
                Assessment as = assessmentByKey.get(key);
                if (as != null) {
                    row.assessment = as.status;
                    row.referenceId = as.id;
                } else {
                    row.assessment = row.allEqual ? "UNCHANGED" : abSame ? "VERSION_CHANGE" : "REVIEW";
                }
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing((MatrixRow x) -> x.allEqual)
                .thenComparing(x -> x.category.ordinal())
                .thenComparing(x -> x.subject == null ? "" : x.subject));
        return rows;
    }

    static Cell cell(ConfigItem i) {
        if (i == null) return new Cell(false, "—", "Not present", null);
        String label;
        if (i.block) {
            label = i.enabled ? "Yes" : "Disabled";
        } else if ("variable".equals(i.family) || i.category == Category.SECRETS) {
            label = i.sensitive || i.source.sourceClass != SourceClass.PLAIN ? shortSource(i.source) : shorten(i.display, 28);
        } else {
            label = shorten(i.display, 28);
        }
        return new Cell(true, label, i.display, i.source);
    }

    private static String shortSource(ValueSource s) {
        return switch (s) {
            case LITERAL -> "Plain";
            case EMPTY -> "Empty";
            case TEMPLATE -> "Template";
            case SECRET_REF -> "K8s Secret";
            case AKEYLESS -> "AKeyless";
            case EXTERNAL_SECRET -> "External";
            case VAULT -> "Vault";
            case CONFIGMAP_REF -> "ConfigMap";
            case FIELD_REF -> "Field ref";
            case BLOCK -> "Yes";
        };
    }

    // ───────────────────────────── summary ─────────────────────────────

    private static void summarize(VersionAnalysisResult r, int historicalChanges) {
        VersionAnalysisResult.Summary s = r.summary;
        s.historicalChanges = historicalChanges;
        s.currentChanges = r.currentDiff == null ? 0 : r.currentDiff.summary.differences;
        for (CorrelatedChange cc : r.correlations) {
            switch (cc.classification) {
                case "CARRIED_FORWARD" -> s.carriedForward++;
                case "CHANGED_IMPLEMENTATION" -> s.changedImplementation++;
                case "MISSING" -> s.missing++;
                case "NEW_PROD_CHANGE" -> s.newProdChanges++;
                case "NO_LONGER_APPLICABLE" -> s.noLongerApplicable++;
                case "ALREADY_IN_BASELINE" -> s.alreadyInBaseline++;
                default -> {
                }
            }
            switch (cc.validation) {
                case "IMPLEMENTED" -> s.implemented++;
                case "IMPLEMENTED_DIFFERENTLY" -> s.implementedDifferently++;
                case "MISSING" -> s.validationMissing++;
                case "NO_LONGER_PRESENT" -> s.noLongerPresent++;
                case "NEW_CHANGE" -> s.newChanges++;
                case "UNEXPECTED" -> s.unexpected++;
                default -> {
                }
            }
            if (cc.security) s.security++;
            if (cc.review) s.review++;
        }
        for (Assessment as : r.nonProdAssessment) {
            switch (as.status) {
                case "ALREADY_SATISFIED" -> s.npAlreadySatisfied++;
                case "REQUIRES_CHANGE" -> s.npRequiresChange++;
                case "DIFFERENT_IMPLEMENTATION" -> s.npDifferentImplementation++;
                case "POTENTIALLY_IRRELEVANT" -> s.npPotentiallyIrrelevant++;
                default -> s.npReview++;
            }
        }
        if (!r.validation) {
            int total = r.nonProdAssessment.size();
            s.verdict = "PREPARATION";
            s.score = total == 0 ? 100 : s.npAlreadySatisfied * 100 / total;
            s.verdictMessage = s.npRequiresChange + " historical PROD change(s) should be considered when preparing the new PROD chart; "
                    + (s.npDifferentImplementation + s.npReview + s.npPotentiallyIrrelevant) + " need a decision.";
            return;
        }
        int expected = s.implemented + s.implementedDifferently + s.validationMissing;
        s.score = expected == 0 ? 100 : (s.implemented + s.implementedDifferently) * 100 / expected;
        if (s.validationMissing > 0 || s.unexpected > 0) {
            s.verdict = "INCONSISTENT";
            s.verdictMessage = "The new PROD chart is not consistent with the expected PROD configuration: "
                    + s.validationMissing + " expected change(s) missing, " + s.unexpected + " unexpected change(s).";
        } else if (s.review > 0) {
            s.verdict = "CONSISTENT_WITH_REVIEW";
            s.verdictMessage = "Expected PROD characteristics are present; " + s.review + " item(s) need human review.";
        } else {
            s.verdict = "CONSISTENT";
            s.verdictMessage = "The new PROD chart contains the expected PROD characteristics.";
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static void set(CorrelatedChange cc, String classification, String similarity, String validation, String explanation) {
        cc.classification = classification;
        cc.similarity = similarity;
        cc.validation = validation;
        cc.explanation = explanation;
    }

    private static void status(Assessment as, String status, String recommendation) {
        as.status = status;
        as.recommendation = recommendation;
    }

    private static ConfigItem item(ItemIndex.Found f) {
        return f == null ? null : f.item();
    }

    private static String display(ConfigItem i) {
        if (i == null) return "not present";
        return i.display == null || i.display.isEmpty() ? i.source.label : i.display;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static ConfigItem firstNonNull(ConfigItem... items) {
        for (ConfigItem i : items) if (i != null) return i;
        return null;
    }

    private static int classificationOrder(String c) {
        return switch (c) {
            case "MISSING" -> 0;
            case "CHANGED_IMPLEMENTATION" -> 1;
            case "NO_LONGER_APPLICABLE" -> 2;
            case "NEW_PROD_CHANGE" -> 3;
            case "CARRIED_FORWARD" -> 4;
            default -> 5;
        };
    }

    private static int assessmentOrder(String s) {
        return switch (s) {
            case "REQUIRES_CHANGE" -> 0;
            case "DIFFERENT_IMPLEMENTATION" -> 1;
            case "REVIEW" -> 2;
            case "POTENTIALLY_IRRELEVANT" -> 3;
            default -> 4;
        };
    }
}
