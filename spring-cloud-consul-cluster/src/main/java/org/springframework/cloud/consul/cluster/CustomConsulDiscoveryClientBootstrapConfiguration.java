package org.springframework.cloud.consul.cluster;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.consul.ConsulAutoConfiguration;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryClientConfiguration;
import org.springframework.cloud.consul.discovery.configclient.ConsulDiscoveryClientConfigServiceBootstrapConfiguration;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(value = "spring.cloud.config.discovery.enabled", matchIfMissing = false)
@ImportAutoConfiguration({ ConsulAutoConfiguration.class,
		ConsulDiscoveryClientConfiguration.class,
		CustomConsulDiscoveryClientConfiguration.class })
@AutoConfigureBefore(ConsulDiscoveryClientConfigServiceBootstrapConfiguration.class)
@AutoConfigureAfter(ClusterConsulBootstrapConfiguration.class)
public class CustomConsulDiscoveryClientBootstrapConfiguration {

}
