package dev.ohs.player.configs;

import ca.uhn.fhir.context.FhirContext;
import dev.ohs.player.bulk.BulkLocationImportServlet;
import dev.ohs.player.bulk.BulkOrgImportServlet;
import dev.ohs.player.bulk.BulkUserImportServlet;
import dev.ohs.player.bulk.CsvProcessor;
import dev.ohs.player.bulk.SseResponseHelper;
import dev.ohs.player.endpoints.GroupManagementServlet;
import dev.ohs.player.endpoints.PractitionerDetailsServlet;
import dev.ohs.player.endpoints.RolesServlet;
import dev.ohs.player.endpoints.UserManagementServlet;
import dev.ohs.player.fhir.LocationService;
import dev.ohs.player.fhir.OrganizationService;
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

  private static final int DEFAULT_BULK_IMPORT_BATCH_SIZE = 5;

  @Autowired FhirContext fhirContext;

  @Autowired IamProviderService iamProviderService;

  @Autowired PractitionerService practitionerService;

  @Autowired PractitionerDetailService practitionerDetailService;

  @Autowired OrganizationService organizationService;

  @Autowired LocationService locationService;

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
  public ServletRegistrationBean<BulkOrgImportServlet> bulkOrgImportServlet() {
    // BULK_IMPORT_BATCH_SIZE: number of rows per FHIR BATCH bundle.
    // Default is 5 (suitable for testing). Change to 50 for production deployments.
    // See README for performance guidance.
    String batchSizeEnv = System.getenv("BULK_IMPORT_BATCH_SIZE");
    int batchSize = DEFAULT_BULK_IMPORT_BATCH_SIZE;
    if (batchSizeEnv != null && !batchSizeEnv.isBlank()) {
      try {
        batchSize = Integer.parseInt(batchSizeEnv.trim());
      } catch (NumberFormatException e) {
        logger.warn(
            "Invalid BULK_IMPORT_BATCH_SIZE '{}', using default {}", batchSizeEnv, batchSize);
      }
    }
    ServletRegistrationBean<BulkOrgImportServlet> bean =
        new ServletRegistrationBean<>(
            new BulkOrgImportServlet(
                organizationService, csvProcessor(), sseResponseHelper(), batchSize),
            "/api/bulk-import/organizations");
    bean.setMultipartConfig(new MultipartConfigElement("/tmp", 52428800, 52428800, 0));
    return bean;
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
  public ServletRegistrationBean<BulkLocationImportServlet> bulkLocationImportServlet() {
    String batchSizeEnv = System.getenv("BULK_IMPORT_BATCH_SIZE");
    int batchSize = DEFAULT_BULK_IMPORT_BATCH_SIZE;
    if (batchSizeEnv != null && !batchSizeEnv.isBlank()) {
      try {
        batchSize = Integer.parseInt(batchSizeEnv.trim());
      } catch (NumberFormatException e) {
        logger.warn(
            "Invalid BULK_IMPORT_BATCH_SIZE '{}', using default {}", batchSizeEnv, batchSize);
      }
    }
    ServletRegistrationBean<BulkLocationImportServlet> bean =
        new ServletRegistrationBean<>(
            new BulkLocationImportServlet(
                locationService,
                organizationService,
                csvProcessor(),
                sseResponseHelper(),
                batchSize),
            "/api/bulk-import/locations");
    bean.setMultipartConfig(new MultipartConfigElement("/tmp", 52428800, 52428800, 0));
    return bean;
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
