# core/config

`api-config.ts` is the one place that names where the platform APIs are and which requests may carry
the bearer token: the API base URLs given to the generated clients, the platform API path prefixes
and exact paths the interceptor allows, and the public auth paths that never get a header.

Origins and paths only. Never a key, token or secret: this file ships to the browser. See the
README at the root of `frontend` for how the dev proxy and a production deployment differ.
