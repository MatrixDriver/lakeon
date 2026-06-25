package com.lakeon.cdf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "lakeon.obs.endpoint=http://localhost:9000",
        "lakeon.obs.access-key=test-access-key",
        "lakeon.obs.secret-key=test-secret-key"
})
@ActiveProfiles("test")
class CdfSpringContextTest {

    @Autowired
    private LakebaseCdfController controller;

    @Autowired
    private LakebaseCdfService service;

    @Test
    void cdfBeansLoadInApplicationContext() {
        assertThat(controller).isNotNull();
        assertThat(service).isNotNull();
    }
}
