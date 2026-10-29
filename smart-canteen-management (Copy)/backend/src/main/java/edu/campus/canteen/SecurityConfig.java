package edu.campus.canteen;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;
@Configuration
public class SecurityConfig {
 @Bean SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
   return http.csrf(c -> c.disable()).cors(Customizer.withDefaults())
    .authorizeHttpRequests(a -> a.requestMatchers("/api/health","/actuator/health").permitAll()
      .requestMatchers(HttpMethod.GET,"/api/menu","/api/categories").permitAll()
      .anyRequest().authenticated())
    .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())).build();
 }
 @Bean CorsConfigurationSource corsConfigurationSource(@Value("${app.cors-origins}") String origins) {
   CorsConfiguration c = new CorsConfiguration(); c.setAllowedOrigins(List.of(origins.split(",")));
   c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
   c.setAllowedHeaders(List.of("Authorization","Content-Type","Accept")); c.setAllowCredentials(false);
   UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource(); s.registerCorsConfiguration("/**",c); return s;
 }
}
