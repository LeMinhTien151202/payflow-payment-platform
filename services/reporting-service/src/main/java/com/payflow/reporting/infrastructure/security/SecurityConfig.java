package com.payflow.reporting.infrastructure.security;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig{
 @Bean SecurityFilterChain filterChain(HttpSecurity http)throws Exception{
  return http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/actuator/health/**").permitAll()
    .requestMatchers("/swagger-ui.html","/swagger-ui/**","/v3/api-docs/**").permitAll()
    .requestMatchers("/api/v1/operations/reporting/**").hasAuthority("SCOPE_reporting:rebuild")
    .requestMatchers("/api/v1/reports/**").hasAuthority("SCOPE_reporting:read")
    .anyRequest().denyAll()).oauth2ResourceServer(o->o.jwt(Customizer.withDefaults())).build();
 }
}
