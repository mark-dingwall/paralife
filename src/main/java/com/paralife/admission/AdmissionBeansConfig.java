package com.paralife.admission;

import com.paralife.engine.TickEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring bean factory for Phase 18 attribution infrastructure.
 *
 * <p>Produces the {@link AttributionTagger} singleton, which cannot be a
 * {@code @Component} because its constructor takes non-bean parameters
 * ({@code maxCardinality} from config, optional {@link TickEngine} reference).
 */
@Configuration
public class AdmissionBeansConfig {

    /**
     * AttributionTagger singleton wired from bound config and optional tick engine.
     *
     * @param admissionConfig bound {@code paralife.admission.*} config
     * @param tickEngine      the simulation tick engine (for tick= in warn-once log)
     */
    @Bean
    public AttributionTagger attributionTagger(AdmissionConfig admissionConfig,
                                               TickEngine tickEngine) {
        return new AttributionTagger(
                admissionConfig.attribution().maxHarnessCardinality(),
                tickEngine);
    }
}
