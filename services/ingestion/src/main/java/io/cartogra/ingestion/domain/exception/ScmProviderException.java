package io.cartogra.ingestion.domain.exception;

public final class ScmProviderException extends RuntimeException {

    private final String providerType;

    public ScmProviderException(String providerType, String message, Throwable cause) {
        super(message, cause);
        this.providerType = providerType;
    }

    public ScmProviderException(String providerType, String message) {
        super(message);
        this.providerType = providerType;
    }

    public String providerType() {
        return providerType;
    }
}
