package org.springframework.cloud.consul.cluster;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
import org.springframework.cloud.consul.discovery.ConsulServerList;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ecwid.consul.v1.ConsulClient;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ServerList;

/**
 * 自定义的ribbon负载均衡配置，用于覆盖默认ServerList
 *
 * @author pengpeng
 * @date 2019年8月14日 下午6:46:30
 */
@Configuration
public class CustomConsulRibbonClientConfiguration {

	@Autowired
	private ConsulClient consulClient;

	/**
	 * 自定义的ConsulServerList
	 */
	@Bean
	@ConditionalOnMissingBean
	public ServerList<?> ribbonServerList(IClientConfig config,
			ConsulDiscoveryProperties properties) {
		ConsulServerList serverList = new CustomConsulServerList(consulClient,
				properties);
		serverList.initWithNiwsConfig(config);
		return serverList;
	}

}
