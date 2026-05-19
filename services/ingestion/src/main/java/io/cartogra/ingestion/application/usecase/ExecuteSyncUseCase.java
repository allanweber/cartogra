package io.cartogra.ingestion.application.usecase;

import io.cartogra.ingestion.application.port.in.SyncCommandPayload;
import io.cartogra.ingestion.domain.SyncJob;

public interface ExecuteSyncUseCase {

    SyncJob execute(SyncCommandPayload command);
}
