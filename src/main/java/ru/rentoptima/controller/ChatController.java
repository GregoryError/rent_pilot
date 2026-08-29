package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.AiChatService;

import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private final AiChatService aiChatService;

    @GetMapping("/chat")
    public String chatPage(Model model) {
        model.addAttribute("activePage", "chat");
        return "pages/chat/index";
    }

    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody ChatRequest request) {
        Long tenantId = AuthContext.tenantId();
        var response = aiChatService.chat(tenantId, request.message(), request.history());
        return ResponseEntity.ok(Map.of(
                "content", response.content(),
                "tokens", response.tokens()
        ));
    }

    public record ChatRequest(String message, List<Map<String, String>> history) {}
}
