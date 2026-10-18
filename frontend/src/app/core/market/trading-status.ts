import { InstrumentResponse } from '../../clients/watchlists-client/model/models';

/**
 * Wording for a market's trading status, from what the API says (tradingStatus, nextOpen). The UI keeps
 * no copy of the trading hours: the Trade API decides, and refuses closed-market orders with MKT-409.
 * Times are shown in the viewer's own time zone.
 */
export function isClosed(instrument: Pick<InstrumentResponse, 'tradingStatus'> | null | undefined): boolean {
  return instrument?.tradingStatus === 'CLOSED';
}

/** "Mon 5 Oct, 09:15", in the viewer's time zone; '' when there is no time. */
export function opensAt(nextOpen: string | null | undefined): string {
  if (!nextOpen) {
    return '';
  }
  const when = new Date(nextOpen);
  if (Number.isNaN(when.getTime())) {
    return '';
  }
  const day = when.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' }).replace(',', '');
  const time = when.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  return `${day}, ${time}`;
}

/** "TCS.NS trades on the NSE, which is closed now. It opens Mon 5 Oct, 09:15." */
export function closedMessage(instrument: Pick<InstrumentResponse, 'symbol' | 'market' | 'nextOpen'>): string {
  const when = opensAt(instrument.nextOpen);
  return `${instrument.symbol} trades on the ${instrument.market}, which is closed now.` + (when ? ` It opens ${when}.` : '');
}

/** The note for an order on a closed market: it is accepted, queued, and runs when the market opens. */
export function queuedMessage(instrument: Pick<InstrumentResponse, 'symbol' | 'market' | 'nextOpen'>): string {
  const when = opensAt(instrument.nextOpen);
  return `${instrument.symbol} trades on the ${instrument.market}, which is closed now. Your order will be queued and run when it opens` +
    (when ? ` (${when}).` : '.') + ' You can cancel it until then.';
}
