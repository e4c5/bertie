package com.raditha.dedup.config;

import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LSHConfigTest {

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws java.io.IOException {
        // Initialize Settings.props to avoid NPE in setProperty
        java.io.File dummy = java.io.File.createTempFile("dummy", ".yml");
        dummy.deleteOnExit();
        java.nio.file.Files.writeString(dummy.toPath(), "variables: {}");
        Settings.loadConfigMap(dummy);
    }

    @Test
    void testLSHParametersLoading() {
        Map<String, Object> config = new HashMap<>();
        config.put("num_bands", 30);
        config.put("rows_per_band", 5);
        
        Settings.setProperty("duplication_detector", config);
        
        assertEquals(30, DuplicationDetectorSettings.getNumBands());
        assertEquals(5, DuplicationDetectorSettings.getRowsPerBand());
    }

    @Test
    void testLSHParametersDefaultValues() {
        Settings.setProperty("duplication_detector", new HashMap<>());
        
        assertEquals(25, DuplicationDetectorSettings.getNumBands());
        assertEquals(4, DuplicationDetectorSettings.getRowsPerBand());
    }
}
