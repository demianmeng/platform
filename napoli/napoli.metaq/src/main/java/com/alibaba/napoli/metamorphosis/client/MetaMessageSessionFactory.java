/*
 * (C) 2007-2012 Alibaba Group Holding Limited.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Authors:
 *   wuhua <wq163@163.com> , boyan <killme2008@gmail.com>
 */
package com.alibaba.napoli.metamorphosis.client;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import org.I0Itec.zkclient.ZkClient;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.alibaba.napoli.gecko.service.RemotingClient;
import com.alibaba.napoli.gecko.service.RemotingFactory;
import com.alibaba.napoli.gecko.service.config.ClientConfig;
import com.alibaba.napoli.gecko.service.exception.NotifyRemotingException;
import com.alibaba.napoli.metamorphosis.client.consumer.ConsisHashStrategy;
import com.alibaba.napoli.metamorphosis.client.consumer.ConsumerConfig;
import com.alibaba.napoli.metamorphosis.client.consumer.ConsumerZooKeeper;
import com.alibaba.napoli.metamorphosis.client.consumer.DefaultLoadBalanceStrategy;
import com.alibaba.napoli.metamorphosis.client.consumer.DumyRecoverManager;
import com.alibaba.napoli.metamorphosis.client.consumer.LoadBalanceStrategy;
import com.alibaba.napoli.metamorphosis.client.consumer.MessageConsumer;
import com.alibaba.napoli.metamorphosis.client.consumer.RecoverManager;
import com.alibaba.napoli.metamorphosis.client.consumer.SimpleMessageConsumer;
import com.alibaba.napoli.metamorphosis.client.consumer.SubscribeInfoManager;
import com.alibaba.napoli.metamorphosis.client.consumer.storage.OffsetStorage;
import com.alibaba.napoli.metamorphosis.client.consumer.storage.ZkOffsetStorage;
import com.alibaba.napoli.metamorphosis.client.producer.MessageProducer;
import com.alibaba.napoli.metamorphosis.client.producer.PartitionSelector;
import com.alibaba.napoli.metamorphosis.client.producer.ProducerZooKeeper;
import com.alibaba.napoli.metamorphosis.client.producer.RoundRobinPartitionSelector;
import com.alibaba.napoli.metamorphosis.client.producer.SimpleMessageProducer;
import com.alibaba.napoli.metamorphosis.exception.InvalidConsumerConfigException;
import com.alibaba.napoli.metamorphosis.exception.InvalidOffsetStorageException;
import com.alibaba.napoli.metamorphosis.exception.MetaClientException;
import com.alibaba.napoli.metamorphosis.exception.NetworkException;
import com.alibaba.napoli.metamorphosis.network.MetamorphosisWireFormatType;
import com.alibaba.napoli.metamorphosis.network.PrioritizeMessageCommand;
import com.alibaba.napoli.metamorphosis.utils.IdGenerator;
import com.alibaba.napoli.metamorphosis.utils.MetaZookeeper;
import com.alibaba.napoli.metamorphosis.utils.Utils;
import com.alibaba.napoli.metamorphosis.utils.ZkUtils;
import com.alibaba.napoli.metamorphosis.utils.ZkUtils.ZKConfig;

/**
 * 消息会话工厂，配置的优先级，优先使用传入的MetaClientConfig中的配置项，
 * 其次使用MetaClientConfig中的zkConfig配置的zk中的选项，如果都没有，则从diamond获取zk地址来获取配置项
 * 
 * @author boyan
 * @Date 2011-4-21
 * @author wuhua
 * @Date 2011-8-4
 */
public class MetaMessageSessionFactory implements MessageSessionFactory {
	protected RemotingClientWrapper remotingClient;
	private final MetaClientConfig metaClientConfig;
	private volatile ZkClient zkClient;

	static final Log log = LogFactory.getLog(MetaMessageSessionFactory.class);

	private final CopyOnWriteArrayList<ZkClientChangedListener> zkClientChangedListeners = new CopyOnWriteArrayList<ZkClientChangedListener>();

	protected final ProducerZooKeeper producerZooKeeper;

	private final ConsumerZooKeeper consumerZooKeeper;

	// private DiamondManager diamondManager;
	private final CopyOnWriteArrayList<Shutdownable> children = new CopyOnWriteArrayList<Shutdownable>();
	private volatile boolean shutdown;
	private volatile boolean isHutdownHookCalled = false;
	private final Thread shutdownHook;
	private ZKConfig zkConfig;
	private final RecoverManager recoverManager;
	private final SubscribeInfoManager subscribeInfoManager;

	protected final IdGenerator sessionIdGenerator;

	protected MetaZookeeper metaZookeeper;

	/**
	 * 返回通讯客户端
	 * 
	 * @return
	 */
	public RemotingClientWrapper getRemotingClient() {
		return this.remotingClient;
	}

	/**
	 * 返回订阅关系管理器
	 * 
	 * @return
	 */
	public SubscribeInfoManager getSubscribeInfoManager() {
		return this.subscribeInfoManager;
	}

	/**
	 * 返回客户端配置
	 * 
	 * @return
	 */
	public MetaClientConfig getMetaClientConfig() {
		return this.metaClientConfig;
	}

	/**
	 * 返回生产者和zk交互管理器
	 * 
	 * @return
	 */
	public ProducerZooKeeper getProducerZooKeeper() {
		return this.producerZooKeeper;
	}

	/**
	 * 返回消费者和zk交互管理器
	 * 
	 * @return
	 */
	public ConsumerZooKeeper getConsumerZooKeeper() {
		return this.consumerZooKeeper;
	}

	/**
	 * 返回本地恢复消息管理器
	 * 
	 * @return
	 */
	public RecoverManager getRecoverStorageManager() {
		return this.recoverManager;
	}

	/**
	 * 返回此工厂创建的所有子对象，如生产者、消费者等
	 * 
	 * @return
	 */
	public CopyOnWriteArrayList<Shutdownable> getChildren() {
		return this.children;
	}

	public MetaMessageSessionFactory(final MetaClientConfig metaClientConfig)
			throws MetaClientException {
		super();
		this.checkConfig(metaClientConfig);
		this.metaClientConfig = metaClientConfig;
		final ClientConfig clientConfig = new ClientConfig();
		clientConfig.setTcpNoDelay(false);
		clientConfig.setWireFormatType(new MetamorphosisWireFormatType());
		clientConfig.setMaxScheduleWrittenBytes(Runtime.getRuntime()
				.maxMemory() / 3);
		this.subscribeInfoManager = new SubscribeInfoManager();
		try {
			RemotingClient client = RemotingFactory.connect(clientConfig);
			client.registerProcessor(PrioritizeMessageCommand.class, new PriorityMessageManager(this));
			this.remotingClient = new RemotingClientWrapper(client);
		} catch (final NotifyRemotingException e) {
			throw new NetworkException("Create remoting client failed", e);
		}
		// 如果有设置，则使用设置的url并连接，否则使用zk发现服务器
		if (this.metaClientConfig.getServerUrl() != null) {
			this.connectServer(this.metaClientConfig);
		} else {
			this.initZooKeeper();
		}

		this.producerZooKeeper = new ProducerZooKeeper(this.metaZookeeper,
				this.remotingClient, this.zkClient, metaClientConfig);
		this.sessionIdGenerator = new IdGenerator();
		// modify by wuhua
		this.consumerZooKeeper = this.initConsumerZooKeeper(
				this.remotingClient, this.zkClient, this.zkConfig);
		this.zkClientChangedListeners.add(this.producerZooKeeper);
		this.zkClientChangedListeners.add(this.consumerZooKeeper);
		
//		this.recoverManager = new RecoverStorageManager(this.metaClientConfig,
//				this.subscribeInfoManager);
		this.recoverManager = new DumyRecoverManager();
		this.shutdownHook = new Thread() {

			@Override
			public void run() {
				try {
					MetaMessageSessionFactory.this.isHutdownHookCalled = true;
					MetaMessageSessionFactory.this.shutdown();
				} catch (final MetaClientException e) {
					log.error("关闭session factory失败", e);
				}
			}

		};
		Runtime.getRuntime().addShutdownHook(this.shutdownHook);
	}

	// add by wuhua
	protected ConsumerZooKeeper initConsumerZooKeeper(
			final RemotingClientWrapper remotingClientWrapper,
			final ZkClient zkClient2, final ZKConfig config) {
		return new ConsumerZooKeeper(this.metaZookeeper, this.remotingClient,
				this.zkClient, this.zkConfig);
	}

	private void checkConfig(final MetaClientConfig metaClientConfig)
			throws MetaClientException {
		if (metaClientConfig == null) {
			throw new MetaClientException("null configuration");
		}
	}

	private void connectServer(final MetaClientConfig metaClientConfig)
			throws NetworkException {
		try {
			this.remotingClient.connect(metaClientConfig.getServerUrl());
			this.remotingClient.awaitReadyInterrupt(metaClientConfig
					.getServerUrl());
		} catch (final NotifyRemotingException e) {
			throw new NetworkException("Connect to "
					+ metaClientConfig.getServerUrl() + " failed", e);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void initZooKeeper() throws MetaClientException {
		// 优先使用设置的zookeepr，其次从diamond获取
		this.zkConfig = null;
		if (this.metaClientConfig.getZkConfig() != null) {
			this.zkConfig = this.metaClientConfig.getZkConfig();

		} else {
			this.zkConfig = this.loadZkConfigFromDiamond();

		}
		if (this.zkConfig != null) {
			this.zkClient = new ZkClient(this.zkConfig.zkConnect,
					this.zkConfig.zkSessionTimeoutMs,
					this.zkConfig.zkConnectionTimeoutMs,
					new ZkUtils.StringSerializer());
			this.metaZookeeper = new MetaZookeeper(this.zkClient,
					this.zkConfig.zkRoot);
		} else {
			throw new MetaClientException("No zk config offered");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.alibaba.napoli.metamorphosis.client.SessionFactory#close()
	 */
	@Override
	public void shutdown() throws MetaClientException {
		if (this.shutdown) {
			return;
		}
		this.shutdown = true;
		// if (this.diamondManager != null) {
		// this.diamondManager.close();
		// }
		this.recoverManager.shutdown();
		// this.localMessageStorageManager.shutdown();
		for (final Shutdownable child : this.children) {
			child.shutdown();
		}
		try {
			this.remotingClient.stop();
		} catch (final NotifyRemotingException e) {
			throw new NetworkException("Stop remoting client failed", e);
		}
		if (this.zkClient != null) {
			this.zkClient.close();
		}
		if (!this.isHutdownHookCalled) {
			Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
		}

	}

	/**
	 * 暂时从zk.properties里加载
	 * 
	 * @return
	 */
	// 单元测试要是通不过,请修改resources/zk.properties里的zk地址
	private ZKConfig loadZkConfigFromDiamond() {
		// 尝试从diamond获取
		// this.diamondManager =
		// new DefaultDiamondManager(this.metaClientConfig.getDiamondZKGroup(),
		// this.metaClientConfig.getDiamondZKDataId(), new ManagerListener() {
		// @Override
		// public void receiveConfigInfo(final String configInfo) {
		// log.info("Receiving new diamond zk config:" + configInfo);
		// log.info("Closing zk client");
		// MetaMessageSessionFactory.this.zkClient.close();
		// final Properties properties = new Properties();
		// try {
		// properties.load(new StringReader(configInfo));
		// final ZKConfig zkConfig = DiamondUtils.getZkConfig(properties);
		// MetaMessageSessionFactory.this.zkClient.close();
		// Thread.sleep(zkConfig.zkSyncTimeMs);
		// log.info("Initialize zk client...");
		// final ZkClient newClient =
		// new ZkClient(zkConfig.zkConnect, zkConfig.zkSessionTimeoutMs,
		// zkConfig.zkConnectionTimeoutMs, new ZkUtils.StringSerializer());
		// log.info("Begin to notify zkClient has been changed...");
		// MetaMessageSessionFactory.this.metaZookeeper.setZkClient(newClient);
		// MetaMessageSessionFactory.this.notifyZkClientChanged(newClient);
		// MetaMessageSessionFactory.this.zkClient = newClient;
		// log.info("End notifying zkClient has been changed...");
		// }
		// catch (final Exception e) {
		// log.error("从diamond加载zk配置失败", e);
		// }
		// }
		//
		//
		// @Override
		// public Executor getExecutor() {
		// return null;
		// }
		// });
		try {
			final Properties properties = Utils.getResourceAsProperties(
					"zk.properties", "GBK");
			final ZKConfig zkConfig = new ZKConfig();
			if (StringUtils.isNotBlank(properties.getProperty("zk.zkConnect"))) {
				zkConfig.zkConnect = properties.getProperty("zk.zkConnect");
			}

			if (StringUtils.isNotBlank(properties
					.getProperty("zk.zkSessionTimeoutMs"))) {
				zkConfig.zkSessionTimeoutMs = Integer.parseInt(properties
						.getProperty("zk.zkSessionTimeoutMs"));
			}

			if (StringUtils.isNotBlank(properties
					.getProperty("zk.zkConnectionTimeoutMs"))) {
				zkConfig.zkConnectionTimeoutMs = Integer.parseInt(properties
						.getProperty("zk.zkConnectionTimeoutMs"));
			}

			if (StringUtils.isNotBlank(properties
					.getProperty("zk.zkSyncTimeMs"))) {
				zkConfig.zkSyncTimeMs = Integer.parseInt(properties
						.getProperty("zk.zkSyncTimeMs"));
			}

			return zkConfig;// DiamondUtils.getZkConfig(this.diamondManager,
							// 10000);
		} catch (final IOException e) {
			log.error("zk配置失败", e);
			return null;
		}
	}

	private void notifyZkClientChanged(final ZkClient zkClient) {
		for (final ZkClientChangedListener listener : this.zkClientChangedListeners) {
			try {
				listener.onZkClientChanged(zkClient);
			} catch (final Throwable t) {
				log.error("更新zKClient失败", t);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.alibaba.napoli.metamorphosis.client.SessionFactory#createProducer(com.taobao
	 * .metamorphosis.client.producer.PartitionSelector)
	 */
	@Override
	public MessageProducer createProducer(
			final PartitionSelector partitionSelector) {
		return this.createProducer(partitionSelector, false);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.taobao.metamorphosis.client.SessionFactory#createProducer()
	 */
	@Override
	public MessageProducer createProducer() {
		return this.createProducer(new RoundRobinPartitionSelector(), false);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.alibaba.napoli.metamorphosis.client.SessionFactory#createProducer(boolean)
	 */
	@Override
	@Deprecated
	public MessageProducer createProducer(final boolean ordered) {
		return this.createProducer(new RoundRobinPartitionSelector(), ordered);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.alibaba.napoli.metamorphosis.client.SessionFactory#createProducer(com.alibaba.napoli
	 * .metamorphosis.client.producer.PartitionSelector, boolean)
	 */
	@Override
	@Deprecated
	public MessageProducer createProducer(
			final PartitionSelector partitionSelector, final boolean ordered) {
		if (partitionSelector == null) {
			throw new IllegalArgumentException("Null partitionSelector");
		}
		return this.addChild(new SimpleMessageProducer(this,
				this.remotingClient, partitionSelector, this.producerZooKeeper,
				this.sessionIdGenerator.generateId()));
	}

	protected <T extends Shutdownable> T addChild(final T child) {
		this.children.add(child);
		return child;
	}

	/**
	 * 删除子会话
	 * 
	 * @param <T>
	 * @param child
	 */
	public <T extends Shutdownable> void removeChild(final T child) {
		this.children.remove(child);
	}

	private synchronized MessageConsumer createConsumer0(
			final ConsumerConfig consumerConfig,
			final OffsetStorage offsetStorage,
			final RecoverManager recoverManager0) {
		if (consumerConfig.getServerUrl() == null) {
			consumerConfig.setServerUrl(this.metaClientConfig.getServerUrl());
		}
		if (offsetStorage == null) {
			throw new InvalidOffsetStorageException("Null offset storage");
		}
		// 必要时启动recover
		if (!recoverManager0.isStarted()) {
			recoverManager0.start(this.metaClientConfig);
		}
		this.checkConsumerConfig(consumerConfig);
		return this.addChild(new SimpleMessageConsumer(this,
				this.remotingClient, consumerConfig, this.consumerZooKeeper,
				this.producerZooKeeper, this.subscribeInfoManager,
				recoverManager0, offsetStorage, this
						.createLoadBalanceStrategy(consumerConfig)));
	}

	protected LoadBalanceStrategy createLoadBalanceStrategy(
			final ConsumerConfig consumerConfig) {
		switch (consumerConfig.getLoadBalanceStrategyType()) {
		case DEFAULT:
			return new DefaultLoadBalanceStrategy();
		case CONSIST:
			return new ConsisHashStrategy();
		default:
			throw new IllegalArgumentException(
					"Unknow load balance strategy type:"
							+ consumerConfig.getLoadBalanceStrategyType());
		}
	}

	protected MessageConsumer createConsumer(
			final ConsumerConfig consumerConfig,
			final OffsetStorage offsetStorage,
			final RecoverManager recoverManager0) {
		OffsetStorage offsetStorageCopy = offsetStorage;
		if (offsetStorageCopy == null) {
			offsetStorageCopy = new ZkOffsetStorage(this.metaZookeeper,
					this.zkClient);
			this.zkClientChangedListeners
					.add((ZkOffsetStorage) offsetStorageCopy);
		}

		return this
				.createConsumer0(consumerConfig, offsetStorageCopy,
						recoverManager0 != null ? recoverManager0
								: this.recoverManager);

	}

	@Override
	public MessageConsumer createConsumer(final ConsumerConfig consumerConfig,
			final OffsetStorage offsetStorage) {
		return this.createConsumer(consumerConfig, offsetStorage,
				this.recoverManager);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.alibaba.napoli.metamorphosis.client.SessionFactory#createConsumer(com.alibaba.napoli
	 * .metamorphosis.client.consumer.ConsumerConfig)
	 */
	@Override
	public MessageConsumer createConsumer(final ConsumerConfig consumerConfig) {
		// final ZkOffsetStorage offsetStorage = new
		// ZkOffsetStorage(this.zkClient);
		// this.zkClientChangedListeners.add(offsetStorage);
		// return this.createConsumer(consumerConfig, offsetStorage);
		return this.createConsumer(consumerConfig, null, null);
	}

	static final char[] INVALID_GROUP_CHAR = { '~', '!', '#', '$', '%', '^',
			'&', '*', '(', ')', '+', '=', '`', '\'', '"', ',', ';', '/', '?',
			'[', ']', '<', '>', '.', ':' };

	protected void checkConsumerConfig(final ConsumerConfig consumerConfig) {
		if (StringUtils.isBlank(consumerConfig.getGroup())) {
			throw new InvalidConsumerConfigException("Blank group");
		}
		final char[] chary = new char[consumerConfig.getGroup().length()];
		consumerConfig.getGroup().getChars(0, chary.length, chary, 0);
		for (final char ch : chary) {
			for (final char invalid : INVALID_GROUP_CHAR) {
				if (ch == invalid) {
					throw new InvalidConsumerConfigException(
							"Group name has invalid character " + ch);
				}
			}
		}
		if (consumerConfig.getFetchRunnerCount() <= 0) {
			throw new InvalidConsumerConfigException(
					"Invalid fetchRunnerCount:"
							+ consumerConfig.getFetchRunnerCount());
		}
		if (consumerConfig.getFetchTimeoutInMills() <= 0) {
			throw new InvalidConsumerConfigException(
					"Invalid fetchTimeoutInMills:"
							+ consumerConfig.getFetchTimeoutInMills());
		}
	}

}