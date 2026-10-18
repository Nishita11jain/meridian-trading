import { Component, Input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Statements } from './statements';
import { AppShell } from '../../shared/layout/app-shell';
import { AuthService } from '../../core/auth/auth.service';
import { StatementsService } from '../../core/statements/statements.service';

/** The real shell needs notifications and the assistant; the statements page is tested without them. */
@Component({ selector: 'app-shell', template: '<ng-content></ng-content>' })
class StubShell {
  @Input() title = '';
}

describe('Statements', () => {
  let statements: { trades: ReturnType<typeof vi.fn>; cash: ReturnType<typeof vi.fn>; downloadCsv: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    statements = {
      trades: vi.fn().mockReturnValue(of([
        { executedAt: '2026-10-05T10:30:00', orderId: 'a1b2c3d4-0000', symbol: 'MSFT', side: 'BUY', quantity: 10, price: 415.2, currency: 'USD', value: 4152 }
      ])),
      cash: vi.fn().mockReturnValue(of([
        { createdAt: '2026-10-05T10:30:00', type: 'BUY', amount: -4152, reference: 'a1b2' }
      ])),
      downloadCsv: vi.fn().mockReturnValue(of(new Blob(['x'])))
    };
    await TestBed.configureTestingModule({
      imports: [Statements],
      providers: [
        { provide: AuthService, useValue: { getProfile: () => of({ accountId: 4, username: 'x', roles: ['CUSTOMER'] }) } },
        { provide: StatementsService, useValue: statements }
      ]
    })
      .overrideComponent(Statements, { remove: { imports: [AppShell] }, add: { imports: [StubShell] } })
      .compileComponents();
  });

  it('shows the trades for the financial year, one row per fill', () => {
    const fixture = TestBed.createComponent(Statements);
    fixture.detectChanges();

    expect(statements.trades).toHaveBeenCalledWith(4, expect.stringMatching(/-04-01$/), expect.stringMatching(/-03-31$/));
    expect(fixture.nativeElement.querySelectorAll('[data-testid="statements-trade-row"]').length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('MSFT');
  });

  it('the cash tab loads the cash ledger, with money out shown as a negative amount', () => {
    const fixture = TestBed.createComponent(Statements);
    fixture.detectChanges();

    fixture.componentInstance.setTab('cash');
    fixture.detectChanges();

    expect(statements.cash).toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[data-testid="statements-cash-row"]').textContent).toContain('-4,152.00');
  });

  it('CSV download asks the server for the statement of the current tab', () => {
    const fixture = TestBed.createComponent(Statements);
    fixture.detectChanges();

    fixture.componentInstance.downloadCsv();

    expect(statements.downloadCsv).toHaveBeenCalledWith(4, 'trades', expect.any(String), expect.any(String));
  });
});
