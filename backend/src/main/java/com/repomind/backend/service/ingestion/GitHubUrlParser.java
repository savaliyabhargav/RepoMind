package com.repomind.backend.service.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitHubUrlParser {

    // Anchored: only http(s) github.com URLs qualify — an unanchored find() would
    // accept ftp:// schemes and hostnames that merely contain "github.com".
    // Owner: GitHub usernames are alphanumeric with inner hyphens.
    // Repo: may contain dots (e.g. next.js); a trailing ".git" is stripped, and
    // extra path segments or query strings after the repo (e.g. /tree/main) are tolerated.
    private static final Pattern PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?github\\.com/"
            + "([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)/"
            + "([A-Za-z0-9._-]+?)(?:\\.git)?(?:[/?#].*)?$");

    private GitHubUrlParser() {}

    public static String extractOwner(String url) {
        return match(url).group(1);
    }

    public static String extractRepoName(String url) {
        return match(url).group(2);
    }

    public static boolean isValid(String url) {
        return url != null && PATTERN.matcher(url.trim()).matches();
    }

    private static Matcher match(String url) {
        if (url == null) throw new IllegalArgumentException("Invalid GitHub URL: null");
        Matcher m = PATTERN.matcher(url.trim());
        if (!m.matches()) throw new IllegalArgumentException("Invalid GitHub URL: " + url);
        return m;
    }
}
