package co.subpilot.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {


        @Bean
        public OpenAPI subPilotOpenAPI() {

            final String securitySchemeName = "ApiKey";

            return new OpenAPI()
                    .info(new Info()
                            .title("SubPilot API")
                            .version("v1")
                            .description("""
                                Managed recurring billing infrastructure
                                built on top of Nomba payment primitives.
                                """)
                            .contact(new Contact()
                                    .name("SubPilot")
                                    .email("hello@subpilot.co"))
                            .license(new License()
                                    .name("Commercial")))
                    .addSecurityItem(
                            new SecurityRequirement().addList(securitySchemeName)
                    )
                    .components(
                            new Components()
                                    .addSecuritySchemes(
                                            securitySchemeName,
                                            new SecurityScheme()
                                                    .type(SecurityScheme.Type.APIKEY)
                                                    .in(SecurityScheme.In.HEADER)
                                                    .name("X-API-KEY")
                                    )
                    );
        }

}