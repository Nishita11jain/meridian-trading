# features/blotter

The order-history table: newest first, rejections included, one status badge per row carrying the word
as well as the colour. While any order is at `NEW` it re-reads history every 4 seconds, at most 20
times, stopping as soon as nothing is at `NEW`; it then says so on screen and offers Refresh. It never
re-posts an order.
