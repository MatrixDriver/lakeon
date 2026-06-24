package com.lakeon.k8s;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.pageserver.PageserverPlacementService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ComputeSpecBuilderSpringWiringTest {

    @Test
    void springCanInstantiateComputeSpecBuilderWithPlacementService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LakeonProperties.class);
            context.registerBean(ObjectMapper.class);
            context.registerBean(PageserverPlacementService.class);
            context.registerBean(ComputeSpecBuilder.class);

            context.refresh();

            assertThat(context.getBean(ComputeSpecBuilder.class)).isNotNull();
        }
    }
}
