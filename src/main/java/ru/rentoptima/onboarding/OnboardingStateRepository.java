package ru.rentoptima.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

/** Ключ — id пользователя: чужое состояние по своему id не достать. */
public interface OnboardingStateRepository extends JpaRepository<OnboardingState, Long> {
}
