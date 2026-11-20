package com.labreservation.accounts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AccountService accounts;
    private final boolean googleEnabled;

    public AuthController(AccountService accounts, @Value("${app.google.enabled}") boolean googleEnabled) {
        this.accounts = accounts;
        this.googleEnabled = googleEnabled;
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getToken(), googleEnabled));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountService.Account register(@Valid @RequestBody Registration request) {
        return accounts.register(request.email(), request.password());
    }

    @GetMapping("/me")
    public AccountService.Account me(Authentication authentication) {
        return accounts.account(Long.parseLong(authentication.getName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRegistration(MethodArgumentNotValidException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Enter a valid email and a password of 12 to 128 characters.");
        problem.setProperty("fields", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField()).distinct().toList());
        return problem;
    }

    public record Registration(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {
    }

    public record CsrfResponse(String headerName, String token, boolean googleEnabled) {
    }
}
