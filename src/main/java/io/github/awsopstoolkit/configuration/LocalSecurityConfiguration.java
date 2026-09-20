package io.github.awsopstoolkit.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class LocalSecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, ToolkitProperties properties) throws Exception {
        var token = ("Bearer " + properties.localToken()).getBytes(StandardCharsets.UTF_8);
        var filter =
                new OncePerRequestFilter() {
                    @Override
                    protected boolean shouldNotFilterAsyncDispatch() {
                        return false;
                    }

                    @Override
                    protected boolean shouldNotFilterErrorDispatch() {
                        return false;
                    }

                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain chain)
                            throws IOException, ServletException {
                        var supplied = request.getHeader("Authorization");
                        // No browser UI: reject cross-origin/browser-triggered requests even with a
                        // token.
                        if (request.getHeader("Origin") != null
                                || supplied == null
                                || !MessageDigest.isEqual(
                                        token, supplied.getBytes(StandardCharsets.UTF_8))) {
                            response.setStatus(401);
                            return;
                        }
                        var context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        "local-operator",
                                        null,
                                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
                        SecurityContextHolder.setContext(context);
                        try {
                            chain.doFilter(request, response);
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    }
                };
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    org.springframework.security.core.userdetails.UserDetailsService noPasswordLogin() {
        return username -> {
            throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
                    "Only local bearer authentication is supported");
        };
    }
}
