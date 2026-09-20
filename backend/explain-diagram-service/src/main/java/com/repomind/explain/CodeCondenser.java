package com.repomind.explain;

import java.util.regex.Pattern;

/**
 * Shrinks source code before it enters an LLM prompt. Architecture diagrams
 * need structure, signatures, and control flow — not comments, imports, or
 * every assignment — so condensing raises accuracy per token instead of the
 * old blind substring truncation.
 */
public final class CodeCondenser {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern CONTROL_FLOW = Pattern.compile(
            "^(if|else|for|while|switch|case|default|try|catch|finally|return|throw|do|when|match)\\b");

    private CodeCondenser() {}

    /** Drops comments, import/package lines, and blank lines. */
    public static String condense(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String stripped = BLOCK_COMMENT.matcher(code).replaceAll("");
        StringBuilder out = new StringBuilder(stripped.length());
        for (String line : stripped.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("//")) continue;
            if (t.startsWith("# ") || t.equals("#")) continue;
            if (t.startsWith("import ") || t.startsWith("package ") || t.startsWith("using ")) continue;
            if (t.startsWith("from ") && t.contains(" import ")) continue;
            out.append(line.stripTrailing()).append('\n');
        }
        return out.toString();
    }

    /**
     * Keeps declarations, top-level method statements, and control-flow lines;
     * elides deeply nested bodies with a "..." marker. Depth 0 = file level,
     * 1 = class body, 2 = method body — so signatures and each method's direct
     * statements survive while nested block internals are dropped.
     */
    public static String skeleton(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(code.length() / 2);
        int depth = 0;
        boolean eliding = false;
        for (String line : code.split("\n", -1)) {
            String t = line.strip();
            int depthAtStart = t.startsWith("}") || t.startsWith(")") ? Math.max(0, depth - 1) : depth;
            boolean keep = depthAtStart <= 2 || CONTROL_FLOW.matcher(t).find();
            if (keep) {
                out.append(line.stripTrailing()).append('\n');
                eliding = false;
            } else if (!eliding) {
                out.append("    ...\n");
                eliding = true;
            }
            depth += countChar(line, '{') - countChar(line, '}');
            if (depth < 0) depth = 0;
        }
        return out.toString();
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }
}
