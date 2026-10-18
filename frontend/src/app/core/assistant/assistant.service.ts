import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** The assistant's answer to one question. The server reads the account from the token, never from here. */
export interface AssistantReply {
  reply: string;
}

/**
 * Sends one question to the read-only assistant on order-service. The Gemini key lives on the server;
 * the browser only sends the question and the session token the auth interceptor already adds.
 */
@Injectable({ providedIn: 'root' })
export class AssistantService {
  private readonly http = inject(HttpClient);

  ask(message: string): Observable<AssistantReply> {
    return this.http.post<AssistantReply>('/api/v1/assistant/chat', { message });
  }
}
