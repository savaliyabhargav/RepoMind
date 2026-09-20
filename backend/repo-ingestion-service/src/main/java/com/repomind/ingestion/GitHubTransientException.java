package com.repomind.ingestion;

public class GitHubTransientException extends RuntimeException {

    public GitHubTransientException(String message) {
        super(message);
    }

    public GitHubTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
