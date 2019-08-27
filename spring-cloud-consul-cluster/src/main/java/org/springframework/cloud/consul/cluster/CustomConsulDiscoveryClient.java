package org.springframework.cloud.consul.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryClient;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;

import com.ecwid.consul.v1.ConsulClient;

/**
 * 自定义的ConsulDiscoveryClient
 *
 * 解决微服务在不同consul节点上重复注册导致getInstances方法返回的结果集重复问题
 *
 * @author pengpeng
 * @date 2019年8月14日 下午12:33:52
 */
public class CustomConsulDiscoveryClient extends ConsulDiscoveryClient {

	private static final Logger LOGGER = LoggerFactory
			.getLogger(CustomConsulDiscoveryClient.class);

	public CustomConsulDiscoveryClient(ConsulClient client,
			ConsulDiscoveryProperties properties) {
		super(client, properties);
	}

	/**
	 * 重写getInstances方法去重
	 */
	@Override
	public List<ServiceInstance> getInstances(String serviceId) {
		List<ServiceInstance> instances = super.getInstances(serviceId);
		Map<String, ServiceInstance> filteredInstances = new HashMap<String, ServiceInstance>();
		for (ServiceInstance instance : instances) { // 去重
			filteredInstances.putIfAbsent(instance.getInstanceId(), instance);
		}
		instances = new ArrayList<ServiceInstance>(filteredInstances.values());
		LOGGER.info(">>> Get instances of service({}) from consul : {}", serviceId,
				instances);
		return instances;
	}

}
