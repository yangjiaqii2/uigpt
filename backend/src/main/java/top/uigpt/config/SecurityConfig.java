package top.uigpt.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import top.uigpt.dto.ApiError;
import top.uigpt.security.JwtAuthenticationFilter;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        http.cors(Customizer.withDefaults());
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(
                auth ->
                        auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                                .permitAll()
                                .requestMatchers("/error")
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, "/")
                                .permitAll()
                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/api/register/captcha",
                                        "/api/register/options")
                                .permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/register")
                                .permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/login")
                                .permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/forgot-password/reset")
                                .permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/logout")
                                .permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/chat", "/api/chat/stream")
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/chat/models")
                                .permitAll()
                                .anyRequest()
                                .authenticated());
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        http.exceptionHandling(
                ex ->
                        ex.authenticationEntryPoint(
                                        (request, response, authException) ->
                                                writeJson(
                                                        response,
                                                        HttpServletResponse.SC_UNAUTHORIZED,
                                                        "未登录或令牌无效"))
                                .accessDeniedHandler(
                                        (request, response, accessDeniedException) ->
                                                writeJson(
                                                        response,
                                                        HttpServletResponse.SC_FORBIDDEN,
                                                        "禁止访问")));
        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(message));
    }

    /** 与 {@link WebConfig#addCorsMappings} 对齐，供 Spring Security CORS 过滤器使用。 */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(List.of("*"));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }
}
