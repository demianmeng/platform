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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.napoli.metamorphosis.exception.MetaClientException;

/**
 * 订阅信息管理器
 */
public class SubscribeInfoManager {
	private final ConcurrentHashMap<String/* group */, ConcurrentHashMap<String/* topic */, SubscriberInfo>> groupTopicSubcriberRegistry = new ConcurrentHashMap<String/* group */, ConcurrentHashMap<String, SubscriberInfo>>();

	public void subscribe(final String topic, final String group,
			final int maxSize, final MessageListener messageListener)
			throws MetaClientException {
		final ConcurrentHashMap<String, SubscriberInfo> topicSubsriberRegistry = this
				.getTopicSubscriberRegistry(group);
		SubscriberInfo info = topicSubsriberRegistry.get(topic);
		if (info == null) {
			info = new SubscriberInfo(messageListener, maxSize);
			final SubscriberInfo oldInfo = topicSubsriberRegistry.putIfAbsent(
					topic, info);
			if (oldInfo != null) {
				throw new MetaClientException("Topic=" + topic
						+ " has been subscribered by group " + group);
			}
		} else {
			throw new MetaClientException("Topic=" + topic
					+ " has been subscribered by group " + group);
		}
	}

	private ConcurrentHashMap<String, SubscriberInfo> getTopicSubscriberRegistry(
			final String group) throws MetaClientException {
		ConcurrentHashMap<String/* topic */, SubscriberInfo> topicSubsriberRegistry = this.groupTopicSubcriberRegistry
				.get(group);
		if (topicSubsriberRegistry == null) {
			topicSubsriberRegistry = new ConcurrentHashMap<String, SubscriberInfo>();
			final ConcurrentHashMap<String/* topic */, SubscriberInfo> old = this.groupTopicSubcriberRegistry
					.putIfAbsent(group, topicSubsriberRegistry);
			if (old != null) {
				topicSubsriberRegistry = old;
			}
		}
		return topicSubsriberRegistry;
	}
	
	public List<MessageListener> getListeners(String topic){
		List<MessageListener> listeners = new ArrayList<MessageListener>();
		for(Map<String/* topic */, SubscriberInfo> map:groupTopicSubcriberRegistry.values()){
			if(map.containsKey(topic)){
				SubscriberInfo info = map.get(topic);
				MessageListener listener = info.getMessageListener();
				if(listener !=null){
					listeners.add(listener);
				}
			}
		}
		return listeners;
	}

	public MessageListener getMessageListener(final String topic,
			final String group) throws MetaClientException {
		final ConcurrentHashMap<String, SubscriberInfo> topicSubsriberRegistry = this.groupTopicSubcriberRegistry
				.get(group);
		if (topicSubsriberRegistry == null) {
			return null;
		}
		final SubscriberInfo info = topicSubsriberRegistry.get(topic);
		if (info == null) {
			return null;
		}
		return info.getMessageListener();
	}

	public void removeGroup(final String group) {
		this.groupTopicSubcriberRegistry.remove(group);
	}

	ConcurrentHashMap<String, ConcurrentHashMap<String, SubscriberInfo>> getGroupTopicSubcriberRegistry() {
		return this.groupTopicSubcriberRegistry;
	}
}