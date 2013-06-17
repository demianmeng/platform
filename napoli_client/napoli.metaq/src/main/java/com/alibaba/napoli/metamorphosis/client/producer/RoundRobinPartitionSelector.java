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
package com.alibaba.napoli.metamorphosis.client.producer;

import java.util.List;

import com.alibaba.napoli.gecko.core.util.PositiveAtomicCounter;
import com.alibaba.napoli.metamorphosis.Message;
import com.alibaba.napoli.metamorphosis.cluster.Partition;
import com.alibaba.napoli.metamorphosis.exception.MetaClientException;

/**
 * 轮询的分区选择器，默认使用此选择器
 * 
 * @author boyan
 * @Date 2011-4-26
 * 
 */
public class RoundRobinPartitionSelector implements PartitionSelector {

	private final PositiveAtomicCounter sets = new PositiveAtomicCounter();

	@Override
	public Partition getPartition(final String topic,
			final List<Partition> partitions, final Message message)
			throws MetaClientException {
		if (partitions == null || partitions.size() == 0) {
			throw new MetaClientException(
					"There is no aviable partition for topic " + topic);
		}
		try {
			return partitions.get(this.sets.incrementAndGet()
					% partitions.size());
		} catch (final Throwable t) {
			throw new MetaClientException(t);
		}
	}

}