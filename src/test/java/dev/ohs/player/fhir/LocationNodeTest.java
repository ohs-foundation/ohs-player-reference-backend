package dev.ohs.player.fhir;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LocationNodeTest {

  @Test
  void children_DefaultsToEmptyList() {
    assertTrue(new LocationNode().getChildren().isEmpty());
  }

  @Test
  void setChildren_NullCoalescesToEmptyList() {
    LocationNode node = new LocationNode();

    node.setChildren(null);

    assertNotNull(node.getChildren());
    assertTrue(node.getChildren().isEmpty());
  }
}
