package dev.uchar.regelspraak.toolchain.service;

/**
 * Startup configuration for the service core.
 *
 * @param allowMarkers when {@code false} (the default, the strict production posture), an in-scope construct
 *                     that the engine could not lower to a live rule (a marker / {@code overgeslagen}) is a
 *                     startup violation naming the rule and the core refuses to start; when {@code true}, the
 *                     core starts and exposes the marker list (fase 3 surfaces it on {@code /info}). This is
 *                     the exit-3 MARKER discipline pushed to the service boundary.
 */
public record KernConfig(boolean allowMarkers) {

    /** The strict default: markers refuse startup. */
    public static KernConfig strikt() { return new KernConfig(false); }

    /** The permissive posture: start with markers, expose them. */
    public static KernConfig metMarkers() { return new KernConfig(true); }
}
