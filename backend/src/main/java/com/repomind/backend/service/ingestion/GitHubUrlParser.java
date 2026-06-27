package com.repomind.backend.service.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitHubUrlParser {

    private static final Pattern PATTERN =
            Pattern.compile("github\\.com/([^/]+)/([^/.]+)(?:\\.git)?");

    private GitHubUrlParser() {}

    public static String extractOwner(String url) {
        return match(url).group(1);
    }

    public static String extractRepoName(String url) {
        return match(url).group(2);
    }

    public static boolean isValid(String url) {
        return PATTERN.matcher(url).find();
    }

    private static Matcher match(String url) {
        Matcher m = PATTERN.matcher(url);
        if (!m.find()) throw new IllegalArgumentException("Invalid GitHub URL: " + url);
        return m;
    }
}
