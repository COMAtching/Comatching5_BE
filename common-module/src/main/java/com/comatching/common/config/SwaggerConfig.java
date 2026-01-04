package com.comatching.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

	@Bean
	public OpenAPI openAPI() {
		String securitySchemeName = "session-auth";

		return new OpenAPI()
			.components(new Components()
				.addSecuritySchemes(securitySchemeName,
					new SecurityScheme()
						.type(SecurityScheme.Type.APIKEY)
						.in(SecurityScheme.In.COOKIE)
						.name("JSESSIONID")
				)
			)
			// 모든 api는 JSESSIONID 필요, @SecurityRequirements(value = {}) 를 붙여서 특정 api 잠금 해제
			.addSecurityItem(new SecurityRequirement().addList(securitySchemeName))

			.info(new Info()
				.title("Comatching API")
				.description("Comatching 서비스 API 명세서")
				.version("1.0.0"));
	}
}