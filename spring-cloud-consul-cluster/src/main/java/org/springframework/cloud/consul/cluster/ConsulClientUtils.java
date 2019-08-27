package org.springframework.cloud.consul.cluster;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.cloud.consul.ConsulProperties;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.ecwid.consul.transport.TLSConfig;
import com.ecwid.consul.v1.ConsulClient;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

/**
 * ConsulClient工具类
 *
 * @author pengpeng
 * @date 2019年8月16日 上午10:11:54
 */
public class ConsulClientUtils {

	private static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

	/**
	 * 通过一致性算法选择一个由给定key决定的命中节点
	 * @param key - 客户端提供的散列key,例如取自客户机的IP
	 * @param clients - 在每次调用之前请确保clients的顺序是一致的
	 * @return
	 */
	public static <T> T chooseClient(String key, List<T> clients) {
		Assert.hasText(key, "Parameter 'key' must be required!");
		int prime = 31; // always used in hashcode method
		return chooseClient(Hashing.murmur3_128(prime).hashString(key, DEFAULT_CHARSET),
				clients);
	}

	/**
	 * 通过一致性算法获取由给定key决定的命中节点
	 * @param key - 客户端提供的散列key,例如取自客户机的IP
	 * @param clients - 在每次调用之前请确保clients的顺序是一致的
	 * @return
	 */
	public static <T> T chooseClient(HashCode keyHash, List<T> clients) {
		Assert.notNull(keyHash, "Parameter 'keyHash' must be required!");
		if (!CollectionUtils.isEmpty(clients)) {
			final List<T> nodeList = new ArrayList<T>(clients);
			int hitIndex = Hashing.consistentHash(keyHash, nodeList.size());
			return clients.get(hitIndex);
		}
		return null;
	}

	/**
	 * 创建ConsulClient, copy from {@link #ConsulAutoConfiguration}
	 * @param consulProperties
	 * @return
	 */
	public static ConsulClient createConsulClient(ConsulProperties consulProperties) {
		final int agentPort = consulProperties.getPort();
		final String agentHost = !StringUtils.isEmpty(consulProperties.getScheme())
				? consulProperties.getScheme() + "://" + consulProperties.getHost()
				: consulProperties.getHost();

		if (consulProperties.getTls() != null) {
			ConsulProperties.TLSConfig tls = consulProperties.getTls();
			TLSConfig tlsConfig = new TLSConfig(tls.getKeyStoreInstanceType(),
					tls.getCertificatePath(), tls.getCertificatePassword(),
					tls.getKeyStorePath(), tls.getKeyStorePassword());
			return new ConsulClient(agentHost, agentPort, tlsConfig);
		}
		return new ConsulClient(agentHost, agentPort);
	}

	public static void main(String[] args) {
		String key = "172.16.18.174";
		List<String> clients = Arrays.asList("172.16.18.174:8500", "172.16.94.32:8500",
				"172.16.94.39:8500");
		Set<String> chooses = new HashSet<String>();
		for (int i = 0; i < 10000; i++) {
			chooses.add(chooseClient(key, clients));
		}
		System.out.println(chooses);
	}

}
