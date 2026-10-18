package com.trading.api.extensions.assistant;

import com.trading.api.extensions.TokenAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * The read-only assistant. It can answer questions and summarise the caller's own holdings and the latest
 * stored prices. It places, cancels and changes nothing, and it never returns balances or amounts.
 */
@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request, Principal principal) {
        return ResponseEntity.ok(assistantService.chat(TokenAccount.of(principal), request.message()));
    }
}
