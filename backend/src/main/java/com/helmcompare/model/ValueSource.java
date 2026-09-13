package com.helmcompare.model;

/** How a configuration item obtains its value. */
public enum ValueSource {
    LITERAL("Plain text", SourceClass.PLAIN),
    EMPTY("Empty", SourceClass.PLAIN),
    TEMPLATE("Helm template", SourceClass.TEMPLATE),
    SECRET_REF("K8s Secret", SourceClass.SECRET),
    AKEYLESS("AKeyless", SourceClass.AKEYLESS),
    EXTERNAL_SECRET("External secret", SourceClass.SECRET),
    VAULT("Vault", SourceClass.SECRET),
    CONFIGMAP_REF("ConfigMap", SourceClass.CONFIGMAP),
    FIELD_REF("Field ref", SourceClass.FIELD),
    BLOCK("Block", SourceClass.BLOCK);

    public final String label;
    public final SourceClass sourceClass;

    ValueSource(String label, SourceClass sourceClass) {
        this.label = label;
        this.sourceClass = sourceClass;
    }

    public boolean isSecretMechanism() {
        return sourceClass == SourceClass.SECRET || sourceClass == SourceClass.AKEYLESS;
    }

    public enum SourceClass { PLAIN, TEMPLATE, SECRET, AKEYLESS, CONFIGMAP, FIELD, BLOCK }
}
