package dev.ohs.player.fhir;

import ca.uhn.fhir.context.FhirContext;
import com.google.fhir.gateway.HttpFhirClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only seam through which the org-scoped access checker talks to the upstream FHIR store.
 *
 * <p>Every call goes through the Gateway's {@link HttpFhirClient}, which attaches the backend
 * credential. The client sends no {@code Accept} header, so {@code _format=json} is appended to
 * every URL. Callers must pass relative URLs whose query values are already URL-encoded.
 */
public class GatewayFhirSearch {

  private static final Logger logger = LoggerFactory.getLogger(GatewayFhirSearch.class);

  /**
   * Maximum number of pages {@link #searchAll} follows before failing.
   *
   * <p><b>Extension point — page limit.</b> This is a constant to keep the sample's configuration
   * small. To make it operator-settable, pass it in through the constructor from an
   * {@code @Value("${org-scope.max-pages:50}")} bean parameter.
   */
  static final int MAX_PAGES = 50;

  private static final String FORMAT_JSON = "_format=json";

  private final HttpFhirClient client;
  private final FhirContext fhirContext;

  public GatewayFhirSearch(HttpFhirClient client, FhirContext fhirContext) {
    this.client = client;
    this.fhirContext = fhirContext;
  }

  /**
   * Runs one search and returns its first page.
   *
   * @param relativeUrl a pre-encoded relative search URL without {@code _format}, e.g. {@code
   *     Patient?_id=123}
   * @throws UpstreamException if the request fails, the store returns an error status, or the body
   *     is not a Bundle
   */
  public Bundle search(String relativeUrl) {
    return fetch(withJsonFormat(relativeUrl));
  }

  /**
   * Runs a search and follows its {@code next} links, returning the resources of every page.
   *
   * @throws UpstreamException on any upstream failure, or if there are more than {@link #MAX_PAGES}
   *     pages; a partial result is never returned
   */
  public List<Resource> searchAll(String relativeUrl) {
    List<Resource> resources = new ArrayList<>();
    Bundle page = search(relativeUrl);
    int pages = 1;
    while (true) {
      page.getEntry().stream()
          .filter(Bundle.BundleEntryComponent::hasResource)
          .forEach(entry -> resources.add(entry.getResource()));
      String nextUrl = nextPageUrl(page);
      if (nextUrl == null) {
        return resources;
      }
      if (pages >= MAX_PAGES) {
        throw new UpstreamException(
            "Search " + relativeUrl + " returned more than " + MAX_PAGES + " pages");
      }
      page = fetch(nextUrl);
      pages++;
    }
  }

  /**
   * Turns the absolute {@code next} link into a URL relative to the store base. HAPI next links
   * have the form {@code <base>?_getpages=...}, so only the query string is kept.
   */
  private static @Nullable String nextPageUrl(Bundle page) {
    Bundle.BundleLinkComponent next = page.getLink(Bundle.LINK_NEXT);
    if (next == null || !next.hasUrl()) {
      return null;
    }
    try {
      String rawQuery = new URI(next.getUrl()).getRawQuery();
      if (rawQuery == null || rawQuery.isEmpty()) {
        throw new UpstreamException("Next page link has no query: " + next.getUrl());
      }
      return withJsonFormat("?" + rawQuery);
    } catch (URISyntaxException e) {
      throw new UpstreamException("Invalid next page link: " + next.getUrl(), e);
    }
  }

  private static String withJsonFormat(String url) {
    if (url.contains("_format=")) {
      return url;
    }
    return url + (url.contains("?") ? "&" : "?") + FORMAT_JSON;
  }

  private Bundle fetch(String url) {
    logger.debug("Org scope upstream search {}", url);
    HttpResponse response;
    try {
      response = client.getResource(url);
    } catch (IOException | RuntimeException e) {
      throw new UpstreamException("Upstream search failed: " + url, e);
    }
    HttpEntity entity = response.getEntity();
    try {
      int status = response.getStatusLine().getStatusCode();
      if (status >= 400) {
        throw new UpstreamException("Upstream search " + url + " returned status " + status);
      }
      if (entity == null) {
        throw new UpstreamException("Upstream search " + url + " returned no body");
      }
      IBaseResource resource;
      try (InputStream content = entity.getContent()) {
        resource = fhirContext.newJsonParser().parseResource(content);
      }
      if (!(resource instanceof Bundle)) {
        throw new UpstreamException(
            "Upstream search " + url + " returned " + resource.fhirType() + ", not a Bundle");
      }
      return (Bundle) resource;
    } catch (UpstreamException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new UpstreamException("Could not read upstream search response: " + url, e);
    } finally {
      EntityUtils.consumeQuietly(entity);
    }
  }

  /** Thrown for any failure to get a usable search result from the upstream store. */
  public static class UpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UpstreamException(String message) {
      super(message);
    }

    public UpstreamException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
