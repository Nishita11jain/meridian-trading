# core/orders

`order.service.ts`: our wrapper around the generated Trade clients (`OrdersService`,
`AccountsService`): place and cancel an order, and read the account, balance, positions and order
history. Screens call this, never the generated services directly.
