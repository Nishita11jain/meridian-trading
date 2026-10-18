# Validation and security error logging

The auth service's `HttpExceptionFilter` (`services/auth-service/src/common/http-exception.filter.ts`) logs every request that fails validation or is refused for security reasons, along with the client's IP address. This page shows what a client sees and what ends up in the log for each case. The log lines are examples; timestamps, process ids and IPs will differ.

Handled cases:

- validation failures (missing field, wrong type, bad length, unknown field, malformed JSON)
- 401 unauthorized
- 409 conflict
- 429 rate limited
- 500 unhandled errors

The response to the client is deliberately vague (`Invalid input`, `Unauthorised`). The detail goes in the log only, so an attacker learns nothing from the response but an operator can still see what happened.

## Validation failures (422)

All of these return the same body:

```
422 Unprocessable Entity
{ "errorCode": "VAL-422", "message": "Invalid input" }
```

Only the log line differs.

**Missing field.** `POST /auth/login` with a username and no password:

```
WARN [HttpExceptionFilter] Validation failed on POST /auth/login: ["password should not be empty"] (IP: 127.0.0.1)
```

**Wrong type.** `"password": 12345` instead of a string:

```
WARN [HttpExceptionFilter] Validation failed on POST /auth/login: ["password must be a string"] (IP: 127.0.0.1)
```

**Length rule broken.** An empty password, where 1 to 128 characters are allowed:

```
WARN [HttpExceptionFilter] Validation failed on POST /auth/login: ["password must be longer than or equal to 1 characters"] (IP: 192.168.1.100)
```

**Unknown fields.** Extra properties that are not part of the request DTO. This is worth watching for, since it is what a scanner or someone probing for mass-assignment bugs looks like:

```
WARN [HttpExceptionFilter] Validation failed on POST /auth/login: ["property extra_field should not exist","property another_field should not exist"] (IP: 192.168.1.50)
```

## Malformed JSON (400)

A body that is not valid JSON never reaches validation. The client gets the framework's 400:

```
400 Bad Request
{ "statusCode": 400, "message": "Unexpected end of JSON input", "error": "Bad Request" }
```

and the log shows:

```
WARN [HttpExceptionFilter] Validation failed on POST /auth/login: "Unexpected end of JSON input" (IP: 10.0.0.5)
```

A burst of these from one address usually means fuzzing.

## Unauthorized (401)

For example `POST /auth/refresh` with an invalid or expired refresh token:

```
401 Unauthorized
{ "errorCode": "AUTH-401", "message": "Unauthorised" }
```

```
WARN [HttpExceptionFilter] Unauthorized access attempt on POST /auth/refresh (IP: 203.0.113.42)
```

The response is the same whatever was wrong with the token. Only the log tells you the endpoint and who asked.

## Rate limited (429)

Logins are throttled per address (`LOGIN_THROTTLE_LIMIT` requests per `LOGIN_THROTTLE_TTL_SECONDS`, 5 per 900 seconds by default). The next request over the limit gets:

```
429 Too Many Requests
{ "errorCode": "RATE-429", "message": "Too many attempts, try again later" }
```

```
WARN [HttpExceptionFilter] Rate limited: POST /auth/login (IP: 203.0.113.42)
```

Every login counts toward the limit, successful or not, so repeated local testing can trigger this. Raise the limit in `.env` while testing.

## Conflict (409)

Registering a username or account that is already taken:

```
409 Conflict
{ "errorCode": "AUTH-409", "message": "Registration failed" }
```

```
WARN [HttpExceptionFilter] Conflict on POST /auth/register (IP: 203.0.113.42)
```

## Unhandled errors (500)

Anything the filter does not recognise, such as the database being down in the middle of a login:

```
500 Internal Server Error
{ "errorCode": "INT-500", "message": "Internal server error" }
```

```
ERROR [HttpExceptionFilter] Unhandled exception on POST /auth/login (IP: 203.0.113.42): connect ECONNREFUSED 127.0.0.1:5432
  at TCPConnectWrap.afterConnect [as oncomplete] (node:net:1329:25)
```

The stack trace is logged for debugging and is never sent to the client. Logging goes through `RedactingLogger`, which is there to keep passwords and tokens out of the output.

## Log levels

| Level | Used for |
|-------|----------|
| WARN | Validation failures, 401, 409 and 429 |
| ERROR | Unhandled exceptions and 500s |
| LOG | Normal operation, such as a token being issued |

## Searching the logs

Everything is plain text with the IP at the end of the line, so grep is enough to start with.

```bash
# validation failures on one endpoint
grep "Validation failed on POST /auth/login" app.log

# everything one address has done
grep "203.0.113.42" app.log

# who is being rate limited
grep "Rate limited" app.log
```

If the same address shows up with a run of 401s, 429s and 409s in a short time, it is probably someone guessing credentials or probing the API, and is a candidate for blocking at the firewall.

## What this gives us

- Failed requests are no longer silent. Fuzzing, scanning and brute-force attempts leave a visible pattern.
- Repeat offenders can be identified by IP and blocked.
- There is a record of failed authentication attempts with a time and an address for investigations.
- Support questions like "I can't log in" are quick to answer. A line such as `password must be a string` shows the client is sending the password as a number.

## Shipping the logs

For a deployed environment, send the output to whatever aggregator you use (CloudWatch, ELK, Datadog). If you configure structured JSON logging, a useful shape would be:

```json
{
  "timestamp": "2026-09-25T13:42:15.000Z",
  "level": "WARN",
  "service": "auth-service",
  "component": "HttpExceptionFilter",
  "message": "Validation failed on POST /auth/login",
  "details": ["password should not be empty"],
  "clientIp": "203.0.113.42",
  "statusCode": 422
}
```

The service currently writes the plain-text lines shown above, not this JSON. Retention and alert thresholds are decisions for whoever runs the deployment; nothing in the repo sets them.
