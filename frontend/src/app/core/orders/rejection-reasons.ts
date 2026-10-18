/**
 * Plain-language text for the reason codes a REJECTED order carries (contracts/trade-api.yaml,
 * OrderHistoryEntry.rejectionReason). Shared by the blotter and the notification detail, so a
 * rejection reads the same wherever it is shown. An unknown code is shown as it is.
 */
const REJECTION_REASON_TEXT: Record<string, string> = {
  INSUFFICIENT_FUNDS: 'Not enough cash',
  INSUFFICIENT_HOLDINGS: 'Not enough shares to sell',
  PRICE_NOT_MET: 'Price not met: the limit was beyond the market price',
  ACCOUNT_SUSPENDED: 'Account not active',
  INSTRUMENT_NOT_TRADABLE: 'Instrument not tradable',
  PRICING_UNAVAILABLE: 'No market price was available',
  MARKET_CLOSED: 'The market had closed by the time the order was processed'
};

export function rejectionReasonText(reason: string | null | undefined): string {
  if (!reason) {
    return 'Reason not recorded';
  }
  return REJECTION_REASON_TEXT[reason] ?? reason;
}
