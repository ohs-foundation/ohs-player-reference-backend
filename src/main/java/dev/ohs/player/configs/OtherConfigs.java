package dev.ohs.player.configs;

import ca.uhn.fhir.context.FhirContext;
import dev.ohs.player.fhir.OrganizationService;
import dev.ohs.player.fhir.PractitionerDetailService;
import dev.ohs.player.fhir.PractitionerService;
import dev.ohs.player.iam.IamProviderService;
import dev.ohs.player.iam.keycloak.KeycloakIamProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtherConfigs {
  private static final String PROXY_TO_ENV = "PROXY_TO";
  private static final String TOKEN_ISSUER_ENV = "TOKEN_ISSUER";
  private static final String IAM_PROVIDER_ENV = "IAM_PROVIDER";

  @Bean
  @ConditionalOnMissingBean(FhirContext.class)
  public FhirContext fhirContext() {
    return FhirContext.forR4Cached();
  }

  @Value("${iam.provider.client-id:}")
  private String iamProviderClientId;

  @Value("${iam.provider.client-secret:}")
  private String iamProviderClientSecret;

  @Bean
  public IamProviderService iamProviderService() {
    String provider = System.getenv(IAM_PROVIDER_ENV);
    if (provider == null || provider.isBlank()) {
      provider = "keycloak";
    }
    switch (provider) {
      case "keycloak":
        return buildKeycloakProvider();
      default:
        throw new IllegalStateException("Unknown IAM_PROVIDER: " + provider);
    }
  }

  private IamProviderService buildKeycloakProvider() {
    String tokenIssuer = System.getenv(TOKEN_ISSUER_ENV);
    if (tokenIssuer == null || tokenIssuer.isBlank()) {
      throw new IllegalStateException("TOKEN_ISSUER environment variable is not set");
    }
    if (iamProviderClientId.isBlank()) {
      throw new IllegalStateException("IAM_PROVIDER_CLIENT_ID environment variable is not set");
    }
    if (iamProviderClientSecret.isBlank()) {
      throw new IllegalStateException("IAM_PROVIDER_CLIENT_SECRET environment variable is not set");
    }
    String[] issuerParts = parseTokenIssuer(tokenIssuer);
    return new KeycloakIamProvider(
        issuerParts[0], issuerParts[1], iamProviderClientId, iamProviderClientSecret);
  }

  /**
   * Parses TOKEN_ISSUER (e.g. http://keycloak:8080/realms/my-realm) into [serverUrl, realm]. Works
   * for both legacy (/auth/realms/...) and modern Keycloak URL formats.
   */
  private String[] parseTokenIssuer(String tokenIssuer) {
    String normalized =
        tokenIssuer.endsWith("/")
            ? tokenIssuer.substring(0, tokenIssuer.length() - 1)
            : tokenIssuer;
    int lastSlash = normalized.lastIndexOf('/');
    String realm = normalized.substring(lastSlash + 1);
    String withoutRealm = normalized.substring(0, lastSlash);
    String serverUrl = withoutRealm.substring(0, withoutRealm.lastIndexOf('/'));
    return new String[] {serverUrl, realm};
  }

  @Bean
  public PractitionerService practitionerService() {
    String fhirServerUrl = System.getenv(PROXY_TO_ENV);
    if (fhirServerUrl == null || fhirServerUrl.isBlank()) {
      throw new IllegalStateException("PROXY_TO environment variable is not set");
    }
    return new PractitionerService(fhirContext(), fhirServerUrl);
  }

  @Bean
  public OrganizationService organizationService() {
    String fhirServerUrl = System.getenv(PROXY_TO_ENV);
    if (fhirServerUrl == null || fhirServerUrl.isBlank()) {
      throw new IllegalStateException("PROXY_TO environment variable is not set");
    }
    return new OrganizationService(fhirContext(), fhirServerUrl);
  }

  @Bean
  public PractitionerDetailService practitionerDetailService() {
    String fhirServerUrl = System.getenv(PROXY_TO_ENV);
    if (fhirServerUrl == null || fhirServerUrl.isBlank()) {
      throw new IllegalStateException("PROXY_TO environment variable is not set");
    }
    return new PractitionerDetailService(fhirContext(), fhirServerUrl);
  }
}
