package ru.rentoptima.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String login() {
        return "pages/auth/login";
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }
}
