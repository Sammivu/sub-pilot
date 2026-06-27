package co.subpilot.nomba;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;


/**
 * Registers NombaApiProperties for binding from subpilot.nomba.* config.
 *
 * The real Nomba integration (NombaApiClient, NombaAuthTokenManager) builds
 * its own internal WebClient rather than consuming a Spring-managed
 * WebClient/RestClient bean — there's intentionally no shared HTTP client
 * bean here. Keeping the client construction inside NombaApiClient /
 * NombaAuthTokenManager (rather than exposing it as a general-purpose bean)
 * avoids another module accidentally reusing a Nomba-specific client
 * (baseUrl, default headers) for something unrelated.
 */
@Configuration
@EnableConfigurationProperties(NombaApiProperties.class)
public class NombaConfig {

}