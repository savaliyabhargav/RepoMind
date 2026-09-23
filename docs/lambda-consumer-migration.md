# Running the Kafka Consumers on AWS Lambda

Status: proposal, nothing implemented yet.

Kafka itself stays a normal always-on service (docker-compose VM, Confluent/Redpanda Cloud, or MSK). Only the
**consumers** move to Lambda, using a Kafka event source mapping (AWS polls the topic and invokes the function with batches).

## 1. Background: cold starts

Lambda does not keep code running. If no warm instance exists, AWS starts a micro-VM, loads the code, starts the
runtime (the JVM), runs init code (for Spring Boot: builds the whole application context, opens DB connections), and
only then runs the handler. That startup cost is the **cold start**. Warm instances are reused for a few minutes.

- Java/Spring Boot cold starts are typically several seconds (often 5-15 s).
- Mitigations: Lambda SnapStart, provisioned concurrency (paid), plain handlers or Spring Cloud Function, GraalVM native.
- Consumers are asynchronous, so a cold start only delays the first batch and is not user-visible.

## 2. Code structure changes

1. Split the backend into Maven modules: `core` (entities, GitHub client, DB writing), `api` (existing Spring Boot app),
   `workers` (Lambda handlers).
2. Use thin handlers (plain Java or Spring Cloud Function with SnapStart), not the full Spring Boot application.
3. Deserialize Kafka records manually. Lambda delivers them base64-encoded, so the Spring JSON type-header mechanism
   (`spring.json.use.type.headers`) does not apply.

## 3. Behavior changes

4. Remove manual `Acknowledgment`. Lambda commits offsets when the function returns successfully.
5. Make handlers idempotent. A failed batch is retried and Kafka event sources have no partial-batch reporting, so
   records can run twice. Keep the canonical-repo dedupe and insert file nodes with
   `ON CONFLICT (repo_id, path) DO NOTHING`.
6. Replace long sleeps in `IngestionWorker` (GitHub rate-limit and transient backoff). Re-publish to a retry topic
   (e.g. `repo-ingest-retry`) with a "not before" time so each invocation stays short (Lambda cap: 15 minutes).
7. Replace `FileNodeBatchWriter`'s in-memory buffer and `@Scheduled` flush with the event source's batch size and
   batching window, followed by one JDBC batch insert per invocation.
8. Run the analysis pipeline as staged messages on `repo-analysis-jobs`. Each stage is its own message, checkpointed in
   `analysis_stages`, so every invocation stays under 15 minutes and runs are resumable.
9. Report progress without SSE from Lambda. Workers write progress to Redis or the DB, and the always-on API serves
   the SSE stream to the browser.

## 4. Infrastructure changes

10. Database connections: use RDS Proxy or PgBouncer, a pool size of 1 per function, and reserved concurrency
    (consumer scaling is bounded by the partition count, currently 3).
11. Networking: a Lambda in a VPC has no internet access without a NAT gateway or VPC endpoints. Workers need both
    Kafka/DB and the GitHub API (NAT is roughly $30/month).
12. Secrets: move DB credentials, `GITHUB_TOKEN` and Kafka credentials to Secrets Manager or SSM.
13. Failure handling: configure an on-failure destination (dead-letter SQS/SNS) so poison messages do not retry forever.
14. Observability and deployment: structured logs, CloudWatch metrics and alarms, infrastructure as code
    (SAM, CDK or Terraform) and CI.

## 5. Suggested order of work

1. Local/BYO LLM support (OpenAI-compatible client). See the AI provider work.
2. Async analysis pipeline over Kafka with progress via Redis (currently the analysis runs synchronously in the HTTP request).
3. Module split and Lambda handlers for the ingestion consumers.
4. Infrastructure (VPC, RDS Proxy, secrets, DLQ) and CI/CD.

## 6. Open decisions

- Where Kafka runs: single-broker VM, Confluent/Redpanda Cloud, or MSK (about $100+/month).
- Whether the consumers actually move to Lambda, or stay as Spring Boot containers next to Kafka (simpler, no rewrite).
