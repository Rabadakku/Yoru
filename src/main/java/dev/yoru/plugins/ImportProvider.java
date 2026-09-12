package dev.yoru.plugins;
import java.time.Instant;
import java.util.*;
/** Future out-of-process adapter contract. No third-party code is loaded by v0.1. */
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
