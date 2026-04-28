package dev.ohs.player.configs;

import ca.uhn.fhir.context.FhirContext;
import com.google.fhir.gateway.interfaces.AccessCheckerFactory;
import dev.ohs.player.plugins.OpenAccessChecker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtherConfigs {

    @Bean
    @ConditionalOnMissingBean(FhirContext.class)
    public FhirContext fhirContext() {
        return FhirContext.forR4Cached();
    }

    //Note, this Bean name should match the @Named of your custom Access Checker plugin factory
    @Bean(name = "open-sesame")
    public AccessCheckerFactory openAccessCheckerFactory() {
        return new OpenAccessChecker.Factory();
    }
}
