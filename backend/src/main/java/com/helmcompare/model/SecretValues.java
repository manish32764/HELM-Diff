package com.helmcompare.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual values of AKeyless paths uploaded for one folder comparison (path → value). */
public class SecretValues {
    public List<String> files = new ArrayList<>();
    public Instant updatedAt;
    public Map<String, String> values = new LinkedHashMap<>();
}
