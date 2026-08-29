package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.FeedbackResponseRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.time.LocalDate;

@Controller
@RequestMapping("/housekeeper")
@RequiredArgsConstructor
public class HousekeeperController {

    private final PropertyRepository propertyRepo;
    private final BookingRepository bookingRepo;
    private final FeedbackResponseRepository feedbackRepo;

    @GetMapping("/{code}")
    public String housekeeperPage(@PathVariable String code, Model model) {
        Property property = propertyRepo.findByHousekeeperCode(code).orElse(null);
        if (property == null) return "error/404";

        LocalDate now = LocalDate.now();
        var upcoming = bookingRepo.findUpcomingCheckouts(property.getId(), now);
        var reviews = feedbackRepo.findVisibleToHousekeeper(property.getId());

        model.addAttribute("property", property);
        model.addAttribute("bookings", upcoming);
        model.addAttribute("reviews", reviews);
        model.addAttribute("today", now);

        return "pages/housekeeper/index";
    }
}
