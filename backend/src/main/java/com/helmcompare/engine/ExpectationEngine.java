package com.helmcompare.engine;

import com.helmcompare.model.Category;
import com.helmcompare.model.ConfigItem;
import com.helmcompare.model.DiffEntry;
import com.helmcompare.model.DiffEntry.Status;
import com.helmcompare.model.DiffResult;
import com.helmcompare.model.Expectation;
import com.helmcompare.model.PortfolioResult.ExpectationResult;
import com.helmcompare.model.ValueSource;
import com.helmcompare.model.ValueSource.SourceClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Derives portable PROD expectations from a Difference Set and evaluates them against any chart. */
public final class ExpectationEngine {

    public static final String POLICY_NO_PLAIN_SECRETS = "policy-no-plain-secrets";

    private static final Set<Category> SELECTED_BY_DEFAULT = Set.of(Category.SECRETS, Category.PROBES, Category.TSC,
            Category.ENVIRONMENT, Category.RESOURCES, Category.SCALING, Category.VOLUMES, Category.SECURITY_CONTEXT);

    private ExpectationEngine() {
    }

    public static List<Expectation> derive(DiffResult diff) {
        List<Expectation> out = new ArrayList<>();
        for (DiffEntry e : diff.entries) {
            if (e.status == Status.COMMON) continue;
            Expectation x = new Expectation();
            x.id = "exp-" + e.id;
            x.category = e.category;
            x.key = e.key;
            x.subject = e.subject;
            x.family = e.family;
            x.rationale = e.summary;
            // Annotations are usually an implementation detail of a secret mechanism, not a portable PROD rule.
            x.selected = SELECTED_BY_DEFAULT.contains(e.category) && !"metadata".equals(e.family);
            ConfigItem r = e.right;
            ConfigItem l = e.left;

            if (e.status == Status.CHANGED && "SECRET_MECHANISM_CHANGED".equals(e.intent) && Similarity.isSecretClass(r)) {
                x.kind = "SECURE_VARIABLE";
                x.targetSourceClass = r.source.sourceClass.name();
                x.applicability = "IF_PRESENT";
                x.title = e.subject + " must use " + r.source.label;
                x.expectedDisplay = r.source.label;
            } else if (e.status == Status.ADDED && r.block) {
                x.kind = "BLOCK_PRESENT";
                x.title = e.subject + " must be present";
                x.expectedCanonical = r.canonical;
                x.expectedDisplay = r.display;
            } else if (e.status == Status.ADDED) {
                x.kind = "ITEM_PRESENT";
                x.title = e.subject + " must be present" + (Similarity.isSecretClass(r) ? " (" + r.source.label + ")" : "");
                x.expectedCanonical = r.canonical;
                x.expectedDisplay = r.display;
            } else if (e.status == Status.REMOVED) {
                x.kind = "ITEM_REMOVED";
                x.applicability = "IF_PRESENT";
                x.title = e.subject + " should not be present";
                x.originCanonical = l.canonical;
                x.selected = x.selected && !"variable".equals(e.family);
            } else if (l.enabled != r.enabled && r.enabled) {
                x.kind = "BLOCK_PRESENT";
                x.title = e.subject + " must be enabled";
                x.expectedCanonical = r.canonical;
                x.expectedDisplay = r.display;
            } else if (r.block || l.block) {
                x.kind = "BLOCK_CHANGED";
                x.applicability = "IF_PRESENT";
                x.title = e.subject + " should use PROD settings";
                x.expectedCanonical = r.canonical;
                x.originCanonical = l.canonical;
                x.expectedDisplay = r.display;
            } else {
                x.kind = "VALUE";
                x.applicability = "IF_PRESENT";
                x.title = e.subject + " should have a PROD-specific value";
                x.expectedCanonical = r.canonical;
                x.originCanonical = l.canonical;
                x.expectedDisplay = r.display;
            }
            out.add(x);
        }
        out.add(noPlainSecretsPolicy());
        return out;
    }

    public static Expectation noPlainSecretsPolicy() {
        Expectation p = new Expectation();
        p.id = POLICY_NO_PLAIN_SECRETS;
        p.kind = "POLICY_NO_PLAIN_SECRETS";
        p.category = Category.SECRETS;
        p.title = "No sensitive values stored as plain text";
        p.rationale = "Built-in policy: every sensitive variable or secret must use a secret mechanism in PROD.";
        p.applicability = "ALWAYS";
        p.selected = true;
        return p;
    }

    /**
     * @param target      chart being evaluated (PROD when available, otherwise NON-PROD)
     * @param nonProd     NON-PROD chart of the same application, used to recognise PROD-specific values
     * @param targetIsProd true for validation of an existing PROD chart
     */
    public static ExpectationResult evaluate(Expectation x, ItemIndex target, ItemIndex nonProd, boolean targetIsProd) {
        ExpectationResult r = new ExpectationResult();
        r.expectationId = x.id;
        switch (x.kind) {
            case "POLICY_NO_PLAIN_SECRETS" -> {
                List<ConfigItem> sensitive = target.all().stream().filter(i -> i.sensitive).toList();
                List<ConfigItem> plain = sensitive.stream().filter(i -> i.source == ValueSource.LITERAL).toList();
                if (sensitive.isEmpty()) {
                    result(r, "NOT_APPLICABLE", "ABSENT", "No sensitive configuration found.", null);
                } else if (plain.isEmpty()) {
                    result(r, "COMPLIANT", "SAME", "All " + sensitive.size() + " sensitive value(s) use a secret mechanism or are not plain text.", null);
                } else {
                    String names = String.join(", ", plain.stream().map(i -> i.subject).distinct().limit(6).toList());
                    result(r, targetIsProd ? "REQUIRES_CHANGE" : "REQUIRES_CHANGE", "DIFFERENT",
                            plain.size() + " plain-text sensitive value(s): " + names + (plain.size() > 6 ? ", …" : ""), plain.get(0));
                }
            }
            case "SECURE_VARIABLE" -> {
                ItemIndex.Found f = target.find(x.key, x.family, x.subject);
                if (f == null) {
                    result(r, "NOT_APPLICABLE", "ABSENT", x.subject + " is not present.", null);
                    break;
                }
                ConfigItem item = f.item();
                SourceClass expected = SourceClass.valueOf(x.targetSourceClass);
                SourceClass actual = Similarity.sourceClass(item);
                if (!f.exact()) {
                    result(r, "REVIEW", "SIMILAR", String.format("Similar variable %s (%d%%) uses %s.",
                            item.subject, Math.round(f.similarity() * 100), item.source.label), item);
                } else if (actual == expected) {
                    result(r, "COMPLIANT", "SAME", "Uses " + item.source.label + ".", item);
                } else if (actual == SourceClass.PLAIN || actual == SourceClass.TEMPLATE || actual == SourceClass.CONFIGMAP) {
                    result(r, "REQUIRES_CHANGE", "DIFFERENT", "Uses " + item.source.label + " — requires conversion to "
                            + x.expectedDisplay + ".", item);
                } else {
                    result(r, "REVIEW", "SIMILAR", "Secured via " + item.source.label + " instead of " + x.expectedDisplay
                            + " (inconsistent implementation).", item);
                }
            }
            case "BLOCK_PRESENT" -> {
                ConfigItem item = byBaseKey(target, x.key);
                if (item == null) {
                    result(r, "REQUIRES_CHANGE", "ABSENT", x.subject + " is missing.", null);
                } else if (!item.enabled) {
                    result(r, "REQUIRES_CHANGE", "DIFFERENT", x.subject + " is present but disabled.", item);
                } else if (Objects.equals(item.canonical, x.expectedCanonical)) {
                    result(r, "COMPLIANT", "SAME", "Present with the same settings.", item);
                } else {
                    result(r, "COMPLIANT", "SIMILAR", "Present with different settings: " + item.display, item);
                }
            }
            case "ITEM_PRESENT" -> {
                ItemIndex.Found f = target.find(x.key, x.family, x.subject);
                if (f == null) {
                    if ("ALWAYS".equals(x.applicability)) result(r, "REQUIRES_CHANGE", "ABSENT", x.subject + " is missing.", null);
                    else result(r, "NOT_APPLICABLE", "ABSENT", x.subject + " is not present.", null);
                } else if (!f.exact()) {
                    result(r, "REVIEW", "SIMILAR", String.format("Similar configuration %s (%d%%) found.",
                            f.item().subject, Math.round(f.similarity() * 100)), f.item());
                } else if (Objects.equals(f.item().canonical, x.expectedCanonical)) {
                    result(r, "COMPLIANT", "SAME", "Present with the same value.", f.item());
                } else {
                    result(r, "COMPLIANT", "SIMILAR", "Present (" + f.item().display + ").", f.item());
                }
            }
            case "ITEM_REMOVED" -> {
                ConfigItem item = byBaseKey(target, x.key);
                if (item == null) {
                    result(r, "COMPLIANT", "ABSENT", x.subject + " is not present.", null);
                } else if (Objects.equals(item.canonical, x.originCanonical)) {
                    result(r, "REQUIRES_CHANGE", "SAME", x.subject + " is still present with the pre-PROD configuration.", item);
                } else {
                    result(r, "REVIEW", "DIFFERENT", x.subject + " is present (" + item.display + "); historically removed for PROD.", item);
                }
            }
            case "VALUE", "BLOCK_CHANGED" -> {
                ConfigItem item = byBaseKey(target, x.key);
                if (item == null) {
                    result(r, "NOT_APPLICABLE", "ABSENT", x.subject + " is not present.", null);
                } else if (Objects.equals(item.canonical, x.expectedCanonical)) {
                    result(r, "COMPLIANT", "SAME", "Matches the historical PROD configuration.", item);
                } else if (targetIsProd && nonProd != null && nonProd.get(item.key) != null) {
                    ConfigItem np = nonProd.get(item.key);
                    if (Similarity.compareStates(item, np) != Similarity.Match.SAME) {
                        result(r, "COMPLIANT", "SIMILAR", "PROD-specific configuration set (" + item.display + ").", item);
                    } else {
                        result(r, "REQUIRES_CHANGE", "DIFFERENT", "Same as NON-PROD (" + item.display + "); PROD configuration expected.", item);
                    }
                } else if (Objects.equals(item.canonical, x.originCanonical)) {
                    result(r, "REQUIRES_CHANGE", "DIFFERENT", targetIsProd
                            ? "Still has the pre-PROD configuration (" + item.display + ")."
                            : "PROD configuration required (historical PROD: " + x.expectedDisplay + ").", item);
                } else {
                    result(r, "REVIEW", "DIFFERENT", "Configuration differs from both historical charts (" + item.display + ").", item);
                }
            }
            default -> result(r, "REVIEW", "DIFFERENT", "Unknown expectation kind " + x.kind, null);
        }
        return r;
    }

    private static ConfigItem byBaseKey(ItemIndex index, String key) {
        ConfigItem exact = index.get(key);
        if (exact != null) return exact;
        return index.all().stream().filter(i -> i.key.startsWith(key + "@")).findFirst().orElse(null);
    }

    private static void result(ExpectationResult r, String status, String match, String detail, ConfigItem found) {
        r.status = status;
        r.match = match;
        r.detail = detail;
        r.found = found;
    }
}
