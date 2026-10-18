import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { AssistantPanel } from './assistant-panel';
import { AssistantService } from '../../core/assistant/assistant.service';

describe('AssistantPanel', () => {
  let ask: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    ask = vi.fn().mockReturnValue(of({ reply: 'Your latest stored price is about 2.0% up.' }));
    await TestBed.configureTestingModule({
      imports: [AssistantPanel],
      providers: [{ provide: AssistantService, useValue: { ask } }]
    }).compileComponents();
  });

  function render() {
    const fixture = TestBed.createComponent(AssistantPanel);
    fixture.detectChanges();
    return fixture;
  }

  it('is closed until the launcher is pressed', () => {
    const fixture = render();
    const root: HTMLElement = fixture.nativeElement;

    expect(root.querySelector('[data-testid="assistant-drawer"]')).toBeNull();

    (root.querySelector('[data-testid="assistant-open"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(root.querySelector('[data-testid="assistant-drawer"]')).not.toBeNull();
  });

  it('says the answers are stored prices and that orders cannot be placed', () => {
    const fixture = render();
    (fixture.nativeElement.querySelector('[data-testid="assistant-open"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('latest stored prices, not live');
    expect(fixture.nativeElement.textContent).toContain('cannot place or change orders');
  });

  it('sends the trimmed question and shows the reply', () => {
    const fixture = render();
    fixture.componentInstance.open.set(true);
    fixture.componentInstance.draft = '  How do limit orders work?  ';

    fixture.componentInstance.send();
    fixture.detectChanges();

    expect(ask).toHaveBeenCalledWith('How do limit orders work?');
    const lines = fixture.componentInstance.lines();
    expect(lines).toEqual([
      { from: 'user', text: 'How do limit orders work?' },
      { from: 'assistant', text: 'Your latest stored price is about 2.0% up.' }
    ]);
    expect(fixture.componentInstance.draft).toBe('');
  });

  it('does not send an empty question', () => {
    const fixture = render();
    fixture.componentInstance.draft = '   ';

    fixture.componentInstance.send();

    expect(ask).not.toHaveBeenCalled();
  });

  it('turns a 429 into a plain message with no status code', () => {
    ask.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 429 })));
    const fixture = render();
    fixture.componentInstance.open.set(true);
    fixture.componentInstance.draft = 'anything';

    fixture.componentInstance.send();

    const last = fixture.componentInstance.lines().at(-1);
    expect(last?.text).toBe('You have asked a lot in the last minute. Try again shortly.');
    expect(last?.text).not.toContain('429');
  });

  it('says the assistant is unavailable on a 503 and leaves the rest of the app alone', () => {
    ask.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    const fixture = render();
    fixture.componentInstance.draft = 'anything';

    fixture.componentInstance.send();

    expect(fixture.componentInstance.lines().at(-1)?.text).toContain('unavailable right now');
    expect(fixture.componentInstance.sending()).toBe(false);
  });
});
