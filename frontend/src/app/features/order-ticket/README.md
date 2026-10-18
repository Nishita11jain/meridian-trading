# features/order-ticket

The order entry form: instrument, side, quantity, price, and a read-only account taken from
`/auth/me`. Validates before submission (whole quantity above zero, price above zero with at most two
decimals, symbol in the contract's shape) and renders the returned status, `NEW` included, or the
mapped message for any error. One idempotency key is used per attempt, so a retry after no response
cannot place a second order.

If the user has typed an order and not sent it, leaving the page (a link, Back/Forward, or closing
or reloading the tab) asks first. Nothing is asked for an empty form or after the order is placed.
