package org.springframework.cloud.consul.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtils.HostInfo;
import org.springframework.cloud.commons.util.UtilAutoConfiguration;
import org.springframework.cloud.consul.ConditionalOnConsulEnabled;
import org.springframework.cloud.consul.ConsulProperties;
import org.springframework.cloud.consul.config.ConsulConfigBootstrapConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.ecwid.consul.v1.ConsulClient;

/**
 * 默认的ConsulClient启动配置,Order必须在{@link #ConsulConfigBootstrapConfiguration}之前，
 * 用于覆盖{@link #ConsulConfigBootstrapConfiguration}中@Import(ConsulAutoConfiguration.class)注册的ConsulClient
 *
 * @author pengpeng
 * @date 2019年8月17日 下午2:22:32
 */

@Configuration
@ConditionalOnConsulEnabled
@EnableConfigurationProperties
@Import(UtilAutoConfiguration.class)
@AutoConfigureBefore(ConsulConfigBootstrapConfiguration.class)
public class ClusterConsulBootstrapConfiguration {

	private static final Logger LOGGER = LoggerFactory
			.getLogger(ClusterConsulBootstrapConfiguration.class);

	@Autowired
	private InetUtils inetUtils;

	@Autowired
	private Environment environment;

	@Bean
	@ConditionalOnMissingBean
	public ConsulProperties consulProperties() {
		String consulHost = environment.getProperty("spring.cloud.consul.host");
		if (consulHost.indexOf(',') != -1) { // 集群模式
			ClusterConsulProperties consulProperties = new ClusterConsulProperties();
			HostInfo hostInfo = inetUtils.findFirstNonLoopbackHostInfo();
			consulProperties.setClusterClientKey(hostInfo.getIpAddress());
			return consulProperties;
		}
		else { // 非集群模式
			return new ConsulProperties();
		}
	}

	@Bean
	@ConditionalOnMissingBean
	public ConsulClient consulClient(ConsulProperties consulProperties) {
		ConsulClient consulClient = null;
		if (consulProperties instanceof ClusterConsulProperties) { // 集群模式
			consulClient = createClusterConsulClient(
					(ClusterConsulProperties) consulProperties);
		}
		else {
			consulClient = createDefaultConsulClient(consulProperties);
		}
		LOGGER.info(">>> Default ConsulClient created : {}, with config properties : {}",
				consulClient, consulProperties);
		return consulClient;
	}

	protected ConsulClient createClusterConsulClient(
			ClusterConsulProperties consulProperties) {
		return new ClusterConsulClient(consulProperties);
	}

	protected ConsulClient createDefaultConsulClient(ConsulProperties consulProperties) {
		return ConsulClientUtils.createConsulClient(consulProperties);
	}

}
