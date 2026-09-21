package com.repomind.aiprovider;

import com.repomind.aiprovider.dto.AiEmbeddingRequest;
import com.repomind.aiprovider.dto.AiEmbeddingResponse;
import com.repomind.aiprovider.dto.AiGenerationRequest;
import com.repomind.aiprovider.dto.AiGenerationResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAiProviderClientTest {

    private static final String CHAT_OK = """
            {"model":"gemma4:e4b","choices":[{"message":{"role":"assistant","content":"OK"}}],
             "usage":{"prompt_tokens":7,"completion_tokens":2}}""";

    private static final String EMBED_OK = """
            {"model":"nomic-embed-text","data":[{"embedding":[0.5,0.25,0.125]}],"usage":{"prompt_tokens":3}}""";

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    /** Starts a server answering every request with the given status/body; records request bodies and auth headers. */
    private FakeServer serve(int status, String responseBody) throws IOException {
        FakeServer fake = new FakeServer();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            fake.hits.incrementAndGet();
            fake.requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            fake.authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        servers.add(server);
        fake.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return fake;
    }

    private static final class FakeServer {
        String url;
        final AtomicInteger hits = new AtomicInteger();
        final List<String> requestBodies = new CopyOnWriteArrayList<>();
        final List<String> authHeaders = new CopyOnWriteArrayList<>();
    }

    private static AiGenerationRequest chat() {
        return new AiGenerationRequest("LOCAL", "meta/llama-3.1-70b-instruct", "sys", "hello", 0.1, 50);
    }

    private static LocalAiProviderClient client(String endpoints, String apiKey, String model, AtomicLong clock) {
        return new LocalAiProviderClient(endpoints, apiKey, model, "nomic-embed-text", 5, 30, "none", clock::get);
    }

    // ── supports ──────────────────────────────────────────────────────────────

    @Test
    void supports_recognisesLocalProviderNamesCaseInsensitively() {
        LocalAiProviderClient c = client("http://localhost:1/v1", "", "m", new AtomicLong());

        assertThat(c.supports("local")).isTrue();
        assertThat(c.supports("OLLAMA")).isTrue();
        assertThat(c.supports("Kaggle")).isTrue();
        assertThat(c.supports("OPENAI_COMPATIBLE")).isTrue();
        assertThat(c.supports("NVIDIA_DEV")).isFalse();
        assertThat(c.supports("GROQ")).isFalse();
        assertThat(c.supports(null)).isFalse();
    }

    // ── generate ──────────────────────────────────────────────────────────────

    @Test
    void generate_parsesResponseAndUsesConfiguredModelInsteadOfCallerModel() throws Exception {
        FakeServer server = serve(200, CHAT_OK);
        LocalAiProviderClient c = client(server.url, "", "gemma4:e4b", new AtomicLong());

        AiGenerationResponse response = c.generate(chat());

        assertThat(response.text()).isEqualTo("OK");
        assertThat(response.model()).isEqualTo("gemma4:e4b");
        assertThat(response.usage().inputTokens()).isEqualTo(7);
        assertThat(response.usage().outputTokens()).isEqualTo(2);
        assertThat(server.requestBodies.get(0)).contains("\"model\":\"gemma4:e4b\"");
        assertThat(server.requestBodies.get(0)).doesNotContain("llama-3.1-70b");
    }

    @Test
    void generate_fallsBackToCallerModelWhenNoModelConfigured() throws Exception {
        FakeServer server = serve(200, CHAT_OK);
        LocalAiProviderClient c = client(server.url, "", "", new AtomicLong());

        c.generate(chat());

        assertThat(server.requestBodies.get(0)).contains("meta/llama-3.1-70b-instruct");
    }

    @Test
    void generate_sendsBearerTokenOnlyWhenApiKeyConfigured() throws Exception {
        FakeServer withKey = serve(200, CHAT_OK);
        FakeServer withoutKey = serve(200, CHAT_OK);

        client(withKey.url, "secret", "m", new AtomicLong()).generate(chat());
        client(withoutKey.url, "", "m", new AtomicLong()).generate(chat());

        assertThat(withKey.authHeaders.get(0)).isEqualTo("Bearer secret");
        assertThat(withoutKey.authHeaders.get(0)).isNull();
    }

    @Test
    void generate_toleratesTrailingSlashOnEndpointUrl() throws Exception {
        FakeServer server = serve(200, CHAT_OK);

        client(server.url + "/", "", "m", new AtomicLong()).generate(chat());

        assertThat(server.hits.get()).isEqualTo(1);
    }

    @Test
    void generate_sendsReasoningEffortWhenConfiguredAndOmitsItWhenBlank() throws Exception {
        FakeServer withEffort = serve(200, CHAT_OK);
        FakeServer withoutEffort = serve(200, CHAT_OK);

        client(withEffort.url, "", "m", new AtomicLong()).generate(chat());
        new LocalAiProviderClient(withoutEffort.url, "", "m", "e", 5, 30, "", new AtomicLong()::get).generate(chat());

        assertThat(withEffort.requestBodies.get(0)).contains("\"reasoning_effort\":\"none\"");
        assertThat(withoutEffort.requestBodies.get(0)).doesNotContain("reasoning_effort");
    }

    // ── failover ──────────────────────────────────────────────────────────────

    @Test
    void generate_failsOverWhenFirstEndpointIsUnreachable() throws Exception {
        FakeServer alive = serve(200, CHAT_OK);
        // port 1 is closed: connection refused, like a dead tunnel
        LocalAiProviderClient c = client("http://127.0.0.1:1/v1," + alive.url, "", "m", new AtomicLong());

        assertThat(c.generate(chat()).text()).isEqualTo("OK");
        assertThat(alive.hits.get()).isEqualTo(1);
    }

    @Test
    void generate_failsOverOnServerErrorAndSkipsEndpointDuringCooldown() throws Exception {
        FakeServer broken = serve(503, "{\"error\":\"overloaded\"}");
        FakeServer alive = serve(200, CHAT_OK);
        AtomicLong clock = new AtomicLong(1_000);
        LocalAiProviderClient c = client(broken.url + "," + alive.url, "", "m", clock);

        c.generate(chat());
        assertThat(broken.hits.get()).isEqualTo(1);

        c.generate(chat());
        assertThat(broken.hits.get()).as("skipped while cooling down").isEqualTo(1);
        assertThat(alive.hits.get()).isEqualTo(2);

        clock.addAndGet(31_000);
        c.generate(chat());
        assertThat(broken.hits.get()).as("retried after cooldown").isEqualTo(2);
    }

    @Test
    void generate_triesEndpointsAgainWhenAllAreCoolingDown() throws Exception {
        FakeServer only = serve(500, "{\"error\":\"boom\"}");
        LocalAiProviderClient c = client(only.url, "", "m", new AtomicLong(1_000));

        assertThatThrownBy(() -> c.generate(chat())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.generate(chat())).isInstanceOf(IllegalStateException.class);

        assertThat(only.hits.get()).as("not locked out while in cooldown").isEqualTo(2);
    }

    @Test
    void generate_throwsClearErrorWhenAllEndpointsFail() throws Exception {
        FakeServer broken = serve(502, "bad gateway");
        LocalAiProviderClient c = client("http://127.0.0.1:1/v1," + broken.url, "", "m", new AtomicLong());

        assertThatThrownBy(() -> c.generate(chat()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("All local LLM endpoints failed")
                .hasMessageContaining("2 tried")
                .hasMessageContaining("502");
    }

    @Test
    void generate_throwsWhenNoEndpointsConfigured() {
        LocalAiProviderClient c = client(" , ", "", "m", new AtomicLong());

        assertThatThrownBy(() -> c.generate(chat()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No local LLM endpoints configured");
    }

    // ── embed ─────────────────────────────────────────────────────────────────

    @Test
    void embed_parsesVectorAndUsesConfiguredEmbeddingModel() throws Exception {
        FakeServer server = serve(200, EMBED_OK);
        LocalAiProviderClient c = client(server.url, "", "gemma4:e4b", new AtomicLong());

        AiEmbeddingResponse response =
                c.embed(new AiEmbeddingRequest("LOCAL", "nvidia/nv-embedqa-e5-v5", "some text", "passage"));

        assertThat(response.embedding()).containsExactly(0.5, 0.25, 0.125);
        assertThat(server.requestBodies.get(0)).contains("\"model\":\"nomic-embed-text\"");
    }

    @Test
    void embed_throwsWhenVectorIsEmpty() throws Exception {
        FakeServer server = serve(200, "{\"data\":[{\"embedding\":[]}]}");
        LocalAiProviderClient c = client(server.url, "", "m", new AtomicLong());

        assertThatThrownBy(() -> c.embed(new AiEmbeddingRequest("LOCAL", "x", "text", "passage")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty vector");
    }
}
