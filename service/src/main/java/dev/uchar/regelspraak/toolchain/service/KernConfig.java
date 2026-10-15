package dev.uchar.regelspraak.toolchain.service;

/**
 * Startup configuration for the service core.
 *
 * @param allowMarkers when {@code false} (the default, the strict production posture), an in-scope construct
 *                     that the engine could not lower to a live rule (a marker / {@code overgeslagen}) is a
 *                     startup violation naming the rule and the core refuses to start; when {@code true}, the
 *                     core starts and exposes the marker list (fase 3 surfaces it on {@code /info}). This is
 *                     the exit-3 MARKER discipline pushed to the service boundary.
 * @param detail       when {@code true}, a request may ask to see its session beside the answer: the firings
 *                     ({@code trace}), the coverage of the entry point's forms ({@code dekking}) and the fixpoint
 *                     ({@code fixpunt}). Off by default: they show every intermediate value, where the answer gives the
 *                     selected {@code uitvoer} alone, so a request that asks them of a core started without is a 400.
 */
public record KernConfig(boolean allowMarkers, boolean detail) {

    /** The strict default: markers refuse startup. */
    public static KernConfig strikt() { return new KernConfig(false, false); }

    /** The permissive posture: start with markers, expose them. */
    public static KernConfig metMarkers() { return new KernConfig(true, false); }

    /** This configuration with the details of a session on request. */
    public KernConfig metDetail() { return new KernConfig(allowMarkers, true); }
}
