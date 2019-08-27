package org.springframework.cloud.consul.cluster;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

import javax.validation.constraints.NotNull;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.consul.ConsulProperties;
import org.springframework.validation.annotation.Validated;

import com.ecwid.consul.transport.TransportException;
import com.ecwid.consul.v1.OperationException;

/**
 * 集群ConsulClient配置
 *
 * 如果host配置为逗号分隔的多个主机地址那么将启用集群配置(例如:
 * spring.cloud.consul.host=192.16.1.101:8500,192.16.1.102,192.16.1.103)， 否则就跟官方的单节点配置一样
 *
 * @author pengpeng
 * @date 2019年8月17日 上午8:39:58
 */
@ConfigurationProperties("spring.cloud.consul")
@Validated
public class ClusterConsulProperties extends ConsulProperties {

	/**
	 * 初始化时是否要求所有节点都必须是client节点
	 */
	private boolean onlyClients = true;

	/**
	 * Consul的ACL访问控制token
	 */
	@Value("${consul.token:${CONSUL_TOKEN:${spring.cloud.consul.token:${SPRING_CLOUD_CONSUL_TOKEN:}}}}")
	private String aclToken;

	/**
	 * 集群ConsulClient客户端一致性哈希算法的Key 建议与spring.cloud.client.ip-address对应的值一致
	 */
	@NotNull
	private String clusterClientKey;

	/**
	 * 集群节点健康检测周期(毫秒)
	 */
	private long healthCheckInterval = 10000;

	/**
	 * 重试其他集群节点的前提条件(异常)
	 */
	private List<Class<? extends Throwable>> retryableExceptions = Arrays.asList(
			TransportException.class, OperationException.class, IOException.class,
			ConnectException.class, TimeoutException.class, SocketTimeoutException.class);

	public boolean isOnlyClients() {
		return onlyClients;
	}

	public void setOnlyClients(boolean onlyClients) {
		this.onlyClients = onlyClients;
	}

	public String getAclToken() {
		return aclToken;
	}

	public void setAclToken(String aclToken) {
		this.aclToken = aclToken;
	}

	public String getClusterClientKey() {
		return clusterClientKey;
	}

	public void setClusterClientKey(String clusterClientKey) {
		this.clusterClientKey = clusterClientKey;
	}

	public long getHealthCheckInterval() {
		return healthCheckInterval;
	}

	public void setHealthCheckInterval(long healthCheckInterval) {
		this.healthCheckInterval = healthCheckInterval;
	}

	public List<Class<? extends Throwable>> getRetryableExceptions() {
		return retryableExceptions;
	}

	public void setRetryableExceptions(
			List<Class<? extends Throwable>> retryableExceptions) {
		this.retryableExceptions = retryableExceptions;
	}

	@Override
	public String toString() {
		return "ClusterConsulProperties{" + "host='" + getHost() + '\'' + ", port="
				+ getPort() + ", scheme=" + getScheme() + ", tls=" + getTls()
				+ ", enabled=" + isEnabled() + ", onlyClients=" + isOnlyClients()
				+ ", aclToken=" + getAclToken() + ", clusterClientKey="
				+ getClusterClientKey() + ", healthCheckInterval="
				+ getHealthCheckInterval() + ", retryableExceptions="
				+ getRetryableExceptions() + '}';
	}

}
