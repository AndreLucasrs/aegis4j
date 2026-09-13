package dev.aegis4j.core.guard;

import dev.aegis4j.api.guard.GuardResult;

public class GuardBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String guardId;
    private final String reasonCode;

    public GuardBlockedException(String guardId, GuardResult.Block block) {
        super("Guard '" + guardId + "' blocked the request [" + block.reasonCode() + "]: " + block.message());
        this.guardId = guardId;
        this.reasonCode = block.reasonCode();
    }

    public String guardId() {
        return guardId;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
