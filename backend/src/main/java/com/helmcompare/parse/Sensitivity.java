package com.helmcompare.parse;

import com.helmcompare.model.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Detection of sensitive names and secret mechanisms, plus masking of sensitive literal values. */
public final class Sensitivity {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(passw(or)?d|passwd|pwd|secret|token|api[_.-]?key|private[_.-]?key|credential|access[_.-]?key"
                    + "|client[_.-]?secret|conn(ection)?[_.-]?str(ing)?|keystore|truststore|signing[_.-]?key|sas[_.-]?key"
                    + "|encryption[_.-]?key|auth[_.-]?key|jwt[_.-]?key|(^|[_.-])pass($|[_.-]))");

    private static final Pattern NOT_SENSITIVE = Pattern.compile(
            "(?i)([_.-](url|uri|name|names|path|file|endpoint|host|enabled|expiry|ttl|timeout|length|policy|header"
                    + "|type|ref|mount|dir|location)$|secretkeyref|secretname|secretstore|secretref|secretproviderclass)");

    private Sensitivity() {
    }

    public static boolean isSensitiveName(String name) {
        if (name == null || name.isEmpty()) return false;
        return SENSITIVE.matcher(name).find() && !NOT_SENSITIVE.matcher(name).find();
    }

    public static ValueSource detectLiteralSource(String value) {
        if (value == null || value.isBlank()) return ValueSource.EMPTY;
        String l = value.toLowerCase(Locale.ROOT);
        if (l.contains("akeyless")) return ValueSource.AKEYLESS;
        if (l.startsWith("vault:") || l.contains("vault.hashicorp")) return ValueSource.VAULT;
        if (l.startsWith("secretsmanager:") || l.contains("arn:aws:secretsmanager") || l.startsWith("azurekeyvault:")
                || l.startsWith("gcpsm:") || l.startsWith("ref+")) {
            return ValueSource.EXTERNAL_SECRET;
        }
        if (value.contains("‹")) return ValueSource.TEMPLATE;
        return ValueSource.LITERAL;
    }

    public static String mask(String value) {
        int len = value == null ? 0 : value.length();
        return "•••••• (hidden, " + len + " chars)";
    }

    /** Stable fingerprint used to compare hidden values without exposing them. */
    public static String fingerprint(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
