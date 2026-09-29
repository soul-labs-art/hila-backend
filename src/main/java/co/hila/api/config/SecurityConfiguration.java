package co.hila.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {

	@Bean
	UserDetailsService userDetailsServiceUntilIdentityModuleExists() {
		return username -> {
			throw new UsernameNotFoundException("No hay cuentas configuradas todavía.");
		};
	}

	@Bean
	SecurityFilterChain applicationSecurity(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(
								"/api/v1/health",
								"/actuator/health",
								"/actuator/health/**",
								"/v3/api-docs/**",
								"/swagger-ui/**",
								"/swagger-ui.html")
							.permitAll()
						.anyRequest().authenticated())
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.build();
	}
}
