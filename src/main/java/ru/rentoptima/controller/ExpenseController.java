package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.ExpenseService;

import java.math.BigDecimal;
import java.time.LocalDate;

@Controller
@RequestMapping("/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;

    @GetMapping
    public String list(Model model) {
        Long tenantId = AuthContext.tenantId();
        LocalDate now = LocalDate.now();
        LocalDate from = now.withDayOfMonth(1);
        LocalDate to = now.plusMonths(1).withDayOfMonth(1).minusDays(1);

        model.addAttribute("activePage", "expenses");
        model.addAttribute("expenses", expenseService.getExpenses(tenantId, from, to));
        model.addAttribute("categories", expenseService.getCategories(tenantId));
        model.addAttribute("total", expenseService.getTotalExpenses(tenantId, from, to));
        model.addAttribute("month", now.getMonth().toString());

        return "pages/expenses/index";
    }

    @PostMapping
    public String create(@RequestParam Long propertyId,
                          @RequestParam Long categoryId,
                          @RequestParam BigDecimal amount,
                          @RequestParam String date,
                          @RequestParam(required = false) String comment,
                          RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        expenseService.createExpense(tenantId, propertyId, categoryId, amount, LocalDate.parse(date), comment);
        redirect.addFlashAttribute("success", "Расход добавлен");
        return "redirect:/expenses";
    }
}
