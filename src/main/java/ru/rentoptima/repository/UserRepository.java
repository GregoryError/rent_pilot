package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.User;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u JOIN FETCH u.tenant WHERE u.username = :username AND u.active = true")
    Optional<User> findByUsernameActive(String username);

    java.util.Optional<ru.rentoptima.entity.User> findByEmail(String email);

    /** Активный пользователь по адресу почты без учёта регистра — для восстановления пароля. */
    @Query("SELECT u FROM User u JOIN FETCH u.tenant WHERE lower(u.email) = lower(:email) AND u.active = true")
    Optional<User> findActiveByEmail(String email);

    java.util.Optional<ru.rentoptima.entity.User> findByEmailVerificationToken(String token);

    java.util.Optional<ru.rentoptima.entity.User> findByPasswordResetToken(String token);
}
