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
package com.alibaba.napoli.metamorphosis.client.consumer;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.alibaba.napoli.gecko.service.exception.NotifyRemotingException;
import com.alibaba.napoli.metamorphosis.Message;
import com.alibaba.napoli.metamorphosis.MessageAccessor;
import com.alibaba.napoli.metamorphosis.cluster.Partition;
import com.alibaba.napoli.metamorphosis.exception.InvalidMessageException;
import com.alibaba.napoli.metamorphosis.exception.MetaClientException;
import com.alibaba.napoli.metamorphosis.utils.MetaStatLog;
import com.alibaba.napoli.metamorphosis.utils.StatConstants;

/**
 * 消息抓取管理器的实现
 * 
 * @author boyan(boyan@taobao.com)
 * @date 2011-9-13
 * 
 */
public class SimpleFetchManager implements FetchManager {

	private volatile boolean shutdown = false;

	private Thread[] fetchRunners;

	private int fetchRequestCount;

	private FetchRequestQueue requestQueue;

	private final ConsumerConfig consumerConfig;

	private final InnerConsumer consumer;

	private List<TopicPartitionRegInfo> topicPartitionRegInfos;

	public SimpleFetchManager(final ConsumerConfig consumerConfig,
			final InnerConsumer consumer) {
		super();
		this.consumerConfig = consumerConfig;
		this.consumer = consumer;
	}

	@Override
	public boolean isShutdown() {
		return this.shutdown;
	}

	@Override
	public void stopFetchRunner() throws InterruptedException {
		topicPartitionRegInfos = null;
		this.shutdown = true;
		// 中断所有任务
		if (this.fetchRunners != null) {
			for (final Thread thread : this.fetchRunners) {
				if (thread != null) {
					thread.interrupt();
					try {
						thread.join(5000);
					} catch (final InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}

			}
		}
		// 等待所有任务结束
		if (this.requestQueue != null) {
			while (this.requestQueue.size() != this.fetchRequestCount) {
				Thread.sleep(50);
			}
		}

	}

	@Override
	public void resetFetchState() {
		topicPartitionRegInfos = null;
		this.requestQueue = new FetchRequestQueue();
		this.fetchRunners = new Thread[this.consumerConfig
				.getFetchRunnerCount()];
		for (int i = 0; i < this.fetchRunners.length; i++) {
			this.fetchRunners[i] = new Thread(new FetchRequestRunner());
			this.fetchRunners[i].setName(this.consumerConfig.getGroup()
					+ "Fetch-Runner-" + i);
		}

	}

	@Override
	public void startFetchRunner() {
		// 保存请求数目，在停止的时候要检查
		topicPartitionRegInfos = this.requestQueue.getTopicPartitionRegInfos();
		this.fetchRequestCount = this.requestQueue.size();
		this.shutdown = false;
		for (final Thread thread : this.fetchRunners) {
			thread.start();
		}

	}

	@Override
	public void addFetchRequest(final FetchRequest request) {
		this.requestQueue.offer(request);

	}

	FetchRequest takeFetchRequest() throws InterruptedException {
		return this.requestQueue.take();
	}

	static final Log log = LogFactory.getLog(SimpleFetchManager.class);

	class FetchRequestRunner implements Runnable {

		private static final int DELAY_NPARTS = 10;

		@Override
		public void run() {
			while (!SimpleFetchManager.this.shutdown) {
				try {
					final FetchRequest request = SimpleFetchManager.this.requestQueue
							.take();
					this.processRequest(request);
				} catch (final InterruptedException e) {
					// take响应中断，忽略
				}

			}
		}

		void processRequest(final FetchRequest request) {
			try {
				final MessageIterator iterator = SimpleFetchManager.this.consumer
						.fetch(request, -1, null);
				final MessageListener listener = SimpleFetchManager.this.consumer
						.getMessageListener(request.getTopic());
				this.notifyListener(request, iterator, listener);
			} catch (final MetaClientException e) {
				this.updateDelay(request);
				this.LogAddRequest(request, e);
			} catch (final InterruptedException e) {
				// 仍然需要加入队列，可能是停止信号
				SimpleFetchManager.this.addFetchRequest(request);
			} catch (final Throwable e) {
				this.updateDelay(request);
				this.LogAddRequest(request, e);
			}
		}

		private long lastLogNoConnectionTime;

		private void LogAddRequest(final FetchRequest request, final Throwable e) {
			if (e instanceof MetaClientException
					&& e.getCause() instanceof NotifyRemotingException
					&& e.getMessage().contains("无可用连接")) {
				// 最多30秒打印一次
				final long now = System.currentTimeMillis();
				if (this.lastLogNoConnectionTime <= 0
						|| now - this.lastLogNoConnectionTime > 30000) {
					log.error("获取消息失败,topic=" + request.getTopic()
							+ ",partition=" + request.getPartition(), e);
					this.lastLogNoConnectionTime = now;
				}
			} else {
				log.error("获取消息失败,topic=" + request.getTopic() + ",partition="
						+ request.getPartition(), e);
			}
			SimpleFetchManager.this.addFetchRequest(request);
		}

		private void getOffsetAddRequest(final FetchRequest request,
				final InvalidMessageException e) {
			try {
				final long newOffset = SimpleFetchManager.this.consumer
						.offset(request);
				request.resetRetries();
				request.setOffset(newOffset, request.getLastMessageId(),
						request.getPartitionObject().isAutoAck());
			} catch (final MetaClientException ex) {
				log.error("查询offset失败,topic=" + request.getTopic()
						+ ",partition=" + request.getPartition(), e);
			} finally {
				SimpleFetchManager.this.addFetchRequest(request);
			}
		}

		private void notifyListener(final FetchRequest request,
				final MessageIterator it, final MessageListener listener) {
			if (listener != null) {
				if (listener.getExecutor() != null) {
					try {
						listener.getExecutor().execute(new Runnable() {
							@Override
							public void run() {
								FetchRequestRunner.this.receiveMessages(
										request, it, listener);
							}
						});
					} catch (final RejectedExecutionException e) {
						log.error(
								"MessageListener线程池繁忙，无法处理消息,topic="
										+ request.getTopic() + ",partition="
										+ request.getPartition(), e);
						SimpleFetchManager.this.addFetchRequest(request);
					}

				} else {
					this.receiveMessages(request, it, listener);
				}
			}
		}

		/**
		 * 处理消息的整个流程：<br>
		 * <ul>
		 * <li>1.判断是否有消息可以处理，如果没有消息并且有数据递增重试次数，并判断是否需要递增maxSize</li>
		 * <li>2.判断消息是否重试多次，如果超过设定次数，就跳过该消息继续往下走。跳过的消息可能在本地重试或者交给notify重投</li>
		 * <li>3.进入消息处理流程，根据是否自动ack的情况进行处理:
		 * <ul>
		 * <li>(1)如果消息是自动ack，如果消费发生异常，则不修改offset，延迟消费等待重试</li>
		 * <li>(2)如果消息是自动ack，如果消费正常，递增offset</li>
		 * <li>(3)如果消息非自动ack，如果消费正常并ack，将offset修改为tmp offset，并重设tmp offset</li>
		 * <li>(4)如果消息非自动ack，如果消费正常并rollback，不递增offset，重设tmp offset</li>
		 * <li>(5)如果消息非自动ack，如果消费正常不ack也不rollback，不递增offset，递增tmp offset</li>
		 * </ul>
		 * </li>
		 * </ul>
		 * 
		 * @param request
		 * @param it
		 * @param listener
		 */
		private void receiveMessages(final FetchRequest request,
				final MessageIterator it, final MessageListener listener) {
			if (it != null && it.hasNext()) {
				if (this.processWhenRetryTooMany(request, it)) {
					return;
				}
				final Partition partition = request.getPartitionObject();
				if (this.processReceiveMessage(request, it, listener, partition)) {
					return;
				}
				this.postReceiveMessage(request, it, partition);
			} else {

				// 尝试多次无法解析出获取的数据，可能需要增大maxSize
				if (SimpleFetchManager.this.isRetryTooManyForIncrease(request)
						&& it != null && it.getDataLength() > 0) {
					request.increaseMaxSize();
					log.warn("警告，第" + request.getRetries() + "次无法拉取topic="
							+ request.getTopic() + ",partition="
							+ request.getPartitionObject() + "的消息，递增maxSize="
							+ request.getMaxSize() + " Bytes");
				}

				// 一定要判断it是否为null,否则正常的拉到结尾时(返回null)也将进行Retries记数,会导致以后再拉到消息时进入recover
				if (it != null) {
					request.incrementRetriesAndGet();
				}

				this.updateDelay(request);
				SimpleFetchManager.this.addFetchRequest(request);
			}
		}

		/**
		 * 返回是否需要跳过后续的处理
		 * 
		 * @param request
		 * @param it
		 * @param listener
		 * @param partition
		 * @return
		 */
		private boolean processReceiveMessage(final FetchRequest request,
				final MessageIterator it, final MessageListener listener,
				final Partition partition) {
			int count = 0;
			while (it.hasNext()) {
				final int prevOffset = it.getOffset();
				try {
					final Message msg = it.next();
					MessageAccessor.setPartition(msg, partition);
					if(((SimpleMessageConsumer)consumer).canConsumer(msg))
						listener.recieveMessages(msg);
					if (partition.isAutoAck()) {
						count++;
					} else {
						// 提交或者回滚都必须跳出循环
						if (partition.isAcked()) {
							count++;
							break;
						} else if (partition.isRollback()) {
							break;
						} else {
							// 不是提交也不是回滚，仅递增计数
							count++;
						}
					}
				} catch (final InvalidMessageException e) {
					MetaStatLog.addStat(null, StatConstants.INVALID_MSG_STAT,
							request.getTopic());
					// 消息体非法，获取有效offset，重新发起查询
					this.getOffsetAddRequest(request, e);
					return true;
				} catch (final Throwable e) {
					// 将指针移到上一条消息
					it.setOffset(prevOffset);
					log.error(
							"MessageListener处理消息异常,topic=" + request.getTopic()
									+ ",partition=" + request.getPartition(), e);
					// 跳出循环，处理消息异常，到此为止
					break;
				}
			}
			MetaStatLog.addStatValue2(null, StatConstants.GET_MSG_COUNT_STAT,
					request.getTopic(), count);
			return false;
		}

		private boolean processWhenRetryTooMany(final FetchRequest request,
				final MessageIterator it) {
			if (SimpleFetchManager.this.isRetryTooMany(request)) {
				try {
					final Message couldNotProecssMsg = it.next();
					MessageAccessor.setPartition(couldNotProecssMsg,
							request.getPartitionObject());
					MetaStatLog.addStat(null, StatConstants.SKIP_MSG_COUNT,
							couldNotProecssMsg.getTopic());
					SimpleFetchManager.this.consumer
							.appendCouldNotProcessMessage(couldNotProecssMsg);
				} catch (final InvalidMessageException e) {
					MetaStatLog.addStat(null, StatConstants.INVALID_MSG_STAT,
							request.getTopic());
					// 消息体非法，获取有效offset，重新发起查询
					this.getOffsetAddRequest(request, e);
					return true;
				} catch (final Throwable t) {
					this.LogAddRequest(request, t);
					return true;
				}

				request.resetRetries();
				// 跳过这条不能处理的消息
				request.setOffset(request.getOffset() + it.getOffset(), it
						.getPrevMessage().getId(), true);
				// 强制设置延迟为0
				request.setDelay(0);
				SimpleFetchManager.this.addFetchRequest(request);
				return true;
			} else {
				return false;
			}
		}

		private void postReceiveMessage(final FetchRequest request,
				final MessageIterator it, final Partition partition) {
			// 如果offset仍然没有前进，递增重试次数
			if (it.getOffset() == 0) {
				request.incrementRetriesAndGet();
			} else {
				request.resetRetries();
			}

			// 非自动ack模式
			if (!partition.isAutoAck()) {
				// 如果是回滚,则回滚offset，再次发起请求
				if (partition.isRollback()) {
					request.rollbackOffset();
					partition.reset();
					this.addRequst(request);
				}
				// 如果提交，则更新临时offset到存储
				else if (partition.isAcked()) {
					partition.reset();
					this.ackRequest(request, it, true);
				} else {
					// 都不是，递增临时offset
					this.ackRequest(request, it, false);
				}
			} else {
				// 自动ack模式
				this.ackRequest(request, it, true);
			}
		}

		private void ackRequest(final FetchRequest request,
				final MessageIterator it, final boolean ack) {
			request.setOffset(request.getOffset() + it.getOffset(), it
					.getPrevMessage() != null ? it.getPrevMessage().getId()
					: -1, ack);
			this.addRequst(request);
		}

		private void addRequst(final FetchRequest request) {
			final long delay = this.getRetryDelay(request);
			request.setDelay(delay);
			SimpleFetchManager.this.addFetchRequest(request);
		}

		private long getRetryDelay(final FetchRequest request) {
			final long maxDelayFetchTimeInMills = SimpleFetchManager.this
					.getMaxDelayFetchTimeInMills();
			final long nPartsDelayTime = maxDelayFetchTimeInMills
					/ DELAY_NPARTS;
			// 延迟时间为：最大延迟时间/10*重试次数
			long delay = nPartsDelayTime * request.getRetries();
			if (delay > maxDelayFetchTimeInMills) {
				delay = maxDelayFetchTimeInMills;
			}
			return delay;
		}

		// 延时查询
		private void updateDelay(final FetchRequest request) {
			final long delay = this.getNextDelay(request);
			request.setDelay(delay);
		}

		private long getNextDelay(final FetchRequest request) {
			final long maxDelayFetchTimeInMills = SimpleFetchManager.this
					.getMaxDelayFetchTimeInMills();
			// 每次1/10递增,最大MaxDelayFetchTimeInMills
			final long nPartsDelayTime = maxDelayFetchTimeInMills
					/ DELAY_NPARTS;
			long delay = request.getDelay() + nPartsDelayTime;
			if (delay > maxDelayFetchTimeInMills) {
				delay = maxDelayFetchTimeInMills;
			}
			return delay;
		}

	}

	boolean isRetryTooMany(final FetchRequest request) {
		return request.getRetries() > this.consumerConfig.getMaxFetchRetries();
	}

	boolean isRetryTooManyForIncrease(final FetchRequest request) {
		return request.getRetries() > this.consumerConfig
				.getMaxIncreaseFetchDataRetries();
	}

	long getMaxDelayFetchTimeInMills() {
		return this.consumerConfig.getMaxDelayFetchTimeInMills();
	}

	@Override
	public boolean canConsume(String topic, int brokerId, int partition) {
		if (topicPartitionRegInfos == null)
			return false;
		for (TopicPartitionRegInfo info : topicPartitionRegInfos) {
			if(info == null || info.getPartition() == null)
				continue;
			if (StringUtils.equals(topic, info.getTopic())
					&& info.getPartition().getBrokerId() == brokerId
					&& info.getPartition().getPartition() == partition) {
				return true;

			}
		}
		return false;
	}

}