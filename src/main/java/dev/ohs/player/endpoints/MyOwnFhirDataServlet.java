package dev.ohs.player.endpoints;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class MyOwnFhirDataServlet extends HttpServlet {
    private final FhirContext fhirContext;
    private static final Logger logger = LoggerFactory.getLogger(MyOwnFhirDataServlet.class);

    public MyOwnFhirDataServlet(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
        logger.debug("Creating MyOwnFhirDataEndpoint");
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        String patientId = request.getParameter("patientId");
        if (patientId == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing patientId\"}");
            return;
        }

        String fhirServer = System.getenv("PROXY_TO");
        if (fhirServer == null || fhirServer.isBlank()) {
            logger.error("PROXY_TO is not set; cannot proxy patient lookup");
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"PROXY_TO is not configured\"}");
            return;
        }

        logger.debug("fhirServer={}", fhirServer);

        try {
            IGenericClient client = fhirContext.newRestfulGenericClient(fhirServer);
            Patient patient = client
                    .read()
                    .resource(Patient.class)
                    .withId(patientId)
                    .execute();

            String result = String.format(
                    "{\"patient\": \"%s\", \"status\": \"ok\"}",
                    fhirContext.newJsonParser().encodeResourceToString(patient));

            logger.debug(result);
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(fhirContext.newJsonParser().encodeResourceToString(patient));

        } catch (BaseServerResponseException e) {
            int statusCode = e.getStatusCode() > 0 ? e.getStatusCode() : HttpServletResponse.SC_BAD_GATEWAY;
            String responseBody = e.getResponseBody() == null ? "" : e.getResponseBody();
            String responseMimeType = e.getResponseMimeType() == null ? "application/fhir+json" : e.getResponseMimeType();
            logger.warn("Upstream FHIR error status={} for patientId={}", statusCode, patientId, e);
            response.setStatus(statusCode);
            response.setContentType(responseMimeType);
            response.getWriter().write(responseBody);
        } catch (Exception e) {
            logger.error("Failed to proxy patient read for patientId={} via PROXY_TO={}", patientId, fhirServer, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Internal server error\"}");
        }
    }
}