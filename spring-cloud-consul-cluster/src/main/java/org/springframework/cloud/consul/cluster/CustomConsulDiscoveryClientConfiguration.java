package org.springframework.cloud.consul.cluster;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.consul.ConditionalOnConsulEnabled;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryClient;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryClientConfiguration;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
import org.springframework.cloud.netflix.ribbon.RibbonClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ecwid.consul.v1.ConsulClient;

/**
 * 自定义的Consul服务发现配置
 *
 * @author pengpeng
 * @date 2019年8月21日 上午9:40:55
 */
@Configuration
@ConditionalOnConsulEnabled
@ConditionalOnClass(ConsulDiscoveryProperties.class)
@ConditionalOnProperty(value = "spring.cloud.consul.discovery.enabled", matchIfMissing = true)
@AutoConfigureBefore(ConsulDiscoveryClientConfiguration.class)
@RibbonClients(defaultConfiguration = CustomConsulRibbonClientConfiguration.class)
public class CustomConsulDiscoveryClientConfiguration {

	/**
	 * 自定义的ConsulDiscoveryClient
	 */
	@Bean
	@ConditionalOnMissingBean
	public ConsulDiscoveryClient consulDiscoveryClient(ConsulClient consulClient,
			ConsulDiscoveryProperties discoveryProperties) {
		return new CustomConsulDiscoveryClient(consulClient, discoveryProperties);
	}

}
