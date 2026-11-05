# Size-Level Availability Processor

A Java 21 / Quarkus service that receives seller stock changes (one event at a time or as an
asynchronous CSV import), enriches them with canonical product context, updates the Availability
API and publishes a normalized `SizeAvailabilityUpdated` event.

The provided dependencies (Product Context API, Availability API Gateway, output-event sink) are
the WireMock mocks from the exercise package (`../compose.yaml`, `../wiremock`). They are not
modified.

## 1. Run it

Prerequisites: JDK 21, Maven 3.9+, Docker (only for the mocks).

```bash
# From the exercise package root (the folder with compose.yaml): start the mocks on :8080
docker compose up -d
curl http://localhost:8080/health

# Start the service on :8081 (dev mode with live reload)
cd availability-processor
mvn quarkus:dev

# ...or build and run the jar
mvn package
java -jar target/quarkus-app/quarkus-run.jar
```

The WireMock base URL can be changed with `DOWNSTREAM_BASE_URL` (maps to `downstream.base-url`).

API documentation: Swagger UI at <http://localhost:8081/q/swagger-ui>, OpenAPI spec at
<http://localhost:8081/q/openapi>.

### Run the tests

```bash
cd availability-processor
mvn test
```

No Docker is needed for the tests: they start an **embedded WireMock that loads the provided
`../wiremock` mappings and fixtures** (same version as `compose.yaml`, local response templating).
So the tests exercise the real mock contracts, including the deterministic fault inputs.

| Test class | What it covers |
|---|---|
| `AvailabilityProcessorTest` | Core flow: apply, duplicate, stale, independent keys, unknown/inactive/disabled product, retries, publish failure + republish |
| `RetryPolicyTest` | Exponential backoff, cap, `Retry-After` honoured or not retried, permanent errors never retried |
| `StockUpdateMapperTest`, `StockUpdateValidatorTest` | Event contract, CSV row conversion and shared validation rules |
| `InventoryEventHandlerTest` | Events use the single lookup (never the snapshot); invalid events never reach dependencies |
| `CsvImportParserTest` | Synchronous upload rejection, CRLF/BOM/quotes/column order, row-level format errors |
| `BulkImportServiceTest` | One snapshot per country, no single lookups, stale rows vs. event versions, partial failures, snapshot outage |
| `ProductContextSnapshotReaderTest` | Streaming + filtering of the snapshot |
| `HttpDependencyFaultsTest` | Every documented WireMock fault input is classified correctly (404 / 429 + `Retry-After` / 503) |
| `DevMessagesControllerTest` | End-to-end single-event flow against WireMock, including faults and publish recovery |
| `AvailabilityImportControllerTest` | End-to-end import of the provided 10,000-row CSV (1 snapshot GET, 0 single lookups), partial failures |

## 2. API

### `POST /_dev/messages` (test-only ingress)

Simulates a broker delivering one `SellerInventoryChanged` message. It calls
`InventoryEventHandler.handle(...)`, the same method a production consumer would call.

```bash
curl -i -X POST http://localhost:8081/_dev/messages \
  -H "Content-Type: application/json" \
  --data-binary @../wiremock/__files/sample-event.json
```

```json
{"eventId":"msg-0001","outcome":"APPLIED","reason":null,
 "message":"availability updated and event published","outputEventId":"8c0f...-..."}
```

The HTTP status follows broker semantics (ack / dead-letter / nack):

| Outcome | HTTP | Meaning | In production |
|---|---|---|---|
| `APPLIED` | 200 | Availability written, event published | ack |
| `REPUBLISHED` | 200 | Version had been written before; its pending event is now published | ack |
| `SKIPPED` (`STALE_VERSION`, `DUPLICATE_VERSION`) | 200 | Nothing to do, idempotent | ack |
| validation error | 400 | Message violates the contract | dead-letter |
| `REJECTED` (`PRODUCT_CONTEXT_NOT_FOUND`, `PRODUCT_INACTIVE`, `PRODUCT_NOT_ENABLED_IN_COUNTRY`, `AVAILABILITY_UPDATE_REJECTED`) | 422 | Permanent: retrying will not help | dead-letter |
| `FAILED` (`PRODUCT_CONTEXT_UNAVAILABLE`, `AVAILABILITY_UPDATE_FAILED`, `EVENT_PUBLISH_FAILED`) | 503 | Transient: redeliver later | nack / retry topic |

### `POST /v1/availability/imports`

Accepts the CSV as a standard file upload: `multipart/form-data`, field `file` (other content types
get `415 Unsupported Media Type`):

```bash
curl -i -X POST http://localhost:8081/v1/availability/imports \
  -F "file=@../wiremock/__files/bulk-stock.csv;type=text/csv"
```

```
HTTP/1.1 202 Accepted
Location: /v1/availability/imports/3f2c...
{"jobId":"3f2c...","state":"QUEUED","totalRows":10000,"statusUrl":"/v1/availability/imports/3f2c..."}
```

Rejected synchronously with `400` (nothing is processed): empty body, missing or duplicated header
columns, no data rows, missing multipart field. Columns may come in any order; extra columns are
ignored.

### `GET /v1/availability/imports/{jobId}`

```json
{
  "jobId": "3f2c...",
  "state": "COMPLETED_WITH_ERRORS",
  "createdAt": "2026-11-04T18:00:00Z", "startedAt": "...", "completedAt": "...",
  "totalRows": 6, "processedRows": 6,
  "succeededRows": 1, "skippedRows": 1, "failedRows": 4,
  "failureMessage": null,
  "errors": [
    {"rowNumber": 4, "sellerId": "s", "productId": "style-50000", "sizeId": "size-38", "countryCode": "PT",
     "sourceVersion": "1", "code": "PRODUCT_CONTEXT_NOT_FOUND", "message": "..."}
  ],
  "skipped": [
    {"rowNumber": 2, "...": "...", "code": "STALE_VERSION", "message": "version 7 is older than applied version 8"}
  ]
}
```

`404` for an unknown `jobId`.

## 3. Business rules and decisions

### Shared flow

Both inputs are converted into the same `StockUpdate` and go through `AvailabilityProcessor`:

1. **Version check** against the latest *successfully applied* version of the stock key
   `(sellerId, productId, sizeId, countryCode)`;
2. **Context resolution** (the only step that differs: single HTTP lookup for events, in-memory
   snapshot index for imports) and business checks;
3. **Availability write** (`PUT`), body `{availableQuantity, sourceVersion, productContext}`;
4. **Record the applied version**;
5. **Publish** `SizeAvailabilityUpdated` to the sink.

Steps 1–5 for one stock key run under a per-key lock, so an event processed while an import is
running can never be overwritten by an older CSV row (and vice versa). Different keys run in
parallel.

### Product context decisions

| Case | Decision | Why |
|---|---|---|
| Unknown `(productId, sizeId, countryCode)` (404 / not in snapshot) | `REJECTED PRODUCT_CONTEXT_NOT_FOUND`, no write, no event | We cannot publish availability without canonical context |
| `productStatus != ACTIVE` | `REJECTED PRODUCT_INACTIVE` | Inactive products must not become sellable |
| `countryEnabled != true` | `REJECTED PRODUCT_NOT_ENABLED_IN_COUNTRY` | Not sellable in that country |
| 429 / 503 / timeout | `FAILED PRODUCT_CONTEXT_UNAVAILABLE` | Transient, safe to retry/redeliver |

A rejected update does **not** record its version, so a later update for the same key is evaluated
normally once the catalogue changes.

### Versions, duplicates and stale updates

* `sourceVersion` is only compared within the same stock key. `eventId` is ignored for
  de-duplication because it identifies a delivery, not a business change.
* `version < applied` → `SKIPPED STALE_VERSION` (no write, no event).
* `version == applied` and its event was published → `SKIPPED DUPLICATE_VERSION`.
* `version == applied` but its event was **not** published → only the event is (re)published
  (`REPUBLISHED`), the availability write is not repeated.
* The same rules apply to CSV rows, and events and rows share one version store, so a bulk import
  never overrides newer stock.

### Retries and flow control

* Only transient failures (429, 5xx, timeouts, connection errors) are retried; other 4xx never.
* Bounded in-process retry (`retry.max-attempts=3`), exponential backoff starting at
  `retry.initial-backoff=200ms`, capped at `retry.max-backoff=5s`.
* `Retry-After` is honoured: we never retry earlier than asked. If it asks for more than
  `retry.max-backoff`, we stop retrying in-process and return a transient failure (503 to the broker
  / row error in the job) instead of blocking a worker thread.
* Bulk work runs on a fixed pool shared by all jobs (`bulk.row-concurrency=8`), so at most 8 rows
  are in flight against each dependency, whatever the number or size of uploads. Jobs run on a
  separate small pool (`bulk.max-concurrent-jobs=2`).
* For events, flow control in production comes from the broker consumer (bounded number of
  partitions/consumers, max in-flight messages).

### Gateway write succeeds, event publication fails

The applied version is recorded with `published=false` and the result is
`FAILED EVENT_PUBLISH_FAILED` (503 → the broker redelivers). On redelivery of the same version the
processor skips the write and republishes the event. The output `eventId` is a deterministic UUID of
`(sellerId, productId, sizeId, countryCode, sourceVersion)`, so the republished event has the
**same `eventId`** and downstream consumers can de-duplicate it. In other words the system is
*at-least-once* for output events, never *at-most-once*.

If the service crashed between the `PUT` and recording the version, a redelivery would repeat the
`PUT` with the same `sourceVersion`; the Availability API receives the version so it can treat
that as idempotent as well. A production version closes this gap with a transactional outbox
(see below).

### Partial failures in bulk imports

* A bad row never rejects the accepted upload; every row ends in exactly one bucket:
  `succeeded` (applied or republished), `skipped` (stale / duplicate — correct behaviour, not an
  error) or `failed` (invalid row, unknown/inactive/disabled product, dependency failure).
* `processedRows = succeededRows + skippedRows + failedRows`, and equals `totalRows` when done.
* `state`: `QUEUED` → `RUNNING` → `COMPLETED` (no failed rows) or `COMPLETED_WITH_ERRORS`
  (at least one failed row). `FAILED` only if the job itself crashes.
* `errors` / `skipped` list each affected row with its `rowNumber` (1-based line number in the
  file, header = line 1), identity fields, a machine-readable `code` and a message.
* Failed rows with a transient code (`*_UNAVAILABLE`, `*_FAILED`) can be fixed by re-uploading
  the same rows: already-applied rows are skipped as duplicates, so re-uploading is safe.
* If a country's snapshot cannot be fetched (after retries), only that country's rows fail with
  `PRODUCT_CONTEXT_UNAVAILABLE`.

## 4. Bulk context matching: complexity

Let *R* = rows in the upload, *S* = contexts in the snapshot, *C* = distinct countries in the upload.

1. Build the set of wanted context keys from the valid rows: **O(R)** time and space.
2. For each country, download the snapshot once (*C* HTTP calls, not *R*) and **stream** it object by
   object, keeping a context only if its key is in the wanted set (O(1) hash lookup): **O(S)** time,
   memory proportional to the matches (≤ R), not to S.
3. Each row resolves its context with an O(1) hash lookup: **O(R)**.

Total: **O(R + S) time, O(R) space** (plus the rows themselves). For the fixture: 10,000 rows +
50,000 contexts → ~60,000 cheap operations instead of 500,000,000 comparisons for a nested loop
(O(R·S)) and with less memory than loading the whole snapshot into a map (O(S)).

As both grow, cost stays linear. When *S* grows much faster than *R* (e.g. millions of contexts
for a 100-row upload) the snapshot download dominates; then I would cache the snapshot per country
with a TTL/ETag, keep a local replica fed by product-context change events, or use a batch lookup
endpoint when R ≪ S. Note: the Quarkus REST client may buffer the raw response bytes before our
streaming parser reads them; for very large snapshots a fully streaming HTTP client
(e.g. `java.net.http` with `BodyHandlers.ofInputStream`) keeps memory strictly O(R).

## 5. Production broker

**Choice: Apache Kafka** (or a managed equivalent such as Confluent Cloud / MSK / Event Hubs Kafka).

* **Ordering where it matters:** topic `seller-inventory-changed` partitioned by the stock key
  `sellerId|productId|sizeId|countryCode`. All versions of one stream land in one partition and are
  consumed in order by one consumer; out-of-order arrivals from upstream are still handled by the
  version check.
* **At-least-once:** commit offsets only after `handle(...)` returns an ack outcome; our processing
  is idempotent, so redeliveries are harmless.
* **Retries / DLQ:** transient failures → retry topic(s) with backoff (or pause the partition);
  invalid and rejected messages → dead-letter topic with the reason code.
* **Output:** `size-availability-updated` topic, same partition key, so consumers see per-key order
  and can de-duplicate by the deterministic `eventId`.
* **Wiring:** with Quarkus Messaging (SmallRye Kafka connector) the adapter is a few lines; the core
  is unchanged:

  ```java
  @Incoming("seller-inventory-changed")
  public CompletionStage<Void> consume(Message<InventoryEventMessage> message) {
      ProcessingResult result = handler.handle(message.getPayload());   // same call as /_dev/messages
      return result.outcome() == Outcome.FAILED ? message.nack(new IllegalStateException(result.message())) : message.ack();
  }
  ```

  and `HttpEventPublisher` is replaced by a Kafka producer implementing `EventPublisher`.

## 6. Assumptions and simplifications

* State (applied versions, jobs) is **in memory**: it is lost on restart and not shared between
  instances. Production: a database table keyed by stock key with a conditional update
  (`UPDATE ... SET version = :v WHERE key = :k AND version < :v`), jobs and row results persisted,
  and a **transactional outbox** so "record version + enqueue event" is atomic.
* Per-key locking is local to one instance (striped locks). With Kafka partitioning by stock key a
  key is consumed by only one instance at a time; the conditional DB update protects the rest
  (e.g. bulk and events on different instances).
* Validation: `countryCode` must be an upper-case ISO 3166-1 alpha-2 code; `availableQuantity >= 0`;
  `sourceVersion >= 0`; events must have `eventType = SellerInventoryChanged`, `schemaVersion = 1`
  and an ISO-8601 `occurredAt`.
* Output `occurredAt` is the processing time of the publication.
* The output event and the availability body include `productName`, `brand`, `category`, `color`,
  `sizeLabel` and `sizeSystem` as canonical context.
* The CSV parser supports quoted fields on a single line (no multi-line fields); upload size is
  limited to 20 MB (`quarkus.http.limits.max-body-size`).
* Row results are kept in full for each job; for very large files I would cap them and offer a
  downloadable error report.
* The WireMock event-sink faults (`eventId` = `fault-429` / `fault-503`) cannot be triggered
  through `/_dev/messages` because output ids are derived from the stock key and version (see
  above); they are exercised by `HttpDependencyFaultsTest`, and the publish-failure recovery is
  tested end to end with a test-only WireMock stub in `DevMessagesControllerTest`.

## 7. Code map

```
src/main/java/com/example/availability/
├── controller/        HTTP endpoints (DevMessagesController, AvailabilityImportController)
├── dto/
│   ├── request/       inbound message contract (InventoryEventMessage)
│   ├── response/      bodies returned by our API (MessageProcessingResponse, ImportAcceptedResponse,
│   │                  ImportJobStatusResponse, ErrorResponse)
│   └── external/      payloads sent to the dependencies (AvailabilityUpdateRequest, SizeAvailabilityUpdated)
├── exception/         InvalidInputException + dependency failure model
│                      (TransientDependencyException, PermanentDependencyException, ProductContextNotFoundException)
├── mapper/            conversions: input -> StockUpdate (StockUpdateMapper), result/job -> DTO + HTTP status
│                      (ProcessingResultMapper, ImportJobMapper), exception -> HTTP (InvalidInputExceptionMapper)
├── model/             domain model (StockKey, ContextKey, StockUpdate, StockState, ProductContext,
│                      ProductContextSummary, ProcessingResult, ReasonCode, ImportJob, ImportRow)
├── service/           business logic: AvailabilityProcessor (shared flow), InventoryEventHandler (consumer logic),
│                      BulkImportService (async worker), ContextResolver
├── repository/        state: StockStateRepository (+ in-memory impl), ImportJobRepository
├── gateway/           interfaces to the dependencies (ProductContextProvider, AvailabilityGateway, EventPublisher)
│   └── http/          HTTP implementations + status classification + streaming snapshot reader
├── client/            declarative Quarkus REST clients for the WireMock routes
├── parser/            CsvImportParser
├── validation/        StockUpdateValidator (rules shared by events and CSV rows)
├── util/              RetryPolicy, OutputEventIds
└── config/            CDI producers (Clock, RetryPolicy)
```

Tests mirror the same packages; `support/` holds fakes, test data and the embedded WireMock resource.
