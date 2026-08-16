package io.github.changlu.openreach.monitor.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnavailableMonitorRecordStoreTest {

    @Test
    void unsupportedStorageFailsOnlyWhenMonitorStoreInitializes() {
        UnavailableMonitorRecordStore store = new UnavailableMonitorRecordStore("mysql");
        IllegalStateException error = assertThrows(IllegalStateException.class, store::initialize);
        assertTrue(error.getMessage().contains("mysql"));
        assertTrue(store.storageDescription().contains("unsupported"));
    }
}
