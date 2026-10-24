package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.rentoptima.auth.ServiceLock;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final ServiceLock serviceLock;

    /**
     * Вход. Закрыт выключателем «Сервис открыт» — поверх формы объявление, поля неактивны; по
     * ссылке «Вход для администратора» ({@code ?admin=1}) форма активна: администратор входит всегда.
     */
    @GetMapping("/login")
    public String login(@RequestParam(required = false) String admin, Model model) {
        boolean closed = !serviceLock.loginOpen();
        model.addAttribute("loginClosed", closed);
        model.addAttribute("adminMode", closed && "1".equals(admin));
        model.addAttribute("registrationOpen", serviceLock.registrationOpen());
        if (closed) model.addAttribute("serviceNotice", serviceLock.notice());
        return "pages/auth/login";
    }
}
