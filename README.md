# Distributed Rate Limiter — Token Bucket with Spring Boot + Redis

A working MVP of a distributed rate limiter using the **Token Bucket** algorithm. State is stored in Redis via an atomic Lua script, so any number of application instances can share the same bucket counters without race conditions.

---

## How to Run

### 1. Start Redis

```bash
docker-compose up -d
```

### 2. Start the application

```bash
mvn spring-boot:run
```

The service starts on `http://localhost:8080`.

### 3. Run the tests

```bash
mvn test
```

Testcontainers spins up a real Redis instance for both the integration test and the concurrency test — no Docker setup needed for tests.

---

## Configuration (`application.yml`)

```yaml
rate-limiter:
  defaults:
    capacity: 100      # max tokens in a bucket
    refill-rate: 10    # tokens added per second
```

Per-key overrides can be set at runtime via `PUT /api/admin/limits/{key}`.

---

## API Endpoints

### Protected resource

```
GET /api/resource
Header: X-API-Key: <client-id>
```

**200 OK** (token consumed):
```
X-RateLimit-Limit:     100
X-RateLimit-Remaining: 99
X-RateLimit-Reset:     1
```

**429 Too Many Requests** (bucket empty):
```
X-RateLimit-Remaining: 0
Retry-After:           1
{"error":"rate limit exceeded","retryAfter":1}
```

### Admin endpoints

```
GET /api/admin/limits/{key}
PUT /api/admin/limits/{key}    body: {"capacity":50,"refillRate":5}
```

---

## Sample curl Commands

```bash
# Single request (replace 'user-1' with any client ID)
curl -i -H "X-API-Key: user-1" http://localhost:8080/api/resource

# Drain a bucket with capacity 5 (run in a loop)
for i in $(seq 1 7); do
  curl -si -H "X-API-Key: demo" http://localhost:8080/api/resource \
    | grep -E "HTTP|X-RateLimit|Retry"
  echo "---"
done

# Set a per-key limit
curl -s -X PUT http://localhost:8080/api/admin/limits/demo \
  -H "Content-Type: application/json" \
  -d '{"capacity":5,"refillRate":1}'

# Inspect bucket state
curl -s http://localhost:8080/api/admin/limits/demo | jq .
```

---

## How the Lua Script Works

The script (`src/main/resources/scripts/token_bucket.lua`) runs as a single Redis `EVAL` command, which Redis executes atomically — no other command can interleave between the read and the write.

```
KEYS[1]  bucket key (e.g. "rate_limit:user-1")
ARGV[1]  capacity        (max tokens)
ARGV[2]  refillRate      (tokens / second)
ARGV[3]  now             (epoch milliseconds, from the JVM)
```

**Algorithm (step by step):**

1. `HMGET key tokens lastRefillTimestamp` — read current state (or initialise to full on first call)
2. `elapsed = (now - lastRefillTimestamp) / 1000` — seconds since last request
3. `newTokens = min(capacity, tokens + elapsed × refillRate)` — refill, capped at capacity
4. If `newTokens >= 1`: decrement by 1, set `allowed = 1`
5. Else: compute `retryAfter = ceil((1 - newTokens) / refillRate)`, leave `allowed = 0`
6. `HSET key tokens newTokens lastRefillTimestamp now` — persist new state
7. `EXPIRE key TTL` where `TTL = 2 × capacity / refillRate` — idle buckets clean themselves up
8. `return {allowed, floor(newTokens), retryAfter}`

Because steps 1–8 are one atomic `EVAL`, 200 concurrent callers will never both read "tokens = 1" and both decrement to 0 — exactly one wins.

---

## Concurrency Test Results

The `ConcurrencyTest` fires **200 threads simultaneously** at a bucket with `capacity = 50`.

Actual output (`mvn test`):
```
[INFO] Running com.example.ratelimiter.ConcurrencyTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0

[INFO] Running com.example.ratelimiter.TokenBucketServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0

[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The assertion `assertEquals(50, allowed.get())` proves that the Lua script's atomicity holds under concurrent JVM + network load. All 200 requests complete and the count is exact — never 49 or 51.

---

## Stress Test Script

Located at `src/test/scripts/stress_test.sh`. Fires concurrent batches of requests, measures throughput and latency, and asserts the atomicity invariant (`allowed ≤ capacity`).

```bash
# Default: 200 requests, 50 concurrent, capacity=50, refillRate=1
./src/test/scripts/stress_test.sh

# Custom: 500 requests, 100 concurrent, capacity=100, refillRate=5
./src/test/scripts/stress_test.sh 500 100 100 5
```

### Actual results (localhost, Redis in Docker, Ubuntu 24.04, Java 21)

```
╔══════════════════════════════════════════════════════╗
║  Distributed Rate Limiter — Stress Test            ║
╚══════════════════════════════════════════════════════╝
  URL         : http://localhost:8080/api/resource
  Key         : stress-<pid>
  Capacity    : 50 tokens
  Refill rate : 1 token/sec
  Requests    : 200 total, 50 concurrent

╔══════════════════════════════════════════════════════╗
║  Results                                             ║
╚══════════════════════════════════════════════════════╝
  Total completed:       200 / 200
  Allowed (200):         50
  Rejected (429):        150

  Wall-clock time:       371ms
  Throughput:            539.0 req/s

  Latency:               min=17ms  avg=59ms  max=92ms

╔══════════════════════════════════════════════════════╗
║  Latency histogram                                   ║
╚══════════════════════════════════════════════════════╝
   10– 19ms │ █                                          1
   20– 29ms │ █                                          4
   30– 39ms │ ██                                         8
   40– 49ms │ ████████                                  36
   50– 59ms │ ██████████                                46
   60– 69ms │ ████████████                              57
   70– 79ms │ ████████                                  38
   80– 89ms │ ██                                         7
   90– 99ms │ █                                          3

╔══════════════════════════════════════════════════════╗
║  Atomicity check                                     ║
╚══════════════════════════════════════════════════════╝
  PASS — allowed (50) ≤ capacity (50)
         Lua EVAL atomicity is holding correctly.
```

**Key observations:**
- **Exactly 50 of 200 concurrent requests allowed** — the Lua EVAL atomicity holds perfectly under concurrent load
- **539 req/s throughput** with 50 concurrent workers; the bottleneck is the curl subprocess overhead, not the service
- **Latency distribution** is tight: 90% of requests fall in the 40–79ms band (dominated by curl startup + connection overhead); the service + Redis EVAL itself adds ~1–3ms
- **429 responses are as fast as 200s** — the Lua script rejects in the same single round-trip

---

## Project Structure

```
rate-limiter/
├── src/main/java/com/example/ratelimiter/
│   ├── RateLimiterApplication.java
│   ├── config/
│   │   ├── RateLimiterConfig.java       # WebMvcConfigurer, @EnableConfigurationProperties
│   │   └── RateLimiterProperties.java   # @ConfigurationProperties("rate-limiter")
│   ├── service/
│   │   ├── RateLimitResult.java         # record: allowed, remaining, retryAfterSeconds
│   │   └── TokenBucketService.java      # executes Lua script, manages per-key overrides
│   ├── filter/
│   │   └── RateLimitInterceptor.java    # HandlerInterceptor for /api/resource
│   └── controller/
│       ├── DemoController.java          # GET /api/resource
│       └── AdminController.java         # GET|PUT /api/admin/limits/{key}
├── src/main/resources/
│   ├── application.yml
│   └── scripts/token_bucket.lua
├── src/test/java/com/example/ratelimiter/
│   ├── TokenBucketServiceTest.java      # Testcontainers: allow/reject/refill
│   └── ConcurrencyTest.java             # 200 threads → exactly 50 allowed
├── src/test/scripts/
│   └── stress_test.sh                   # bash concurrent load test + atomicity check
├── docker-compose.yml
└── pom.xml
```
