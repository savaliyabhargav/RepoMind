package com.repomind.backend.service.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class GitHubUrlParserTest {

    // ── extractOwner ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "owner from [{0}] = [{1}]")
    @CsvSource({
        "https://github.com/owner/repo,         owner",
        "https://github.com/octocat/Hello-World, octocat",
        "https://github.com/org/repo.git,        org",
        "http://github.com/user/project,         user",
        "https://github.com/my-org/my-repo,      my-org",
    })
    void extractOwner_returnsCorrectOwner(String url, String expected) {
        assertThat(GitHubUrlParser.extractOwner(url.trim())).isEqualTo(expected.trim());
    }

    // ── extractRepoName ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "name from [{0}] = [{1}]")
    @CsvSource({
        "https://github.com/owner/repo,          repo",
        "https://github.com/octocat/Hello-World,  Hello-World",
        "https://github.com/org/repo.git,         repo",
        "https://github.com/user/spring-boot,     spring-boot",
    })
    void extractRepoName_returnsCorrectName(String url, String expected) {
        assertThat(GitHubUrlParser.extractRepoName(url.trim())).isEqualTo(expected.trim());
    }

    @Test
    void extractRepoName_stripsGitSuffix() {
        assertThat(GitHubUrlParser.extractRepoName("https://github.com/user/myrepo.git"))
                .isEqualTo("myrepo");
    }

    // ── isValid ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "https://github.com/owner/repo",
        "https://github.com/octocat/Hello-World",
        "https://github.com/org/repo.git",
        "http://github.com/user/project",
        "https://github.com/my-org/my-repo",
    })
    void isValid_trueForValidUrls(String url) {
        assertThat(GitHubUrlParser.isValid(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://gitlab.com/owner/repo",
        "https://github.com/owner",
        "not-a-url",
        "",
        "https://github.com/",
        "ftp://github.com/owner/repo",
    })
    void isValid_falseForInvalidUrls(String url) {
        assertThat(GitHubUrlParser.isValid(url)).isFalse();
    }

    // ── error cases ───────────────────────────────────────────────────────────

    @Test
    void extractOwner_throwsIllegalArgumentOnNonGitHubUrl() {
        assertThatThrownBy(() -> GitHubUrlParser.extractOwner("https://gitlab.com/x/y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid GitHub URL");
    }

    @Test
    void extractRepoName_throwsIllegalArgumentOnNonGitHubUrl() {
        assertThatThrownBy(() -> GitHubUrlParser.extractRepoName("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
