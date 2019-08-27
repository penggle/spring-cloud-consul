package org.springframework.cloud.consul.cluster;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.ecwid.consul.ConsulException;
import com.ecwid.consul.transport.TransportException;
import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.OperationException;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.acl.AclClient;
import com.ecwid.consul.v1.acl.model.Acl;
import com.ecwid.consul.v1.acl.model.NewAcl;
import com.ecwid.consul.v1.acl.model.UpdateAcl;
import com.ecwid.consul.v1.agent.AgentClient;
import com.ecwid.consul.v1.agent.model.Member;
import com.ecwid.consul.v1.agent.model.NewCheck;
import com.ecwid.consul.v1.agent.model.NewService;
import com.ecwid.consul.v1.agent.model.Self;
import com.ecwid.consul.v1.agent.model.Service;
import com.ecwid.consul.v1.catalog.CatalogClient;
import com.ecwid.consul.v1.catalog.model.CatalogDeregistration;
import com.ecwid.consul.v1.catalog.model.CatalogNode;
import com.ecwid.consul.v1.catalog.model.CatalogRegistration;
import com.ecwid.consul.v1.catalog.model.CatalogService;
import com.ecwid.consul.v1.coordinate.CoordinateClient;
import com.ecwid.consul.v1.coordinate.model.Datacenter;
import com.ecwid.consul.v1.coordinate.model.Node;
import com.ecwid.consul.v1.event.EventClient;
import com.ecwid.consul.v1.event.model.Event;
import com.ecwid.consul.v1.event.model.EventParams;
import com.ecwid.consul.v1.health.HealthClient;
import com.ecwid.consul.v1.health.model.Check;
import com.ecwid.consul.v1.health.model.Check.CheckStatus;
import com.ecwid.consul.v1.health.model.HealthService;
import com.ecwid.consul.v1.kv.KeyValueClient;
import com.ecwid.consul.v1.kv.model.GetBinaryValue;
import com.ecwid.consul.v1.kv.model.GetValue;
import com.ecwid.consul.v1.kv.model.PutParams;
import com.ecwid.consul.v1.query.QueryClient;
import com.ecwid.consul.v1.query.model.QueryExecution;
import com.ecwid.consul.v1.session.SessionClient;
import com.ecwid.consul.v1.session.model.NewSession;
import com.ecwid.consul.v1.session.model.Session;
import com.ecwid.consul.v1.status.StatusClient;
import com.google.common.base.CharMatcher;

/**
 * 集群版ConsulClient
 *
 * 在客户端实现集群，省去使用Nginx/HAProxy等实现集群所带来的一系列问题(Nginx/HAProxy单点故障问题，服务注册与发现问题)
 *
 * 该集群版ConsulClient仅仅为基于spring-cloud-consul作为服务配置、注册、发现的客户端所定制：
 *
 * 1、默认组成客户端集群的节点必须是client模式的节点，并且在服务启动注册前都要求是可用的(健康的)
 *
 * 2、服务配置模块：关于ConsulClient KV操作仅在当前节点上执行一次，如果当前节点不可用则使用RetryTemplate进行fallback重试!
 *
 * 3、服务注册模块：
 * 3.1、{@link #ConsulServiceRegistry}中所用到的几个方法(agentServiceRegister,agentServiceDeregister,agentServiceSetMaintenance)，在调用前均要求各集群节点必须可用(健康的)，并且在每个节点上执行一次！
 * 为什么要每个client节点都注册一遍?原因有二：(1)、在单个client节点上注册的服务信息仅在该client节点可用的情况下才会出现在集群中(ui/ConsulClient.getHealthServices())中可见，如果该client节点挂了，
 * 那么集群中(ui/ConsulClient.getHealthServices())看不到它上面注册的服务了，因此必须多节点注册；(2)、在单个client节点上注册的服务信息及其healthcheck，仅在该节点可用的情况下healthcheck才被执行，
 * 如果该节点挂了，那么该节点上注册的服务的healthcheck将无法执行，因此会出现服务实际是健康的，但是consul集群认为其是不健康的(因为负责健康检测的那个节点挂了)
 *
 * 3.2 {@link #TtlScheduler}中所用到的方法(agentCheckPass)，则尽最大努力在每个节点上执行一次!
 *
 * 4、服务发现模块：
 * 4.1、服务发现模块所用到的几个方法(getCatalogServices，getHealthServices)，仅在当前节点上执行一次，如果当前节点不可用则使用RetryTemplate进行fallback重试!
 * 4.2、由3.1可知，服务发现模块所用到的获取服务实例列表方法(getHealthServices)，它的调用结果存在重复，因此调用处(ConsulServiceRegistry.getInstances()、ConsulServerList.getXxxServers())需要加入排重逻辑!
 *
 * 5、其他SpringCloud中未使用到的方法，使用默认策略，即仅在当前节点上执行一次，如果当前节点不可用则使用RetryTemplate进行fallback重试!
 *
 * @author pengpeng
 * @date 2019年8月15日 上午11:25:40
 */
public class ClusterConsulClient extends ConsulClient implements AclClient, AgentClient,
		CatalogClient, CoordinateClient, EventClient, HealthClient, KeyValueClient,
		QueryClient, SessionClient, StatusClient, RetryListener {

	private static final Logger LOGGER = LoggerFactory
			.getLogger(ClusterConsulClient.class);

	private static final String CURRENT_CLIENT_KEY = "currentClient";

	private final ScheduledExecutorService consulClientsHealthCheckExecutor = Executors
			.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 4);

	/**
	 * ConsulClient配置
	 */
	private final ClusterConsulProperties consulProperties;

	/**
	 * 所有ConsulClient
	 */
	private final List<ConsulClientHolder> consulClients;

	/**
	 * 重试RetryTemplate
	 */
	private final RetryTemplate retryTemplate;

	/**
	 * 通过一致性hash算法算出来的节点,该节点作为主要使用节点,这样避免集群节点压力问题
	 */
	private final ConsulClientHolder primaryClient;

	/**
	 * 当前正在使用的ConsulClient
	 */
	private volatile ConsulClientHolder currentClient;

	/**
	 * 集群节点在出错时切换的锁
	 */
	private final Lock chooseLock = new ReentrantLock();

	public ClusterConsulClient(ClusterConsulProperties consulProperties) {
		super();
		Assert.notNull(consulProperties,
				"Parameter 'consulProperties' must be required!");
		this.consulProperties = consulProperties;
		this.consulClients = createConsulClients(); // 创建所有集群节点
		this.retryTemplate = createRetryTemplate(); // 创建重试模板
		ConsulClientHolder primaryClient = initPrimaryClient(); // 初始化主要客户端

		// 如果存在不可用节点则立即快速失败
		Assert.state(consulClients.stream().allMatch(client -> client.isHealthy()),
				"Creating ClusterConsulClient failed：all consul nodes of cluster must be available!");

		// 集群中的节点只能是client模式的节点?
		if (consulProperties.isOnlyClients()) {
			boolean isAllClientNode = consulClients.stream().allMatch(client -> {
				Response<Self> response = null;
				if (!StringUtils.isEmpty(consulProperties.getAclToken())) {
					response = client.getClient()
							.getAgentSelf(consulProperties.getAclToken());
				}
				else {
					response = client.getClient().getAgentSelf();
				}
				return !response.getValue().getConfig().isServer();
			});
			Assert.state(isAllClientNode,
					"Creating ClusterConsulClient failed：all consul nodes of cluster must be in 'client' mode!");
		}
		this.primaryClient = primaryClient;
		this.currentClient = primaryClient;
		this.scheduleConsulClientsHealthCheck();
	}

	@Override
	public Response<String> getStatusLeader() {
		return retryTemplate
				.execute(new RetryCallback<Response<String>, ConsulException>() {
					@Override
					public Response<String> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getStatusLeader();
					}
				});
	}

	@Override
	public Response<List<String>> getStatusPeers() {
		return retryTemplate
				.execute(new RetryCallback<Response<List<String>>, ConsulException>() {
					@Override
					public Response<List<String>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getStatusPeers();
					}
				});
	}

	@Override
	public Response<String> sessionCreate(NewSession newSession,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<String>, ConsulException>() {
					@Override
					public Response<String> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).sessionCreate(newSession,
								queryParams);
					}
				});
	}

	@Override
	public Response<String> sessionCreate(NewSession newSession, QueryParams queryParams,
			String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<String>, ConsulException>() {
					@Override
					public Response<String> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).sessionCreate(newSession,
								queryParams, token);
					}
				});
	}

	@Override
	public Response<Void> sessionDestroy(String session, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).sessionDestroy(session,
								queryParams);
					}
				});
	}

	@Override
	public Response<Void> sessionDestroy(String session, QueryParams queryParams,
			String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).sessionDestroy(session,
								queryParams, token);
					}
				});
	}

	@Override
	public Response<Session> getSessionInfo(String session, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Session>, ConsulException>() {
					@Override
					public Response<Session> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getSessionInfo(session,
								queryParams);
					}
				});
	}

	@Override
	public Response<Session> getSessionInfo(String session, QueryParams queryParams,
			String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Session>, ConsulException>() {
					@Override
					public Response<Session> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getSessionInfo(session,
								queryParams, token);
					}
				});
	}

	@Override
	public Response<List<Session>> getSessionNode(String node, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Session>>, ConsulException>() {
					@Override
					public Response<List<Session>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getSessionNode(node,
								queryParams);
					}
				});
	}

	@Override
	public Response<List<Session>> getSessionNode(String node, QueryParams queryParams,
			String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Session>>, ConsulException>() {
					@Override
					public Response<List<Session>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getSessionNode(node,
								queryParams, token);
					}
				});
	}

	@Override
	public Response<List<Session>> getSessionList(QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Session>>, ConsulException>() {
					@Override
					public Response<List<Session>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getSessionList(queryParams);
					}
				});
	}

	@Override
	public Response<List<Session>> getSessionList(QueryParams queryParams, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Session>>, ConsulException>() {
					@Override
					public Response<List<Session>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getSessionList(queryParams,
								token);
					}
				});
	}

	@Override
	public Response<Session> renewSession(String session, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Session>, ConsulException>() {
					@Override
					public Response<Session> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).renewSession(session,
								queryParams);
					}
				});
	}

	@Override
	public Response<Session> renewSession(String session, QueryParams queryParams,
			String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Session>, ConsulException>() {
					@Override
					public Response<Session> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).renewSession(session,
								queryParams, token);
					}
				});
	}

	@Override
	public Response<QueryExecution> executePreparedQuery(String uuid,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<QueryExecution>, ConsulException>() {
					@Override
					public Response<QueryExecution> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).executePreparedQuery(uuid,
								queryParams);
					}
				});
	}

	@Override
	public Response<GetValue> getKVValue(String key) {
		return retryTemplate
				.execute(new RetryCallback<Response<GetValue>, ConsulException>() {
					@Override
					public Response<GetValue> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVValue(key);
					}
				});
	}

	@Override
	public Response<GetValue> getKVValue(String key, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<GetValue>, ConsulException>() {
					@Override
					public Response<GetValue> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVValue(key, token);
					}
				});
	}

	@Override
	public Response<GetValue> getKVValue(String key, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<GetValue>, ConsulException>() {
					@Override
					public Response<GetValue> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVValue(key, queryParams);
					}
				});
	}

	@Override
	public Response<GetValue> getKVValue(String key, String token,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<GetValue>, ConsulException>() {
					@Override
					public Response<GetValue> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVValue(key, token,
								queryParams);
					}
				});
	}

	@Override
	public Response<GetBinaryValue> getKVBinaryValue(String key) {
		return retryTemplate
				.execute(new RetryCallback<Response<GetBinaryValue>, ConsulException>() {
					@Override
					public Response<GetBinaryValue> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVBinaryValue(key);
					}
				});
	}

	@Override
	public Response<GetBinaryValue> getKVBinaryValue(String key, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<GetBinaryValue>, ConsulException>() {
					@Override
					public Response<GetBinaryValue> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVBinaryValue(key, token);
					}
				});
	}

	@Override
	public Response<GetBinaryValue> getKVBinaryValue(String key,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<GetBinaryValue>, ConsulException>() {
					@Override
					public Response<GetBinaryValue> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVBinaryValue(key,
								queryParams);
					}
				});
	}

	@Override
	public Response<GetBinaryValue> getKVBinaryValue(String key, String token,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<GetBinaryValue>, ConsulException>() {
					@Override
					public Response<GetBinaryValue> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVBinaryValue(key, token,
								queryParams);
					}
				});
	}

	@Override
	public Response<List<GetValue>> getKVValues(String keyPrefix) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<GetValue>>, ConsulException>() {
					@Override
					public Response<List<GetValue>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVValues(keyPrefix);
					}
				});
	}

	@Override
	public Response<List<GetValue>> getKVValues(String keyPrefix, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<GetValue>>, ConsulException>() {
					@Override
					public Response<List<GetValue>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVValues(keyPrefix,
								token);
					}
				});
	}

	@Override
	public Response<List<GetValue>> getKVValues(String keyPrefix,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<GetValue>>, ConsulException>() {
					@Override
					public Response<List<GetValue>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVValues(keyPrefix,
								queryParams);
					}
				});
	}

	@Override
	public Response<List<GetValue>> getKVValues(String keyPrefix, String token,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<GetValue>>, ConsulException>() {
					@Override
					public Response<List<GetValue>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVValues(keyPrefix, token,
								queryParams);
					}
				});
	}

	@Override
	public Response<List<GetBinaryValue>> getKVBinaryValues(String keyPrefix) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<GetBinaryValue>>, ConsulException>() {
					@Override
					public Response<List<GetBinaryValue>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context).getKVBinaryValues(keyPrefix);
					}
				});
	}

	@Override
	public Response<List<GetBinaryValue>> getKVBinaryValues(String keyPrefix,
			String token) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<GetBinaryValue>>, ConsulException>() {
					@Override
					public Response<List<GetBinaryValue>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context).getKVBinaryValues(keyPrefix,
								token);
					}
				});
	}

	@Override
	public Response<List<GetBinaryValue>> getKVBinaryValues(String keyPrefix,
			QueryParams queryParams) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<GetBinaryValue>>, ConsulException>() {
					@Override
					public Response<List<GetBinaryValue>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context).getKVBinaryValues(keyPrefix,
								queryParams);
					}
				});
	}

	@Override
	public Response<List<GetBinaryValue>> getKVBinaryValues(String keyPrefix,
			String token, QueryParams queryParams) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<GetBinaryValue>>, ConsulException>() {
					@Override
					public Response<List<GetBinaryValue>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context).getKVBinaryValues(keyPrefix,
								token, queryParams);
					}
				});
	}

	@Override
	public Response<List<String>> getKVKeysOnly(String keyPrefix) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<String>>, ConsulException>() {
					@Override
					public Response<List<String>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVKeysOnly(keyPrefix);
					}
				});
	}

	@Override
	public Response<List<String>> getKVKeysOnly(String keyPrefix, String separator,
			String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<String>>, ConsulException>() {
					@Override
					public Response<List<String>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVKeysOnly(keyPrefix,
								separator, token);
					}
				});
	}

	@Override
	public Response<List<String>> getKVKeysOnly(String keyPrefix,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<String>>, ConsulException>() {
					@Override
					public Response<List<String>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVKeysOnly(keyPrefix,
								queryParams);
					}
				});
	}

	@Override
	public Response<List<String>> getKVKeysOnly(String keyPrefix, String separator,
			String token, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<String>>, ConsulException>() {
					@Override
					public Response<List<String>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getKVKeysOnly(keyPrefix,
								separator, token, queryParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVValue(String key, String value) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVValue(key, value);
					}
				});
	}

	@Override
	public Response<Boolean> setKVValue(String key, String value, PutParams putParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVValue(key, value,
								putParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVValue(String key, String value, String token,
			PutParams putParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVValue(key, value, token,
								putParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVValue(String key, String value,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVValue(key, value,
								queryParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVValue(String key, String value, PutParams putParams,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVValue(key, value,
								putParams, queryParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVValue(String key, String value, String token,
			PutParams putParams, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVValue(key, value, token,
								putParams, queryParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVBinaryValue(String key, byte[] value) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVBinaryValue(key, value);
					}
				});
	}

	@Override
	public Response<Boolean> setKVBinaryValue(String key, byte[] value,
			PutParams putParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVBinaryValue(key, value,
								putParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVBinaryValue(String key, byte[] value, String token,
			PutParams putParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVBinaryValue(key, value,
								token, putParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVBinaryValue(String key, byte[] value,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVBinaryValue(key, value,
								queryParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVBinaryValue(String key, byte[] value,
			PutParams putParams, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVBinaryValue(key, value,
								putParams, queryParams);
					}
				});
	}

	@Override
	public Response<Boolean> setKVBinaryValue(String key, byte[] value, String token,
			PutParams putParams, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Boolean>, ConsulException>() {
					@Override
					public Response<Boolean> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).setKVBinaryValue(key, value,
								token, putParams, queryParams);
					}
				});
	}

	@Override
	public Response<Void> deleteKVValue(String key) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).deleteKVValue(key);
					}
				});
	}

	@Override
	public Response<Void> deleteKVValue(String key, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).deleteKVValue(key, token);
					}
				});
	}

	@Override
	public Response<Void> deleteKVValue(String key, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).deleteKVValue(key,
								queryParams);
					}
				});
	}

	@Override
	public Response<Void> deleteKVValue(String key, String token,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).deleteKVValue(key, token,
								queryParams);
					}
				});
	}

	@Override
	public Response<Void> deleteKVValues(String key) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).deleteKVValues(key);
					}
				});
	}

	@Override
	public Response<Void> deleteKVValues(String key, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).deleteKVValues(key, token);
					}
				});
	}

	@Override
	public Response<Void> deleteKVValues(String key, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).deleteKVValues(key,
								queryParams);
					}
				});
	}

	@Override
	public Response<Void> deleteKVValues(String key, String token,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).deleteKVValues(key, token,
								queryParams);
					}
				});
	}

	@Override
	public Response<List<Check>> getHealthChecksForNode(String nodeName,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Check>>, ConsulException>() {
					@Override
					public Response<List<Check>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.getHealthChecksForNode(nodeName, queryParams);
					}
				});
	}

	@Override
	public Response<List<Check>> getHealthChecksForService(String serviceName,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Check>>, ConsulException>() {
					@Override
					public Response<List<Check>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.getHealthChecksForService(serviceName, queryParams);
					}
				});
	}

	@Override
	public Response<List<HealthService>> getHealthServices(String serviceName,
			boolean onlyPassing, QueryParams queryParams) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<HealthService>>, ConsulException>() {
					@Override
					public Response<List<HealthService>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.getHealthServices(serviceName, onlyPassing, queryParams);
					}
				});
	}

	@Override
	public Response<List<HealthService>> getHealthServices(String serviceName, String tag,
			boolean onlyPassing, QueryParams queryParams) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<HealthService>>, ConsulException>() {
					@Override
					public Response<List<HealthService>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getHealthServices(
								serviceName, tag, onlyPassing, queryParams);
					}
				});
	}

	@Override
	public Response<List<HealthService>> getHealthServices(String serviceName,
			boolean onlyPassing, QueryParams queryParams, String token) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<HealthService>>, ConsulException>() {
					@Override
					public Response<List<HealthService>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getHealthServices(
								serviceName, onlyPassing, queryParams, token);
					}
				});
	}

	@Override
	public Response<List<HealthService>> getHealthServices(String serviceName, String tag,
			boolean onlyPassing, QueryParams queryParams, String token) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<HealthService>>, ConsulException>() {
					@Override
					public Response<List<HealthService>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getHealthServices(
								serviceName, tag, onlyPassing, queryParams, token);
					}
				});
	}

	@Override
	public Response<List<Check>> getHealthChecksState(QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Check>>, ConsulException>() {
					@Override
					public Response<List<Check>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.getHealthChecksState(queryParams);
					}
				});
	}

	@Override
	public Response<List<Check>> getHealthChecksState(CheckStatus checkStatus,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Check>>, ConsulException>() {
					@Override
					public Response<List<Check>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.getHealthChecksState(checkStatus, queryParams);
					}
				});
	}

	@Override
	public Response<Event> eventFire(String event, String payload,
			EventParams eventParams, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<Event>, ConsulException>() {
					@Override
					public Response<Event> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).eventFire(event, payload,
								eventParams, queryParams);
					}
				});
	}

	@Override
	public Response<List<Event>> eventList(QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Event>>, ConsulException>() {
					@Override
					public Response<List<Event>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).eventList(queryParams);
					}
				});
	}

	@Override
	public Response<List<Event>> eventList(String event, QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Event>>, ConsulException>() {
					@Override
					public Response<List<Event>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).eventList(event,
								queryParams);
					}
				});
	}

	@Override
	public Response<List<Datacenter>> getDatacenters() {
		return retryTemplate.execute(
				new RetryCallback<Response<List<Datacenter>>, ConsulException>() {
					@Override
					public Response<List<Datacenter>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getDatacenters();
					}
				});
	}

	@Override
	public Response<List<Node>> getNodes(QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Node>>, ConsulException>() {
					@Override
					public Response<List<Node>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getNodes(queryParams);
					}
				});
	}

	@Override
	public Response<Void> catalogRegister(CatalogRegistration catalogRegistration) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.catalogRegister(catalogRegistration);
					}
				});
	}

	@Override
	public Response<Void> catalogRegister(CatalogRegistration catalogRegistration,
			String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.catalogRegister(catalogRegistration, token);
					}
				});
	}

	@Override
	public Response<Void> catalogDeregister(CatalogDeregistration catalogDeregistration) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.catalogDeregister(catalogDeregistration);
					}
				});
	}

	@Override
	public Response<List<String>> getCatalogDatacenters() {
		return retryTemplate
				.execute(new RetryCallback<Response<List<String>>, ConsulException>() {
					@Override
					public Response<List<String>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getCatalogDatacenters();
					}
				});
	}

	@Override
	public Response<List<com.ecwid.consul.v1.catalog.model.Node>> getCatalogNodes(
			QueryParams queryParams) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<com.ecwid.consul.v1.catalog.model.Node>>, ConsulException>() {
					@Override
					public Response<List<com.ecwid.consul.v1.catalog.model.Node>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context).getCatalogNodes(queryParams);
					}
				});
	}

	@Override
	public Response<Map<String, List<String>>> getCatalogServices(
			QueryParams queryParams) {
		return retryTemplate.execute(
				new RetryCallback<Response<Map<String, List<String>>>, ConsulException>() {
					@Override
					public Response<Map<String, List<String>>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context)
								.getCatalogServices(queryParams);
					}
				});
	}

	@Override
	public Response<Map<String, List<String>>> getCatalogServices(QueryParams queryParams,
			String token) {
		return retryTemplate.execute(
				new RetryCallback<Response<Map<String, List<String>>>, ConsulException>() {
					@Override
					public Response<Map<String, List<String>>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context)
								.getCatalogServices(queryParams, token);
					}
				});
	}

	@Override
	public Response<List<CatalogService>> getCatalogService(String serviceName,
			QueryParams queryParams) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<CatalogService>>, ConsulException>() {
					@Override
					public Response<List<CatalogService>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context)
								.getCatalogService(serviceName, queryParams);
					}
				});
	}

	@Override
	public Response<List<CatalogService>> getCatalogService(String serviceName,
			String tag, QueryParams queryParams) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<CatalogService>>, ConsulException>() {
					@Override
					public Response<List<CatalogService>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context)
								.getCatalogService(serviceName, tag, queryParams);
					}
				});
	}

	@Override
	public Response<List<CatalogService>> getCatalogService(String serviceName,
			QueryParams queryParams, String token) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<CatalogService>>, ConsulException>() {
					@Override
					public Response<List<CatalogService>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context)
								.getCatalogService(serviceName, queryParams, token);
					}
				});
	}

	@Override
	public Response<List<CatalogService>> getCatalogService(String serviceName,
			String tag, QueryParams queryParams, String token) {
		return retryTemplate.execute(
				new RetryCallback<Response<List<CatalogService>>, ConsulException>() {
					@Override
					public Response<List<CatalogService>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context)
								.getCatalogService(serviceName, tag, queryParams, token);
					}
				});
	}

	@Override
	public Response<CatalogNode> getCatalogNode(String nodeName,
			QueryParams queryParams) {
		return retryTemplate
				.execute(new RetryCallback<Response<CatalogNode>, ConsulException>() {
					@Override
					public Response<CatalogNode> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getCatalogNode(nodeName,
								queryParams);
					}
				});
	}

	@Override
	public Response<Map<String, com.ecwid.consul.v1.agent.model.Check>> getAgentChecks() {
		return retryTemplate.execute(
				new RetryCallback<Response<Map<String, com.ecwid.consul.v1.agent.model.Check>>, ConsulException>() {
					@Override
					public Response<Map<String, com.ecwid.consul.v1.agent.model.Check>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context).getAgentChecks();
					}
				});
	}

	@Override
	public Response<Map<String, Service>> getAgentServices() {
		return retryTemplate.execute(
				new RetryCallback<Response<Map<String, Service>>, ConsulException>() {
					@Override
					public Response<Map<String, Service>> doWithRetry(
							RetryContext context) throws ConsulException {
						return getRetryConsulClient(context).getAgentServices();
					}
				});
	}

	@Override
	public Response<List<Member>> getAgentMembers() {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Member>>, ConsulException>() {
					@Override
					public Response<List<Member>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getAgentMembers();
					}
				});
	}

	@Override
	public Response<Self> getAgentSelf() {
		return retryTemplate
				.execute(new RetryCallback<Response<Self>, ConsulException>() {
					@Override
					public Response<Self> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getAgentSelf();
					}
				});
	}

	@Override
	public Response<Self> getAgentSelf(String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Self>, ConsulException>() {
					@Override
					public Response<Self> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getAgentSelf(token);
					}
				});
	}

	@Override
	public Response<Void> agentSetMaintenance(boolean maintenanceEnabled) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.agentSetMaintenance(maintenanceEnabled);
					}
				});
	}

	@Override
	public Response<Void> agentSetMaintenance(boolean maintenanceEnabled, String reason) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.agentSetMaintenance(maintenanceEnabled, reason);
					}
				});
	}

	@Override
	public Response<Void> agentJoin(String address, boolean wan) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentJoin(address, wan);
					}
				});
	}

	@Override
	public Response<Void> agentForceLeave(String node) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentForceLeave(node);
					}
				});
	}

	@Override
	public Response<Void> agentCheckRegister(NewCheck newCheck) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentCheckRegister(newCheck);
					}
				});
	}

	@Override
	public Response<Void> agentCheckRegister(NewCheck newCheck, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentCheckRegister(newCheck,
								token);
					}
				});
	}

	@Override
	public Response<Void> agentCheckDeregister(String checkId) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context)
								.agentCheckDeregister(checkId);
					}
				});
	}

	@Override
	public Response<Void> agentCheckDeregister(String checkId, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentCheckDeregister(checkId,
								token);
					}
				});
	}

	/**
	 * 尽最大努力向每个节点报告自身的健康状况
	 * @see {@link #TtlScheduler}
	 */
	@Override
	public Response<Void> agentCheckPass(String checkId) {
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			try {
				response = consulClient.getClient().agentCheckPass(checkId);
			}
			catch (Exception e) {
				LOGGER.error(e.getMessage());
			}
		}
		return response;
	}

	/**
	 * 尽最大努力向每个节点报告自身的健康状况
	 * @see {@link #TtlScheduler}
	 */
	@Override
	public Response<Void> agentCheckPass(String checkId, String note) {
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			try {
				response = consulClient.getClient().agentCheckPass(checkId, note);
			}
			catch (Exception e) {
				LOGGER.error(e.getMessage());
			}
		}
		return response;
	}

	/**
	 * 尽最大努力向每个节点报告自身的健康状况
	 * @see {@link #TtlScheduler}
	 */
	@Override
	public Response<Void> agentCheckPass(String checkId, String note, String token) {
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			try {
				response = consulClient.getClient().agentCheckPass(checkId, note, token);
			}
			catch (Exception e) {
				LOGGER.error(e.getMessage());
			}
		}
		return response;
	}

	@Override
	public Response<Void> agentCheckWarn(String checkId) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentCheckWarn(checkId);
					}
				});
	}

	@Override
	public Response<Void> agentCheckWarn(String checkId, String note) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentCheckWarn(checkId,
								note);
					}
				});
	}

	@Override
	public Response<Void> agentCheckWarn(String checkId, String note, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentCheckWarn(checkId, note,
								token);
					}
				});
	}

	@Override
	public Response<Void> agentCheckFail(String checkId) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentCheckFail(checkId);
					}
				});
	}

	@Override
	public Response<Void> agentCheckFail(String checkId, String note) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentCheckFail(checkId,
								note);
					}
				});
	}

	@Override
	public Response<Void> agentCheckFail(String checkId, String note, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).agentCheckFail(checkId, note,
								token);
					}
				});
	}

	/**
	 * 检测所有节点是否可用，如果都可用，则向所有节点注册服务
	 * @see {@link #ConsulServiceRegistry.register(...)}
	 */
	@Override
	public Response<Void> agentServiceRegister(NewService newService) {
		Assert.state(isAllConsulClientsHealthy(true),
				"Register service failed: all consul clients must be available!"); // 全部节点都是可用的情况下才能注册
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			response = consulClient.getClient().agentServiceRegister(newService);
		}
		return response;
	}

	/**
	 * 检测所有节点是否可用，如果都可用，则向所有节点注册服务
	 * @see {@link #ConsulServiceRegistry.register(...)}
	 */
	@Override
	public Response<Void> agentServiceRegister(NewService newService, String token) {
		Assert.state(isAllConsulClientsHealthy(true),
				"Register service failed: all consul clients must be available!"); // 全部节点都是可用的情况下才能注册
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			response = consulClient.getClient().agentServiceRegister(newService, token);
		}
		return response;
	}

	/**
	 * 检测所有节点是否可用，如果都可用，则向所有节点注销服务
	 * @see {@link #ConsulServiceRegistry.deregister(...)}
	 */
	@Override
	public Response<Void> agentServiceDeregister(String serviceId) {
		Assert.state(isAllConsulClientsHealthy(true),
				"Deregister service failed: all consul clients must be available!"); // 全部节点都是可用的情况下才能注册
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			response = consulClient.getClient().agentServiceDeregister(serviceId);
		}
		return response;
	}

	/**
	 * 检测所有节点是否可用，如果都可用，则向所有节点注销服务
	 * @see {@link #ConsulServiceRegistry.deregister(...)}
	 */
	@Override
	public Response<Void> agentServiceDeregister(String serviceId, String token) {
		Assert.state(isAllConsulClientsHealthy(true),
				"Deregister service failed: all consul clients must be available!"); // 全部节点都是可用的情况下才能注册
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			response = consulClient.getClient().agentServiceDeregister(serviceId, token);
		}
		return response;
	}

	/**
	 * 检测所有节点是否可用，如果都可用，则向所有节点执行setMaintenance
	 * @see {@link #ConsulServiceRegistry.setStatus(...)}
	 */
	@Override
	public Response<Void> agentServiceSetMaintenance(String serviceId,
			boolean maintenanceEnabled) {
		Assert.state(isAllConsulClientsHealthy(true),
				"Set service maintenance failed: all consul clients must be available!"); // 全部节点都是可用的情况下才能注册
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			response = consulClient.getClient().agentServiceSetMaintenance(serviceId,
					maintenanceEnabled);
		}
		return response;
	}

	/**
	 * 检测所有节点是否可用，如果都可用，则向所有节点执行setMaintenance
	 * @see {@link #ConsulServiceRegistry.setStatus(...)}
	 */
	@Override
	public Response<Void> agentServiceSetMaintenance(String serviceId,
			boolean maintenanceEnabled, String reason) {
		Assert.state(isAllConsulClientsHealthy(true),
				"Set service maintenance failed: all consul clients must be available!"); // 全部节点都是可用的情况下才能注册
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			response = consulClient.getClient().agentServiceSetMaintenance(serviceId,
					maintenanceEnabled, reason);
		}
		return response;
	}

	/**
	 * 尽最大努力在每个节点上执行agentReload()操作
	 */
	@Override
	public Response<Void> agentReload() {
		Response<Void> response = null;
		for (ConsulClientHolder consulClient : consulClients) {
			try {
				response = consulClient.getClient().agentReload();
			}
			catch (Exception e) {
				LOGGER.error(e.getMessage());
			}
		}
		return response;
	}

	@Override
	public Response<String> aclCreate(NewAcl newAcl, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<String>, ConsulException>() {
					@Override
					public Response<String> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).aclCreate(newAcl, token);
					}
				});
	}

	@Override
	public Response<Void> aclUpdate(UpdateAcl updateAcl, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).aclUpdate(updateAcl, token);
					}
				});
	}

	@Override
	public Response<Void> aclDestroy(String aclId, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<Void>, ConsulException>() {
					@Override
					public Response<Void> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).aclDestroy(aclId, token);
					}
				});
	}

	@Override
	public Response<Acl> getAcl(String id) {
		return retryTemplate.execute(new RetryCallback<Response<Acl>, ConsulException>() {
			@Override
			public Response<Acl> doWithRetry(RetryContext context)
					throws ConsulException {
				return getRetryConsulClient(context).getAcl(id);
			}
		});
	}

	@Override
	public Response<String> aclClone(String aclId, String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<String>, ConsulException>() {
					@Override
					public Response<String> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).aclClone(aclId, token);
					}
				});
	}

	@Override
	public Response<List<Acl>> getAclList(String token) {
		return retryTemplate
				.execute(new RetryCallback<Response<List<Acl>>, ConsulException>() {
					@Override
					public Response<List<Acl>> doWithRetry(RetryContext context)
							throws ConsulException {
						return getRetryConsulClient(context).getAclList(token);
					}
				});
	}

	/**
	 * 创建所有ConsulClient
	 * @return
	 */
	protected List<ConsulClientHolder> createConsulClients() {
		List<String> connectList = prepareConnectList();
		List<ConsulClientHolder> consulClients = connectList.stream().map(connect -> {
			String[] connects = connect.split(":");
			ClusterConsulProperties properties = new ClusterConsulProperties();
			properties.setEnabled(consulProperties.isEnabled());
			properties.setScheme(consulProperties.getScheme());
			properties.setTls(consulProperties.getTls());
			properties.setAclToken(consulProperties.getAclToken());
			properties.setClusterClientKey(consulProperties.getClusterClientKey());
			properties.setHealthCheckInterval(consulProperties.getHealthCheckInterval());
			properties.setRetryableExceptions(consulProperties.getRetryableExceptions());
			properties.setHost(connects[0]);
			properties.setPort(Integer.parseInt(connects[1]));
			return new ConsulClientHolder(properties);
		}).sorted().collect(Collectors.toList()); // 排序
		connectList = consulClients.stream().map(ConsulClientHolder::getClientId)
				.collect(Collectors.toList());
		LOGGER.info(">>> Creating cluster consul clients: {}", connectList);
		return consulClients;
	}

	/**
	 * 准备ConsulClient的连接标识
	 * @return
	 */
	protected List<String> prepareConnectList() {
		List<String> connectList = new ArrayList<String>();
		String hosts = consulProperties.getHost();
		String[] connects = CharMatcher.anyOf(",").trimFrom(hosts.trim()).split(",");
		for (String connect : connects) {
			String[] parts = CharMatcher.anyOf(":").trimFrom(connect.trim()).split(":");
			Assert.isTrue(parts.length > 0 && parts.length <= 2, String
					.format("Invalid config 'spring.cloud.consul.host' : %s", hosts));
			String host = parts[0];
			int port = consulProperties.getPort();
			if (parts.length == 2) {
				port = Integer.valueOf(parts[1]);
			}
			connectList.add(host + ":" + port);
		}
		return connectList;
	}

	/**
	 * 创建重试RetryTemplate， 默认使用SimpleRetryPolicy(maxAttempts定为consulClients.size() + 1)
	 * @param retryableExceptions
	 * @return
	 */
	protected RetryTemplate createRetryTemplate() {
		Map<Class<? extends Throwable>, Boolean> retryableExceptions = null;

		if (!CollectionUtils.isEmpty(consulProperties.getRetryableExceptions())) {
			retryableExceptions = consulProperties.getRetryableExceptions().stream()
					.collect(Collectors.toMap(Function.identity(), e -> Boolean.TRUE,
							(oldValue, newValue) -> newValue));
		}

		if (!CollectionUtils.isEmpty(retryableExceptions)) {
			retryableExceptions = createDefaultRetryableExceptions();
		}
		RetryTemplate retryTemplate = new RetryTemplate();
		SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(consulClients.size(),
				retryableExceptions, true);
		retryTemplate.setRetryPolicy(retryPolicy);
		retryTemplate.setListeners(new RetryListener[] { this });
		return retryTemplate;
	}

	/**
	 * 创建默认的retryableExceptions
	 * @return
	 */
	protected Map<Class<? extends Throwable>, Boolean> createDefaultRetryableExceptions() {
		Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
		retryableExceptions.put(TransportException.class, true);
		retryableExceptions.put(OperationException.class, true);
		retryableExceptions.put(IOException.class, true);
		retryableExceptions.put(ConnectException.class, true);
		retryableExceptions.put(TimeoutException.class, true);
		retryableExceptions.put(SocketTimeoutException.class, true);
		return retryableExceptions;
	}

	/**
	 * 根据已生成的集群列表(不管节点健康状况)初始化主要ConsulClient
	 * @return
	 */
	protected ConsulClientHolder initPrimaryClient() {
		return ConsulClientUtils.chooseClient(consulProperties.getClusterClientKey(),
				consulClients);
	}

	/**
	 * 通过哈希一致性算法选择一个健康的ConsulClient
	 */
	protected void chooseConsulClient() {
		try {
			chooseLock.lock();
			if (!currentClient.isHealthy()) {
				// 过滤出健康节点
				List<ConsulClientHolder> availableClients = consulClients.stream()
						.filter(client -> client.isHealthy()).sorted()
						.collect(Collectors.toList());
				// 在健康节点中通过哈希一致性算法选取一个节点
				ConsulClientHolder choosedClient = ConsulClientUtils.chooseClient(
						consulProperties.getClusterClientKey(), availableClients);

				if (choosedClient == null) {
					checkConsulClientsHealth(); // 一个健康节点都没有，则立马执行一次全部健康检测
					throw new IllegalStateException("No consul client is available!!!");
				}
				LOGGER.info(">>> Successfully choosed a new ConsulClient : {}",
						choosedClient.getClientId());
				this.currentClient = choosedClient;
			}
		}
		finally {
			chooseLock.unlock();
		}
	}

	/**
	 * 获取重试的ConsulClient
	 * @param context - 重试上下文
	 * @return
	 */
	protected ConsulClient getRetryConsulClient(RetryContext context) {
		context.setAttribute(CURRENT_CLIENT_KEY, currentClient);
		int retryCount = context.getRetryCount();
		if (!currentClient.isHealthy()) {
			chooseConsulClient();
		}
		if (retryCount > 0) {
			LOGGER.info(">>> Using current ConsulClient[{}] for retry {}",
					currentClient.getClientId(), retryCount);
		}
		return currentClient.getClient();
	}

	@Override
	public final <T, E extends Throwable> boolean open(RetryContext context,
			RetryCallback<T, E> callback) {
		return true;
	}

	@Override
	public final <T, E extends Throwable> void close(RetryContext context,
			RetryCallback<T, E> callback, Throwable throwable) {
		context.removeAttribute(CURRENT_CLIENT_KEY);
	}

	/**
	 * 每次ConsulClient调用出错之后且在下次重试之前调用该方法
	 */
	@Override
	public <T, E extends Throwable> void onError(RetryContext context,
			RetryCallback<T, E> callback, Throwable throwable) {
		ConsulClientHolder currentClient = (ConsulClientHolder) context
				.getAttribute(CURRENT_CLIENT_KEY);
		if (currentClient != null) {
			currentClient.setHealthy(false);
		}
	}

	/**
	 * ConsulClient集群的健康检测
	 */
	protected void scheduleConsulClientsHealthCheck() {
		consulClientsHealthCheckExecutor.scheduleAtFixedRate(
				this::checkConsulClientsHealth, consulProperties.getHealthCheckInterval(),
				consulProperties.getHealthCheckInterval(), TimeUnit.MILLISECONDS);
	}

	/**
	 * 对全部的ConsulClient检测一次健康状况
	 */
	protected void checkConsulClientsHealth() {
		boolean allHealthy = isAllConsulClientsHealthy(true);
		if (allHealthy && currentClient != primaryClient) { // 如果所有节点都是健康的，那么恢复currentClient为primaryClient
			currentClient = primaryClient;
			LOGGER.info(
					">>> The primaryClient is recovered when all consul clients is healthy.");
		}
	}

	/**
	 * 判断全部的ConsulClient是否都是健康的?
	 * @param immediate - 是否立即执行一次检测
	 * @return
	 */
	protected boolean isAllConsulClientsHealthy(boolean immediate) {
		boolean allHealthy = true;
		for (ConsulClientHolder consulClient : consulClients) {
			if (immediate) {
				consulClient.checkHealth();
			}
			allHealthy = allHealthy && consulClient.isHealthy();
		}
		return allHealthy;
	}

	protected ClusterConsulProperties getConsulProperties() {
		return consulProperties;
	}

	protected List<ConsulClientHolder> getConsulClients() {
		return consulClients;
	}

	protected ConsulClientHolder getCurrentClient() {
		return currentClient;
	}

	protected void setCurrentClient(ConsulClientHolder currentClient) {
		this.currentClient = currentClient;
	}

	protected RetryTemplate getRetryTemplate() {
		return retryTemplate;
	}

	protected ScheduledExecutorService getConsulClientsHealthCheckExecutor() {
		return consulClientsHealthCheckExecutor;
	}

	/**
	 * ConsulClient holder
	 *
	 * @author pengpeng
	 * @date 2019年8月17日 上午8:43:16
	 */
	class ConsulClientHolder implements Comparable<ConsulClientHolder> {

		/**
		 * 当前ConsulClient的配置
		 */
		private final ClusterConsulProperties properties;

		/**
		 * Consul客户端
		 */
		private final ConsulClient client;

		/**
		 * 当前ConsulClient是否是健康的
		 */
		private boolean healthy = true;

		public ConsulClientHolder(ClusterConsulProperties properties) {
			super();
			this.properties = properties;
			this.client = ConsulClientUtils.createConsulClient(properties);
			LOGGER.info(">>>  Cluster ConsulClient[{}] created!", this.getClientId());
			this.checkHealth(); // 创建时做一次健康检测
		}

		public ClusterConsulProperties getProperties() {
			return properties;
		}

		public ConsulClient getClient() {
			return client;
		}

		public boolean isHealthy() {
			return healthy;
		}

		protected void setHealthy(boolean healthy) {
			this.healthy = healthy;
		}

		public String getClientId() {
			return properties.getHost() + ":" + properties.getPort();
		}

		/**
		 * 检测当前ConsulClient的健康状况
		 * @return
		 */
		public void checkHealth() {
			String aclToken = properties.getAclToken();

			boolean healthy = false;
			try {
				Response<Map<String, List<String>>> response;
				if (!StringUtils.isEmpty(aclToken)) {
					response = client.getCatalogServices(QueryParams.DEFAULT, aclToken);
				}
				else {
					response = client.getCatalogServices(QueryParams.DEFAULT);
				}
				healthy = !response.getValue().isEmpty();
			}
			catch (Exception e) {
				LOGGER.error(">>> Check consul client health failed : {}",
						e.getMessage());
			}
			this.setHealthy(healthy);
			LOGGER.info(">>> Cluster consul client healthcheck finished: {}", this);
		}

		/**
		 * 排序，保证服务分布式部署时的全局hash一致性
		 */
		@Override
		public int compareTo(ConsulClientHolder o) {
			return getClientId().compareTo(o.getClientId());
		}

		@Override
		public String toString() {
			return "{ clientId = " + getClientId() + ", healthy = " + healthy
					+ ", primary = " + (this == primaryClient) + ", current = "
					+ (this == currentClient) + " }";
		}

	}

}
