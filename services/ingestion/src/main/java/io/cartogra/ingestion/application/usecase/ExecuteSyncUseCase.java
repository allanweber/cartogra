package io.cartogra.ingestion.application.usecase;

import io.cartogra.common.event.SyncCommandPayload;
import io.cartogra.ingestion.domain.SyncJob;

public interface ExecuteSyncUseCase {

    SyncJob execute(SyncCommandPayload command);
}
