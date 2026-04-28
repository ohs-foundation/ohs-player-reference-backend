package dev.ohs.player.configs;

import ca.uhn.fhir.context.FhirContext;
import dev.ohs.player.endpoints.MyOwnFhirDataServlet;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

//This (Servlet Registration Bean) is the replacement for @WebServlet annotation which may not be scanned and spring shuns.
@Configuration
@Import(OtherConfigs.class)
public class CustomPluginSpringConfiguration {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(CustomPluginSpringConfiguration.class);

    @Autowired
    FhirContext fhirContext;

    @Bean
    public ServletRegistrationBean<MyOwnFhirDataServlet> myServlet() {
        return new ServletRegistrationBean<>(
                new MyOwnFhirDataServlet(fhirContext),
                "/custom-link/my-own-fhir-data/*"
        );
    }

    @PostConstruct
    public void init() {
        logger.info(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> CUSTOM PLUGINS CONFIGURATION LOADED!");
    }
}