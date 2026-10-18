import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { LeavePageConfirmService } from './core/guards/leave-page-confirm.service';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('uses the custom confirm when Ctrl+R is pressed with unsaved changes', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const leavePageConfirm = TestBed.inject(LeavePageConfirmService);
    vi.spyOn(leavePageConfirm, 'hasUnsavedChanges').mockReturnValue(true);
    const requestSpy = vi.spyOn(leavePageConfirm, 'requestLeave').mockResolvedValue(false);

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'r', ctrlKey: true, cancelable: true }));

    expect(requestSpy).toHaveBeenCalledTimes(1);
  });
});
