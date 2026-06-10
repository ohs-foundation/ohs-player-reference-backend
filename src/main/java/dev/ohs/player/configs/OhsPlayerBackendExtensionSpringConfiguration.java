package dev.ohs.player.configs;

import ca.uhn.fhir.context.FhirContext;
import dev.ohs.player.bulk.BulkUserImportServlet;
import dev.ohs.player.bulk.CsvProcessor;
import dev.ohs.player.bulk.SseResponseHelper;
import dev.ohs.player.endpoints.GroupManagementServlet;
import dev.ohs.player.endpoints.PractitionerDetailsServlet;
import dev.ohs.player.endpoints.RolesServlet;
import dev.ohs.player.endpoints.UserManagementServlet;
import dev.ohs.player.fhir.PractitionerDetailService;
import dev.ohs.player.fhir.PractitionerService;
import dev.ohs.player.iam.IamProviderService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.MultipartConfigElement;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

// ServletRegistrationBean is the replacement for @WebServlet which may not be scanned by Spring.
// This class is meant for registration of servlet beans. Other beans are registered in
// OtherConfigs.class
@Configuration
@Import(OtherConfigs.class)
public class OhsPlayerBackendExtensionSpringConfiguration {

  private static final Logger logger =
      org.slf4j.LoggerFactory.getLogger(OhsPlayerBackendExtensionSpringConfiguration.class);

  @Autowired FhirContext fhirContext;

  @Autowired IamProviderService iamProviderService;

  @Autowired PractitionerService practitionerService;

  @Autowired PractitionerDetailService practitionerDetailService;

  @Bean
  public ServletRegistrationBean<UserManagementServlet> userManagementServlet() {
    return new ServletRegistrationBean<>(
        new UserManagementServlet(iamProviderService, practitionerService, fhirContext),
        "/api/users/*");
  }

  @Bean
  public ServletRegistrationBean<GroupManagementServlet> groupManagementServlet() {
    return new ServletRegistrationBean<>(
        new GroupManagementServlet(iamProviderService), "/api/groups/*");
  }

  @Bean
  public ServletRegistrationBean<RolesServlet> rolesServlet() {
    return new ServletRegistrationBean<>(new RolesServlet(iamProviderService), "/api/roles/*");
  }

  @Bean
  public ServletRegistrationBean<PractitionerDetailsServlet> practitionerDetailsServlet() {
    return new ServletRegistrationBean<>(
        new PractitionerDetailsServlet(practitionerDetailService, fhirContext),
        "/api/practitioner-details");
  }

  @Bean
  public CsvProcessor csvProcessor() {
    return new CsvProcessor();
  }

  @Bean
  public SseResponseHelper sseResponseHelper() {
    return new SseResponseHelper();
  }

  @Bean
  public ServletRegistrationBean<BulkUserImportServlet> bulkUserImportServlet() {
    ServletRegistrationBean<BulkUserImportServlet> bean =
        new ServletRegistrationBean<>(
            new BulkUserImportServlet(
                iamProviderService, practitionerService, csvProcessor(), sseResponseHelper()),
            "/api/bulk-import/users");
    bean.setMultipartConfig(new MultipartConfigElement("/tmp", 52428800, 52428800, 0));
    return bean;
  }

  @PostConstruct
  public void init() {
    logger.info(
        ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> CUSTOM PLUGINS CONFIGURATION LOADED!");
  }
}
