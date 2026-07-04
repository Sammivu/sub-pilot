package co.subpilot.internal.admin.service;

import co.subpilot.internal.admin.entity.InternalAdmin;
import co.subpilot.internal.admin.repository.InternalAdminRepository;
import co.subpilot.internal.admin.security.InternalAdminJwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class InternalAdminAuthService {

    private final InternalAdminRepository internalAdminRepository;
    private final InternalAdminJwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public record LoginResult(String token, InternalAdmin admin) {}

    @Transactional
    public LoginResult login(String email, String rawPassword) {
        InternalAdmin admin = internalAdminRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password."));

        if (!passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password.");
        }

        admin.setLastLoginAt(Instant.now());
        internalAdminRepository.save(admin);

        String token = jwtService.generateToken(admin.getId(), admin.getRole(), admin.getEmail());
        return new LoginResult(token, admin);
    }

    public InternalAdmin getById(String adminId) {
        return internalAdminRepository.findById(adminId)
                .orElseThrow(() -> new co.subpilot.common.exception.ResourceNotFoundException("internal_admin", adminId));
    }
}