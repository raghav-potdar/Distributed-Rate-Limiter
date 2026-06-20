-- Token Bucket rate limiter
-- KEYS[1]  = bucket key (e.g. "rate_limit:user-abc")
-- ARGV[1]  = capacity       (max tokens)
-- ARGV[2]  = refillRate     (tokens per second)
-- ARGV[3]  = now            (current epoch milliseconds)
-- Returns: {allowed (0|1), remainingTokens, retryAfterSeconds}

local key         = KEYS[1]
local capacity    = tonumber(ARGV[1])
local refillRate  = tonumber(ARGV[2])
local now         = tonumber(ARGV[3])

-- Read current bucket state
local data              = redis.call('HMGET', key, 'tokens', 'lastRefillTimestamp')
local tokens            = tonumber(data[1])
local lastRefillTimestamp = tonumber(data[2])

-- Initialise bucket on first access
if tokens == nil then
    tokens              = capacity
    lastRefillTimestamp = now
end

-- Refill: add tokens proportional to elapsed time, cap at capacity
local elapsed  = math.max(0, (now - lastRefillTimestamp) / 1000.0)
local newTokens = math.min(capacity, tokens + elapsed * refillRate)

local allowed    = 0
local retryAfter = 0

if newTokens >= 1 then
    newTokens = newTokens - 1
    allowed   = 1
else
    -- Time (seconds) until 1 token is available
    retryAfter = math.ceil((1 - newTokens) / refillRate)
end

-- Persist updated state; TTL = 2× time to fully refill from empty
local ttl = math.ceil(2 * capacity / refillRate)
redis.call('HSET', key, 'tokens', newTokens, 'lastRefillTimestamp', now)
redis.call('EXPIRE', key, ttl)

return {allowed, math.floor(newTokens), retryAfter}
