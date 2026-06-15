package dev.ohs.player.bulk;

/**
 * Internal signal thrown when a row's parent org is in the current accumulating batch. In a FHIR
 * BATCH bundle entries are processed independently — urn:uuid cross-references are not resolved.
 * The caller must flush the current batch first so the parent is committed, then retry the row.
 */
class IntraBatchFlushSignal extends RuntimeException {
  IntraBatchFlushSignal() {
    super(null, null, true, false);
  }
}
