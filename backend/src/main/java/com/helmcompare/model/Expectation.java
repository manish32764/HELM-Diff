package com.helmcompare.model;

/**
 * A PROD characteristic derived from a historical Difference Set (or a built-in policy)
 * that can be evaluated against any chart in the portfolio.
 */
public class Expectation {
    public String id;
    public String title;
    public Category category;
    /** SECURE_VARIABLE, BLOCK_PRESENT, ITEM_PRESENT, VALUE, BLOCK_CHANGED, ITEM_REMOVED, POLICY_NO_PLAIN_SECRETS */
    public String kind;
    public String key;
    public String subject;
    public String family;
    /** Expected source class for SECURE_VARIABLE (e.g. AKEYLESS). */
    public String targetSourceClass;
    public String expectedCanonical;
    public String originCanonical;
    public String expectedDisplay;
    /** ALWAYS (every chart must satisfy) or IF_PRESENT (only charts containing the configuration). */
    public String applicability = "ALWAYS";
    public boolean selected = true;
    public String rationale;
}
