import { Component, computed, input, model } from '@angular/core';

import { createMatcher } from '../../core/search/text-matcher';

/**
 * The search box used above lists and tables. Plain text by default; the `.*` button switches to a regular
 * expression (see createMatcher for the exact rules). The parent binds `query` and `regex` with two-way
 * binding and builds its own matcher from them, so filtering stays with the page that owns the data.
 */
@Component({
  selector: 'app-search-field',
  template: `
    <div class="lt-search" [class.is-invalid]="error()" [style.width]="width()">
      <span class="lt-search__icon" aria-hidden="true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
          <circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.5-3.5" stroke-linecap="round"></path>
        </svg>
      </span>
      <input class="lt-input" type="search" autocomplete="off" spellcheck="false"
        [attr.data-testid]="testId()" [attr.aria-label]="label()" [attr.placeholder]="regex() ? regexPlaceholder() : placeholder()"
        [attr.aria-invalid]="error() ? true : null" [attr.aria-describedby]="error() ? errorId() : null"
        [value]="query()" (input)="query.set($any($event.target).value)" (keydown.escape)="query.set('')" />
      <button type="button" class="lt-search__mode" [attr.data-testid]="testId() + '-regex'"
        [attr.aria-pressed]="regex()" [attr.title]="regex() ? 'Regular expression on. Click for plain text.' : 'Plain text. Click to search with a regular expression.'"
        aria-label="Search with a regular expression" (click)="regex.set(!regex())">.*</button>
      @if (error()) {
        <span class="lt-search__error" [id]="errorId()" [attr.data-testid]="errorId()" role="status">{{ error() }}</span>
      }
    </div>
  `
})
export class SearchField {
  readonly query = model('');
  readonly regex = model(false);
  readonly label = input('Search');
  readonly placeholder = input('Search');
  readonly regexPlaceholder = input('Regex, e.g. ^(TCS|INFY)');
  readonly testId = input('search');
  readonly width = input<string | null>(null);

  readonly errorId = computed(() => this.testId() + '-error');
  readonly error = computed(() => createMatcher(this.query(), this.regex()).error);
}
