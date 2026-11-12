package dev.uchar.regelspraak.toolchain.service.http;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;

import java.io.IOException;

/** {@link ServiceHttp} on an ephemeral port of 127.0.0.1 whose POST handling throws {@code fault}: the seam of its 500
 *  ({@link ServiceHttp#bindWith}), for the parity test of the Quarkus adapter in another module. */
public final class ExplodingService {

    private ExplodingService() {}

    public static ServiceHttp start(DecisionCore core, RuntimeException fault) throws IOException {
        return ServiceHttp.bindWith(core, (path, tree, options) -> { throw fault; }, "127.0.0.1", 0).start();
    }
}
