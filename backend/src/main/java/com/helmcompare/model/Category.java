package com.helmcompare.model;

/** Logical configuration area a configuration item belongs to. */
public enum Category {
    ENVIRONMENT("Environment Variables"),
    SECRETS("Secrets"),
    PROBES("Health Probes"),
    TSC("TSC / Topology Spread"),
    RESOURCES("Resources"),
    VOLUMES("Volumes"),
    SERVICES("Services & Networking"),
    CONFIG("Configuration"),
    WORKLOAD("Image & Workload"),
    SCALING("Scaling"),
    SECURITY_CONTEXT("Security Context"),
    SCHEDULING("Scheduling"),
    METADATA("Labels & Annotations"),
    OTHER("Other");

    public final String label;

    Category(String label) {
        this.label = label;
    }
}
