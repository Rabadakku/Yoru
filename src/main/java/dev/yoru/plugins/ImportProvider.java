package dev.yoru.plugins;
import java.time.Instant;
import java.util.*;
/**
 * The contract planned for out-of-process import adapters after 1.0 (see the
 * roadmap). Nothing implements or loads it, and no third-party code runs.
 */
public interface ImportProvider {
    record Manifest(String id,int apiVersion,Set<String> requestedCapabilities) {
        public Manifest {
            requestedCapabilities=Set.copyOf(requestedCapabilities);
        }
    }
    record Observation(String externalId, Instant observedAt, String unit, double value, String suggestedActivity) {
    }
    Manifest manifest();
    List<Observation> preview(byte[] userSelectedExport);
}
