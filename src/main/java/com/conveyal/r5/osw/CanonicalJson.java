package com.conveyal.r5.osw;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A canonical text form of JSON, used to derive stable content identifiers (md5) for pedestrian cost profiles and run
 * specifications. Two JSON documents that differ only in whitespace, object key order, or number formatting
 * (1.0 vs 1, 0.10 vs 0.1, 1e-2 vs 0.01) have the same canonical form and therefore the same identifier.
 *
 * Rules (mirrored exactly by canonical_json() in osw-tools/pedestrian_profile.py, so Java and Python agree):
 *  - Objects: keys sorted by Unicode code point, written as {"k":v,...} with no whitespace.
 *  - Arrays: order preserved, [a,b,...].
 *  - Numbers: exact decimal value with trailing zeros removed, in plain (non-exponent) notation. -0 becomes 0.
 *  - Strings: JSON-escaped with only the mandatory escapes (quote, backslash, control characters); non-ASCII as-is.
 *  - true, false, null as literals.
 */
public final class CanonicalJson {

    private CanonicalJson () { }

    public static String canonicalize (JsonNode node) {
        StringBuilder sb = new StringBuilder();
        write(node, sb);
        return sb.toString();
    }

    public static String md5Hex (String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String md5OfCanonical (JsonNode node) {
        return md5Hex(canonicalize(node));
    }

    private static void write (JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) keys.add(it.next());
            // String.compareTo orders by UTF-16 code unit, which matches code point order except for surrogate pairs
            // compared against BMP characters above U+D7FF. Compare by code points to match Python's sorted().
            Collections.sort(keys, CanonicalJson::compareCodePoints);
            sb.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) sb.append(',');
                first = false;
                writeString(key, sb);
                sb.append(':');
                write(node.get(key), sb);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) sb.append(',');
                write(node.get(i), sb);
            }
            sb.append(']');
        } else if (node.isNumber()) {
            sb.append(canonicalNumber(node.decimalValue()));
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
        } else if (node.isTextual()) {
            writeString(node.textValue(), sb);
        } else {
            // Binary or POJO nodes do not occur in parsed JSON.
            writeString(node.asText(), sb);
        }
    }

    static String canonicalNumber (BigDecimal value) {
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    /** Canonical form of a double, as used when resolved parameter values are hashed. */
    static String canonicalNumber (double value) {
        // BigDecimal.valueOf uses Double.toString, the shortest decimal that round-trips, as does Python's repr().
        return canonicalNumber(BigDecimal.valueOf(value));
    }

    private static void writeString (String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static int compareCodePoints (String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
