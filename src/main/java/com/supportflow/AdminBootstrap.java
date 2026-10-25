package com.supportflow;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AdminBootstrap implements CommandLineRunner {
    record AdminEmail(@NotBlank @Email String value) {}
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Validator validator;
    private final String email, password;

    AdminBootstrap(UserRepository users, PasswordEncoder encoder, Validator validator,
                   @Value("${app.bootstrap.admin-email:}") String email,
                   @Value("${app.bootstrap.admin-password:}") String password) {
        this.users=users; this.encoder=encoder; this.validator=validator;
        this.email=email.trim().toLowerCase(Locale.ROOT); this.password=password;
    }

    @Override @Transactional
    public void run(String... args) {
        if(email.isBlank() && password.isBlank()) return;
        if(!validator.validate(new AdminEmail(email)).isEmpty() || email.length()>254 || password.isBlank()
            || password.length()<10 || password.getBytes(StandardCharsets.UTF_8).length>72) {
            throw new IllegalArgumentException("Set a valid BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD (10 characters minimum, 72 UTF-8 bytes maximum)");
        }
        var existing=users.findByEmail(email);
        if(existing.isPresent()) {
            if(existing.get().role!=Role.ADMIN) throw new IllegalStateException("Bootstrap email already belongs to a non-admin user");
            return;
        }
        if(!users.findByRole(Role.ADMIN).isEmpty()) throw new IllegalStateException("An admin already exists; remove bootstrap credentials");
        users.save(new AppUser(email,encoder.encode(password),Role.ADMIN));
    }
}
