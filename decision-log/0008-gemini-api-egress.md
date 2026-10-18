# 0008 The Finny assistant sends a description of the customer's holdings to Google

| Field | Value |
|---|---|
| Status | accepted |
| Date | 2026-10-06 |
| Decided by | Team 5 |

## Context

Finny, the chat assistant, answers questions about a customer's portfolio using Google's Gemini API. That means some customer data leaves the platform for a third party, and the key for that API has to live somewhere.

## Options considered

| Option | For | Against |
|---|---|---|
| Call Gemini from the server, send only a limited description | The key never reaches the browser, and the server decides what Google sees | Customer-derived text still goes to Google |
| Call Gemini from the browser | Less server code | The key would ship in the bundle |
| Leave the assistant out | No egress at all | Drops a feature we wanted to show |

## Decision

The browser calls `POST /api/v1/assistant/chat`; the server calls `generativelanguage.googleapis.com` with the key in a request header. What goes to Google is built in `AssistantContext`: the symbols held, whether each is above or below its average cost, whether one is the largest holding or dominates the portfolio, and a stock's own price move. No cash, balance, market value, profit or loss amount, name or contact detail is included. With `GEMINI_API_KEY` empty the assistant answers that it is unavailable and nothing is sent. The route allows 10 requests a minute per account by default (`assistant.rate-limit-per-minute`).

## Consequences

- Google sees which symbols a customer holds. That is the part we accepted.
- The key is only ever in the server's environment.
- For the showcase the key can be left empty and nothing leaves the platform.
