import { Injectable, signal } from '@angular/core';

export interface LeavePagePrompt {
  message: string;
}

interface PendingLeavePagePrompt extends LeavePagePrompt {
  resolve: (decision: boolean) => void;
}

@Injectable({ providedIn: 'root' })
export class LeavePageConfirmService {
  private readonly pendingPrompt = signal<PendingLeavePagePrompt | null>(null);
  private unsavedChangesChecker: (() => boolean) | null = null;
  private bypassNextBeforeUnload = false;

  readonly prompt = this.pendingPrompt.asReadonly();

  registerUnsavedChangesChecker(checker: () => boolean): () => void {
    this.unsavedChangesChecker = checker;
    return () => {
      if (this.unsavedChangesChecker === checker) {
        this.unsavedChangesChecker = null;
      }
    };
  }

  hasUnsavedChanges(): boolean {
    return this.unsavedChangesChecker?.() ?? false;
  }

  allowNextBeforeUnload(): void {
    this.bypassNextBeforeUnload = true;
  }

  consumeBeforeUnloadBypass(): boolean {
    if (!this.bypassNextBeforeUnload) {
      return false;
    }
    this.bypassNextBeforeUnload = false;
    return true;
  }

  requestLeave(message: string): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      // Only one prompt should be shown at a time; a newer navigation intent replaces the older one.
      this.pendingPrompt()?.resolve(false);
      this.pendingPrompt.set({ message, resolve });
    });
  }

  confirm(): void {
    this.resolve(true);
  }

  cancel(): void {
    this.resolve(false);
  }

  private resolve(decision: boolean): void {
    const pending = this.pendingPrompt();
    if (!pending) {
      return;
    }
    this.pendingPrompt.set(null);
    pending.resolve(decision);
  }
}