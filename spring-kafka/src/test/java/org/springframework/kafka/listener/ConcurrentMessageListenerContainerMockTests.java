/*
 * Copyright 2019-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.listener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaResourceHolder;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.event.ConsumerFailedToStartEvent;
import org.springframework.kafka.event.ConsumerStartedEvent;
import org.springframework.kafka.event.ConsumerStartingEvent;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.ContainerProperties.AssignmentCommitOption;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.transaction.KafkaAwareTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * @author Gary Russell
 * @author Wang Zhiyang
 * @author Soby Chacko
 *
 * @since 2.2.4
 *
 */
public class ConcurrentMessageListenerContainerMockTests {

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	void testThreadStarvation() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		Set<String> consumerThreads = ConcurrentHashMap.newKeySet();
		CountDownLatch latch = new CountDownLatch(2);
		willAnswer(invocation -> {
			consumerThreads.add(Thread.currentThread().getName());
			latch.countDown();
			Thread.sleep(50);
			return new ConsumerRecords<>(Collections.emptyMap(), Map.of());
		}).given(consumer).poll(any());
		given(consumerFactory.createConsumer(anyString(), anyString(), anyString(),
				eq(KafkaTestUtils.defaultPropertyOverrides())))
						.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener((MessageListener) record -> { });
		containerProperties.setMissingTopicsFatal(false);
		ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
		exec.setCorePoolSize(1);
		exec.afterPropertiesSet();
		containerProperties.setListenerTaskExecutor(exec);
		containerProperties.setConsumerStartTimeout(Duration.ofMillis(50));
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer<>(consumerFactory,
				containerProperties);
		container.setConcurrency(2);
		CountDownLatch startedLatch = new CountDownLatch(2);
		CountDownLatch failedLatch = new CountDownLatch(1);
		container.setApplicationEventPublisher(event -> {
			if (event instanceof ConsumerStartingEvent || event instanceof ConsumerStartedEvent) {
				startedLatch.countDown();
			}
			else if (event instanceof ConsumerFailedToStartEvent) {
				failedLatch.countDown();
			}
		});
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(consumerThreads).hasSize(1);
		assertThat(startedLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(failedLatch.await(10, TimeUnit.SECONDS)).isTrue();
		container.stop();
		exec.destroy();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	void testCorrectContainerForConsumerError() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		AtomicBoolean first = new AtomicBoolean(true);
		willAnswer(invocation -> {
			if (first.getAndSet(false)) {
				throw new RuntimeException("planned");
			}
			Thread.sleep(100);
			return new ConsumerRecords<>(Collections.emptyMap(), Map.of());
		}).given(consumer).poll(any());
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
			.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener((MessageListener) record -> { });
		containerProperties.setMissingTopicsFatal(false);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer<>(consumerFactory,
				containerProperties);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<MessageListenerContainer> errorContainer = new AtomicReference<>();
		container.setCommonErrorHandler(new CommonErrorHandler() {

			@Override
			public boolean seeksAfterHandling() {
				return true;
			}

			@Override
			public void handleOtherException(Exception thrownException, Consumer<?, ?> consumer,
					MessageListenerContainer container, boolean batchListener) {

				errorContainer.set(container);
				latch.countDown();
			}

		});
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(errorContainer.get()).isSameAs(container);
		container.stop();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	void delayedIdleEvent() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		AtomicBoolean firstEvent = new AtomicBoolean(false);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
		recordMap.put(new TopicPartition("foo", 0),
				Collections.singletonList(new ConsumerRecord("foo", 0, 0, null, "bar")));
		ConsumerRecords records = new ConsumerRecords<>(recordMap, Map.of());
		willAnswer(invocation -> {
			Thread.sleep(50);
			return firstEvent.getAndSet(false) ? records : new ConsumerRecords<>(Collections.emptyMap(), Map.of());
		}).given(consumer).poll(any());
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
			.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener((MessageListener) record -> { });
		containerProperties.setMissingTopicsFatal(false);
		containerProperties.setIdleEventInterval(100L);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer<>(consumerFactory,
				containerProperties);
		CountDownLatch latch1 = new CountDownLatch(1);
		CountDownLatch latch2 = new CountDownLatch(2);
		container.setApplicationEventPublisher(event -> {
			if (event instanceof ListenerContainerIdleEvent) {
				latch1.countDown();
				latch2.countDown();
			}
		});
		container.start();
		long t1 = System.currentTimeMillis();
		assertThat(latch1.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(System.currentTimeMillis() - t1).isGreaterThanOrEqualTo(500L);
		firstEvent.set(true);
		assertThat(latch2.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(System.currentTimeMillis() - t1).isLessThan(1000L);
		container.stop();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	void testConsumerExitWhenNotAuthorized() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		willAnswer(invocation -> {
			Thread.sleep(100);
			throw new GroupAuthorizationException("grp");
		}).given(consumer).poll(any());
		CountDownLatch latch = new CountDownLatch(1);
		willAnswer(invocation -> {
			latch.countDown();
			return null;
		}).given(consumer).close();
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
			.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener((MessageListener) record -> { });
		containerProperties.setMissingTopicsFatal(false);
		containerProperties.setShutdownTimeout(10);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer<>(consumerFactory,
				containerProperties);
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(consumer).close();
		container.stop();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	@DisplayName("Seek on TL callback when idle")
	void testSyncRelativeSeeks() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		TestMessageListener1 listener = new TestMessageListener1();
		ConsumerRecords empty = new ConsumerRecords<>(Collections.emptyMap(), Map.of());
		willAnswer(invocation -> {
			Thread.sleep(10);
			return empty;
		}).given(consumer).poll(any());
		TopicPartition tp0 = new TopicPartition("foo", 0);
		TopicPartition tp1 = new TopicPartition("foo", 1);
		TopicPartition tp2 = new TopicPartition("foo", 2);
		TopicPartition tp3 = new TopicPartition("foo", 3);
		List<TopicPartition> assignments = List.of(tp0, tp1, tp2, tp3);
		willAnswer(invocation -> {
			((ConsumerRebalanceListener) invocation.getArgument(1))
				.onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		given(consumer.position(any())).willReturn(100L);
		given(consumer.beginningOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 0L)));
		given(consumer.endOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 200L)));
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
			.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener(listener);
		containerProperties.setIdleEventInterval(10L);
		containerProperties.setMissingTopicsFatal(false);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(consumerFactory,
				containerProperties);
		container.start();
		assertThat(listener.latch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(consumer).seek(tp0, 60L);
		verify(consumer).seek(tp1, 140L);
		verify(consumer).seek(tp2, 160L);
		verify(consumer).seek(tp3, 40L);
		container.stop();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	void seekOffsetFromComputeFnOnInitAssignmentAndIdleContainer() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		TestMessageListener3 listener = new TestMessageListener3();
		ConsumerRecords empty = new ConsumerRecords<>(Collections.emptyMap(), Map.of());
		willAnswer(invocation -> {
			Thread.sleep(10);
			return empty;
		}).given(consumer).poll(any());
		TopicPartition tp0 = new TopicPartition("test-topic", 0);
		TopicPartition tp1 = new TopicPartition("test-topic", 1);
		TopicPartition tp2 = new TopicPartition("test-topic", 2);
		TopicPartition tp3 = new TopicPartition("test-topic", 3);
		List<TopicPartition> assignments = List.of(tp0, tp1, tp2, tp3);
		willAnswer(invocation -> {
			((ConsumerRebalanceListener) invocation.getArgument(1))
					.onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		given(consumer.position(any())).willReturn(30L); // current offset position is always 30
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
				.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("test-topic");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener(listener);
		containerProperties.setIdleEventInterval(10L);
		containerProperties.setMissingTopicsFatal(false);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(consumerFactory,
				containerProperties);
		container.start();
		assertThat(listener.latch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(consumer).seek(tp0, 20L);
		verify(consumer).seek(tp1, 21L);
		verify(consumer).seek(tp2, 22L);
		verify(consumer).seek(tp3, 23L);

		verify(consumer).seek(tp0, 30L);
		verify(consumer).seek(tp1, 30L);
		verify(consumer).seek(tp2, 30L);
		verify(consumer).seek(tp3, 30L);
		container.stop();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	void seekOffsetFromComputeFnFromActiveListener() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		TestMessageListener4 listener = new TestMessageListener4();
		CountDownLatch latch = new CountDownLatch(2);
		TopicPartition tp0 = new TopicPartition("test-topic", 0);
		TopicPartition tp1 = new TopicPartition("test-topic", 1);
		TopicPartition tp2 = new TopicPartition("test-topic", 2);
		TopicPartition tp3 = new TopicPartition("test-topic", 3);
		List<TopicPartition> assignments = List.of(tp0, tp1, tp2, tp3);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
		recordMap.put(tp0, Collections.singletonList(new ConsumerRecord("test-topic", 0, 0, null, "test-data")));
		recordMap.put(tp1, Collections.singletonList(new ConsumerRecord("test-topic", 1, 0, null, "test-data")));
		recordMap.put(tp2, Collections.singletonList(new ConsumerRecord("test-topic", 2, 0, null, "test-data")));
		recordMap.put(tp3, Collections.singletonList(new ConsumerRecord("test-topic", 3, 0, null, "test-data")));
		ConsumerRecords records = new ConsumerRecords<>(recordMap, Map.of());
		willAnswer(invocation -> {
			Thread.sleep(10);
			if (listener.latch.getCount() <= 0) {
				latch.countDown();
			}
			return records;
		}).given(consumer).poll(any());
		willAnswer(invocation -> {
			((ConsumerRebalanceListener) invocation.getArgument(1))
					.onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		given(consumer.position(tp0)).willReturn(30L); // current offset 30, target 20 (see hard-coded in onMessage)
		given(consumer.position(tp1)).willReturn(10L); // current 10, target 21
		given(consumer.position(tp2)).willReturn(22L); // current 22, target 22
		given(consumer.position(tp3)).willReturn(22L); // current 22, target 23
		given(consumer.beginningOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 0L)));
		given(consumer.endOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 100L)));
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
				.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("test-topic");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener(listener);
		containerProperties.setMissingTopicsFatal(false);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(consumerFactory,
				containerProperties);
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(consumer).seek(tp0, 20L);
		verify(consumer).seek(tp1, 10L);
		verify(consumer).seek(tp2, 22L);
		verify(consumer).seek(tp3, 22L);
		container.stop();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	@DisplayName("Seek from activeListener")
	void testAsyncRelativeSeeks() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		TestMessageListener1 listener = new TestMessageListener1();
		CountDownLatch latch = new CountDownLatch(2);
		TopicPartition tp0 = new TopicPartition("foo", 0);
		TopicPartition tp1 = new TopicPartition("foo", 1);
		TopicPartition tp2 = new TopicPartition("foo", 2);
		TopicPartition tp3 = new TopicPartition("foo", 3);
		List<TopicPartition> assignments = List.of(tp0, tp1, tp2, tp3);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
		recordMap.put(tp0, Collections.singletonList(new ConsumerRecord("foo", 0, 0, null, "bar")));
		recordMap.put(tp1, Collections.singletonList(new ConsumerRecord("foo", 1, 0, null, "bar")));
		recordMap.put(tp2, Collections.singletonList(new ConsumerRecord("foo", 2, 0, null, "bar")));
		recordMap.put(tp3, Collections.singletonList(new ConsumerRecord("foo", 3, 0, null, "bar")));
		ConsumerRecords records = new ConsumerRecords<>(recordMap, Map.of());
		willAnswer(invocation -> {
			Thread.sleep(10);
			if (listener.latch.getCount() <= 0) {
				latch.countDown();
			}
			return records;
		}).given(consumer).poll(any());
		willAnswer(invocation -> {
			((ConsumerRebalanceListener) invocation.getArgument(1))
				.onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		given(consumer.position(any())).willReturn(100L);
		given(consumer.beginningOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 0L)));
		given(consumer.endOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 200L)));
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
			.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener(listener);
		containerProperties.setMissingTopicsFatal(false);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(consumerFactory,
				containerProperties);
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(consumer).seek(tp0, 70L);
		verify(consumer).seek(tp1, 130L);
		verify(consumer).seekToEnd(Collections.singletonList(tp2));
		verify(consumer).seek(tp2, 70L); // position - 30 (seekToEnd ignored by mock)
		verify(consumer).seekToBeginning(Collections.singletonList(tp3));
		verify(consumer).seek(tp3, 130L); // position + 30 (seekToBeginning ignored by mock)
		container.stop();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	@DisplayName("Seek timestamp TL callback when idle")
	void testSyncTimestampSeeks() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		TestMessageListener2 listener = new TestMessageListener2();
		ConsumerRecords empty = new ConsumerRecords<>(Collections.emptyMap(), Map.of());
		willAnswer(invocation -> {
			Thread.sleep(10);
			return empty;
		}).given(consumer).poll(any());
		TopicPartition tp0 = new TopicPartition("foo", 0);
		TopicPartition tp1 = new TopicPartition("foo", 1);
		TopicPartition tp2 = new TopicPartition("foo", 2);
		TopicPartition tp3 = new TopicPartition("foo", 3);
		List<TopicPartition> assignments = List.of(tp0, tp1, tp2, tp3);
		willAnswer(invocation -> {
			((ConsumerRebalanceListener) invocation.getArgument(1))
				.onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		given(consumer.position(any())).willReturn(100L);
		given(consumer.beginningOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 0L)));
		given(consumer.endOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 200L)));
		given(consumer.offsetsForTimes(Collections.singletonMap(tp0, 42L)))
				.willReturn(Collections.singletonMap(tp0, new OffsetAndTimestamp(63L, 42L)));
		given(consumer.offsetsForTimes(assignments.stream().collect(Collectors.toMap(tp -> tp, tp -> 43L))))
				.willReturn(assignments.stream()
						.collect(Collectors.toMap(tp -> tp, tp -> new OffsetAndTimestamp(82L, 43L))));
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
			.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener(listener);
		containerProperties.setIdleEventInterval(10L);
		containerProperties.setMissingTopicsFatal(false);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(consumerFactory,
				containerProperties);
		container.start();
		assertThat(listener.latch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(consumer).seek(tp0, 63L);
		verify(consumer).seek(tp0, 82L);
		verify(consumer).seek(tp1, 82L);
		verify(consumer).seek(tp2, 82L);
		verify(consumer).seek(tp3, 82L);
		container.stop();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	@DisplayName("Timestamp seek from activeListener")
	void testAsyncTimestampSeeks() throws InterruptedException {
		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		TestMessageListener2 listener = new TestMessageListener2();
		CountDownLatch latch = new CountDownLatch(2);
		TopicPartition tp0 = new TopicPartition("foo", 0);
		TopicPartition tp1 = new TopicPartition("foo", 1);
		TopicPartition tp2 = new TopicPartition("foo", 2);
		TopicPartition tp3 = new TopicPartition("foo", 3);
		List<TopicPartition> assignments = List.of(tp0, tp1, tp2, tp3);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
		recordMap.put(tp0, Collections.singletonList(new ConsumerRecord("foo", 0, 0, null, "bar")));
		recordMap.put(tp1, Collections.singletonList(new ConsumerRecord("foo", 1, 0, null, "bar")));
		recordMap.put(tp2, Collections.singletonList(new ConsumerRecord("foo", 2, 0, null, "bar")));
		recordMap.put(tp3, Collections.singletonList(new ConsumerRecord("foo", 3, 0, null, "bar")));
		ConsumerRecords records = new ConsumerRecords<>(recordMap, Map.of());
		willAnswer(invocation -> {
			Thread.sleep(10);
			if (listener.latch.getCount() <= 0) {
				latch.countDown();
			}
			return records;
		}).given(consumer).poll(any());
		willAnswer(invocation -> {
			((ConsumerRebalanceListener) invocation.getArgument(1))
				.onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		given(consumer.position(any())).willReturn(100L);
		given(consumer.beginningOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 0L)));
		given(consumer.endOffsets(any())).willReturn(assignments.stream()
				.collect(Collectors.toMap(tp -> tp, tp -> 200L)));
		given(consumer.offsetsForTimes(Collections.singletonMap(tp0, 42L)))
				.willReturn(Collections.singletonMap(tp0, new OffsetAndTimestamp(73L, 42L)));
		Map<TopicPartition, OffsetAndTimestamp> map = new HashMap<>(assignments.stream()
				.collect(Collectors.toMap(tp -> tp,
						tp -> new OffsetAndTimestamp(tp.equals(tp0) ? 73L : 92L, 43L))));
		map.put(new TopicPartition("foo", 5), null);
		given(consumer.offsetsForTimes(any()))
				.willReturn(map);
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
			.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener(listener);
		containerProperties.setMissingTopicsFatal(false);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(consumerFactory,
				containerProperties);
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(consumer).seek(tp0, 73L);
		verify(consumer).seek(tp1, 92L);
		verify(consumer).seek(tp2, 92L);
		verify(consumer).seek(tp3, 92L);
		container.stop();
	}

	@Test
	@DisplayName("Intercept after tx start")
	void testInterceptAfterTx() throws InterruptedException {
		testIntercept(false, AssignmentCommitOption.ALWAYS, false);
	}

	@Test
	@DisplayName("Intercept before tx start")
	void testInterceptBeforeTx() throws InterruptedException {
		testIntercept(true, AssignmentCommitOption.ALWAYS, false);
	}

	@Test
	@DisplayName("Intercept after tx start no initial commit")
	void testInterceptAfterTx1() throws InterruptedException {
		testIntercept(false, null, false);
	}

	@Test
	@DisplayName("Intercept before tx start no initial commit")
	void testInterceptBeforeTx1() throws InterruptedException {
		testIntercept(true, null, false);
	}

	@Test
	@DisplayName("Intercept batch after tx start")
	void testBatchInterceptAfterTx() throws InterruptedException {
		testIntercept(false, AssignmentCommitOption.ALWAYS, true);
	}

	@Test
	@DisplayName("Intercept batch before tx start")
	void testBatchInterceptBeforeTx() throws InterruptedException {
		testIntercept(true, AssignmentCommitOption.ALWAYS, true);
	}

	@Test
	@DisplayName("Intercept batch after tx start no initial commit")
	void testBatchInterceptAfterTx1() throws InterruptedException {
		testIntercept(false, null, true);
	}

	@Test
	@DisplayName("Intercept batch before tx start no initial commit")
	void testBatchInterceptBeforeTx1() throws InterruptedException {
		testIntercept(true, null, true);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	void testIntercept(boolean beforeTx, @Nullable AssignmentCommitOption option, boolean batch)
			throws InterruptedException {

		ConsumerFactory consumerFactory = mock(ConsumerFactory.class);
		final Consumer consumer = mock(Consumer.class);
		TopicPartition tp0 = new TopicPartition("foo", 0);
		ConsumerRecord record1 = new ConsumerRecord("foo", 0, 0L, "bar", "baz");
		ConsumerRecord record2 = new ConsumerRecord("foo", 0, 1L, null, null);
		ConsumerRecords records = batch
				? new ConsumerRecords(Collections.singletonMap(tp0, List.of(record1, record2)), Map.of())
				: new ConsumerRecords(Collections.singletonMap(tp0, Collections.singletonList(record1)), Map.of());
		ConsumerRecords empty = new ConsumerRecords<>(Collections.emptyMap(), Map.of());
		AtomicInteger firstOrSecondPoll = new AtomicInteger();
		willAnswer(invocation -> {
			Thread.sleep(10);
			return firstOrSecondPoll.incrementAndGet() < 3 ? records : empty;
		}).given(consumer).poll(any());
		List<TopicPartition> assignments = List.of(tp0);
		willAnswer(invocation -> {
			((ConsumerRebalanceListener) invocation.getArgument(1))
				.onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		given(consumer.position(any())).willReturn(0L);
		given(consumerFactory.createConsumer("grp", "", "-0", KafkaTestUtils.defaultPropertyOverrides()))
			.willReturn(consumer);
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		AtomicBoolean first = new AtomicBoolean(true);
		AtomicReference<List<ConsumerRecord<String, String>>> received = new AtomicReference<>();
		if (batch) {
			containerProperties.setMessageListener((BatchMessageListener<String, String>) recs -> {
				if (first.getAndSet(false)) {
					throw new RuntimeException("test");
				}
				received.set(recs);
			});
		}
		else {
			containerProperties.setMessageListener((MessageListener) rec -> {
				if (first.getAndSet(false)) {
					throw new RuntimeException("test");
				}
			});
		}
		containerProperties.setMissingTopicsFatal(false);
		if (option != null) {
			containerProperties.setAssignmentCommitOption(option);
		}
		KafkaAwareTransactionManager tm = mock(KafkaAwareTransactionManager.class);
		ProducerFactory pf = mock(ProducerFactory.class);
		given(tm.getProducerFactory()).willReturn(pf);
		Producer producer = mock(Producer.class);
		given(pf.createProducer()).willReturn(producer);
		containerProperties.setKafkaAwareTransactionManager(tm);
		List<String> order = new ArrayList<>();
		CountDownLatch latch = new CountDownLatch(option == null ? 2 : 3);
		willAnswer(inv -> {
			order.add("tx");
			TransactionSynchronizationManager.bindResource(pf,
					new KafkaResourceHolder<>(producer, Duration.ofSeconds(5L)));
			latch.countDown();
			return null;
		}).given(tm).getTransaction(any());
		willAnswer(inv -> {
			TransactionSynchronizationManager.unbindResource(pf);
			return null;
		}).given(tm).commit(any());
		willAnswer(inv -> {
			TransactionSynchronizationManager.unbindResource(pf);
			return null;
		}).given(tm).rollback(any());
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(consumerFactory,
				containerProperties);
		CountDownLatch successCalled = new CountDownLatch(1);
		CountDownLatch failureCalled = new CountDownLatch(1);
		container.setRecordInterceptor(new RecordInterceptor() {

			@Override
			public @Nullable ConsumerRecord intercept(ConsumerRecord rec, Consumer consumer) {
				order.add("interceptor");
				latch.countDown();
				return rec;
			}

			@Override
			public void success(ConsumerRecord record, Consumer consumer) {
				order.add("success");
				successCalled.countDown();
			}

			@Override
			public void failure(ConsumerRecord record, Exception exception, Consumer consumer) {
				order.add("failure");
				failureCalled.countDown();
			}

		});
		container.setBatchInterceptor(new BatchInterceptor() {

			@Override
			public @Nullable ConsumerRecords intercept(ConsumerRecords recs, Consumer consumer) {
				order.add("interceptor");
				latch.countDown();
				return new ConsumerRecords(Collections.singletonMap(tp0, Collections.singletonList(record1)), Map.of());
			}

			@Override
			public void success(ConsumerRecords records, Consumer consumer) {
				order.add("success");
				successCalled.countDown();
			}

			@Override
			public void failure(ConsumerRecords records, Exception exception, Consumer consumer) {
				order.add("failure");
				failureCalled.countDown();
			}

		});
		container.setInterceptBeforeTx(beforeTx);
		container.start();
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(failureCalled.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(successCalled.await(10, TimeUnit.SECONDS)).isTrue();
			if (beforeTx) {
				if (option == null) {
					assertThat(order).containsExactly("interceptor", "tx", "failure", "interceptor", "tx", "success");
				}
				else {
					assertThat(order).containsExactly("tx", "interceptor", "tx", "failure", "interceptor", "tx",
							"success"); // first one is on assignment
				}
			}
			else {
				if (option == null) {
					assertThat(order).containsExactly("tx", "interceptor", "failure", "tx", "interceptor", "success");
				}
				else {
					assertThat(order).containsExactly("tx", "tx", "interceptor", "failure", "tx", "interceptor",
							"success");
				}
			}
			if (batch) {
				assertThat(received.get()).hasSize(1);
			}
		}
		finally {
			container.stop();
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	void testNoCommitOnAssignmentWithEarliest() throws InterruptedException {
		Consumer consumer = mock(Consumer.class);
		ConsumerRecords records = new ConsumerRecords<>(Collections.emptyMap(), Map.of());
		CountDownLatch latch = new CountDownLatch(1);
		willAnswer(inv -> {
			latch.countDown();
			Thread.sleep(50);
			return records;
		}).given(consumer).poll(any());
		TopicPartition tp0 = new TopicPartition("foo", 0);
		List<TopicPartition> assignments = List.of(tp0);
		willAnswer(invocation -> {
			((ConsumerRebalanceListener) invocation.getArgument(1))
				.onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		ConsumerFactory cf = mock(ConsumerFactory.class);
		given(cf.createConsumer(any(), any(), any(), any())).willReturn(consumer);
		given(cf.getConfigurationProperties())
				.willReturn(Collections.singletonMap(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener((MessageListener) rec -> { });
		containerProperties.setMissingTopicsFatal(false);
		containerProperties.setAssignmentCommitOption(AssignmentCommitOption.LATEST_ONLY);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(cf,
				containerProperties);
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(consumer, never()).commitSync(any(), any());
	}

	@Test
	void testNoInitialCommitIfAlreadyCommitted() throws InterruptedException {
		testInitialCommitIBasedOnCommitted(true);
	}

	@Test
	void testInitialCommitIfNotAlreadyCommitted() throws InterruptedException {
		testInitialCommitIBasedOnCommitted(false);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void testInitialCommitIBasedOnCommitted(boolean committed) throws InterruptedException {
		Consumer consumer = mock(Consumer.class);
		ConsumerRecords records = new ConsumerRecords<>(Collections.emptyMap(), Map.of());
		CountDownLatch latch = new CountDownLatch(1);
		willAnswer(inv -> {
			latch.countDown();
			Thread.sleep(50);
			return records;
		}).given(consumer).poll(any());
		TopicPartition tp0 = new TopicPartition("foo", 0);
		List<TopicPartition> assignments = List.of(tp0);
		willAnswer(invocation -> {
			((ConsumerRebalanceListener) invocation.getArgument(1))
				.onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		if (committed) {
			given(consumer.committed(Collections.singleton(tp0)))
					.willReturn(Collections.singletonMap(tp0, new OffsetAndMetadata(0L)));
		}
		else {
			given(consumer.committed(Collections.singleton(tp0)))
					.willReturn(Collections.singletonMap(tp0, null));
		}
		ConsumerFactory cf = mock(ConsumerFactory.class);
		given(cf.createConsumer(any(), any(), any(), any())).willReturn(consumer);
		given(cf.getConfigurationProperties())
				.willReturn(Collections.singletonMap(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"));
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener((MessageListener) rec -> { });
		containerProperties.setMissingTopicsFatal(false);
		containerProperties.setAssignmentCommitOption(AssignmentCommitOption.LATEST_ONLY);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(cf,
				containerProperties);
		container.start();
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		if (committed) {
			verify(consumer, never()).commitSync(any(), any());
		}
		else {
			verify(consumer).commitSync(any(), any());
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	void removeFromPartitionPauseRequestedWhenNotAssigned() throws InterruptedException {
		Consumer consumer = mock(Consumer.class);
		CountDownLatch pollLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			pollLatch.countDown();
			Thread.sleep(50);
			return ConsumerRecords.empty();
		}).given(consumer).poll(any());
		CountDownLatch pauseLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			pauseLatch.countDown();
			return null;
		}).given(consumer).pause(any());
		TopicPartition tp0 = new TopicPartition("foo", 0);
		List<TopicPartition> assignments = List.of(tp0);
		AtomicReference<ConsumerRebalanceListener> rebal = new AtomicReference<>();
		willAnswer(invocation -> {
			rebal.set(invocation.getArgument(1));
			rebal.get().onPartitionsAssigned(assignments);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		ConsumerFactory cf = mock(ConsumerFactory.class);
		given(cf.createConsumer(any(), any(), any(), any())).willReturn(consumer);
		given(cf.getConfigurationProperties())
				.willReturn(Collections.singletonMap(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"));
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener((MessageListener) rec -> { });
		containerProperties.setMissingTopicsFatal(false);
		containerProperties.setAssignmentCommitOption(AssignmentCommitOption.LATEST_ONLY);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(cf,
				containerProperties);
		container.start();
		assertThat(pollLatch.await(10, TimeUnit.SECONDS)).isTrue();
		container.pausePartition(tp0);
		KafkaMessageListenerContainer child = (KafkaMessageListenerContainer) KafkaTestUtils
				.getPropertyValue(container, "containers", List.class).get(0);
		assertThat(child.isPartitionPauseRequested(tp0)).isTrue();
		assertThat(pauseLatch.await(10, TimeUnit.SECONDS)).isTrue();
		rebal.get().onPartitionsRevoked(assignments);
		assertThat(child.isPartitionPauseRequested(tp0)).isTrue();
		// immediate pause when re-assigned
		rebal.get().onPartitionsAssigned(assignments);
		verify(consumer, times(2)).pause(any());
		rebal.get().onPartitionsRevoked(assignments);
		// resume partition while unassigned
		container.resumePartition(tp0);
		assertThat(child.isPartitionPauseRequested(tp0)).isFalse();
		rebal.get().onPartitionsAssigned(assignments);
		verify(consumer, times(2)).pause(any()); // no immediate pause this time
		container.stop();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	void pruneRevokedPartitionsFromRemainingRecordsWhenSeekAfterErrorFalseLegacyAssignor() throws InterruptedException {
		TopicPartition tp0 = new TopicPartition("foo", 0);
		TopicPartition tp1 = new TopicPartition("foo", 1);
		TopicPartition tp2 = new TopicPartition("foo", 2);
		TopicPartition tp3 = new TopicPartition("foo", 3);
		List<TopicPartition> allAssignments = List.of(tp0, tp1, tp2, tp3);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> allRecordMap = new HashMap<>();
		allRecordMap.put(tp0, Collections.singletonList(new ConsumerRecord("foo", 0, 0, null, "bar")));
		allRecordMap.put(tp1, Collections.singletonList(new ConsumerRecord("foo", 1, 0, null, "bar")));
		allRecordMap.put(tp2, Collections.singletonList(new ConsumerRecord("foo", 2, 0, null, "bar")));
		allRecordMap.put(tp3, Collections.singletonList(new ConsumerRecord("foo", 3, 0, null, "bar")));
		ConsumerRecords allRecords = new ConsumerRecords<>(allRecordMap, Map.of());
		List<TopicPartition> afterRevokeAssignments = List.of(tp1, tp3);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> afterRevokeRecordMap = new HashMap<>();
		afterRevokeRecordMap.put(tp1, Collections.singletonList(new ConsumerRecord("foo", 1, 0, null, "bar")));
		afterRevokeRecordMap.put(tp3, Collections.singletonList(new ConsumerRecord("foo", 3, 0, null, "bar")));
		ConsumerRecords afterRevokeRecords = new ConsumerRecords<>(afterRevokeRecordMap, Map.of());
		AtomicInteger pollPhase = new AtomicInteger();

		Consumer consumer = mock(Consumer.class);
		AtomicReference<ConsumerRebalanceListener> rebal = new AtomicReference<>();
		CountDownLatch subscribeLatch = new CountDownLatch(1);
		willAnswer(invocation -> {
			rebal.set(invocation.getArgument(1));
			subscribeLatch.countDown();
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		CountDownLatch pauseLatch = new CountDownLatch(1);
		AtomicBoolean paused = new AtomicBoolean();
		willAnswer(inv -> {
			paused.set(true);
			pauseLatch.countDown();
			return null;
		}).given(consumer).pause(any());
		CountDownLatch resumeLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			paused.set(false);
			resumeLatch.countDown();
			return null;
		}).given(consumer).resume(any());
		ConsumerFactory cf = mock(ConsumerFactory.class);
		given(cf.createConsumer(any(), any(), any(), any())).willReturn(consumer);
		given(cf.getConfigurationProperties())
				.willReturn(Collections.singletonMap(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setMessageListener((MessageListener) rec -> {
			throw new RuntimeException("test");
		});
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(cf,
				containerProperties);
		container.setCommonErrorHandler(new CommonErrorHandler() {

			@Override
			public boolean seeksAfterHandling() {
				return false; // pause and use remainingRecords
			}

			@Override
			public boolean handleOne(Exception thrownException, ConsumerRecord<?, ?> record, Consumer<?, ?> consumer,
					MessageListenerContainer container) {

				return false; // not handled
			}

		});
		CountDownLatch pollLatch = new CountDownLatch(2);
		CountDownLatch rebalLatch = new CountDownLatch(1);
		CountDownLatch continueLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			Thread.sleep(50);
			pollLatch.countDown();
			switch (pollPhase.getAndIncrement()) {
				case 0 -> {
					rebal.get().onPartitionsAssigned(allAssignments);
					return allRecords;
				}
				case 1 -> {
					rebal.get().onPartitionsRevoked(allAssignments);
					rebal.get().onPartitionsAssigned(afterRevokeAssignments);
					rebalLatch.countDown();
					continueLatch.await(10, TimeUnit.SECONDS);
					if (paused.get()) {
						return ConsumerRecords.empty();
					}
					return afterRevokeRecords;
				}
				default -> {
					if (paused.get()) {
						return ConsumerRecords.empty();
					}
					return afterRevokeRecords;
				}
			}
		}).given(consumer).poll(any());
		container.start();
		assertThat(subscribeLatch.await(10, TimeUnit.SECONDS)).isTrue();
		KafkaMessageListenerContainer child = (KafkaMessageListenerContainer) KafkaTestUtils
				.getPropertyValue(container, "containers", List.class).get(0);
		assertThat(pollLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(pauseLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(rebalLatch.await(10, TimeUnit.SECONDS)).isTrue();
		ConsumerRecords remaining = KafkaTestUtils.getPropertyValue(child, "listenerConsumer.remainingRecords",
				ConsumerRecords.class);
		assertThat(remaining).isNull();
		continueLatch.countDown();
		assertThat(resumeLatch.await(10, TimeUnit.SECONDS)).isTrue();
		// no pause when re-assigned because all revoked and remainingRecords == null
		verify(consumer).pause(any());
		verify(consumer).resume(any());
		container.stop();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	void pruneRevokedPartitionsFromRemainingRecordsWhenSeekAfterErrorFalseCoopAssignor() throws InterruptedException {
		TopicPartition tp0 = new TopicPartition("foo", 0);
		TopicPartition tp1 = new TopicPartition("foo", 1);
		TopicPartition tp2 = new TopicPartition("foo", 2);
		TopicPartition tp3 = new TopicPartition("foo", 3);
		List<TopicPartition> allAssignments = List.of(tp0, tp1, tp2, tp3);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> allRecordMap = new LinkedHashMap<>();
		ConsumerRecord record0 = new ConsumerRecord("foo", 0, 0, null, "bar");
		ConsumerRecord record1 = new ConsumerRecord("foo", 1, 0, null, "bar");
		allRecordMap.put(tp0, Collections.singletonList(record0));
		allRecordMap.put(tp1, Collections.singletonList(record1));
		allRecordMap.put(tp2, Collections.singletonList(new ConsumerRecord("foo", 2, 0, null, "bar")));
		allRecordMap.put(tp3, Collections.singletonList(new ConsumerRecord("foo", 3, 0, null, "bar")));
		ConsumerRecords allRecords = new ConsumerRecords<>(allRecordMap, Map.of());
		List<TopicPartition> revokedAssignments = List.of(tp0, tp2);
		AtomicInteger pollPhase = new AtomicInteger();

		Consumer consumer = mock(Consumer.class);
		AtomicReference<ConsumerRebalanceListener> rebal = new AtomicReference<>();
		CountDownLatch subscribeLatch = new CountDownLatch(1);
		willAnswer(invocation -> {
			rebal.set(invocation.getArgument(1));
			subscribeLatch.countDown();
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		CountDownLatch pauseLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			pauseLatch.countDown();
			return null;
		}).given(consumer).pause(any());
		ConsumerFactory cf = mock(ConsumerFactory.class);
		given(cf.createConsumer(any(), any(), any(), any())).willReturn(consumer);
		given(cf.getConfigurationProperties())
				.willReturn(Collections.singletonMap(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		List<ConsumerRecord> recordsDelivered = new ArrayList<>();
		CountDownLatch consumeLatch = new CountDownLatch(3);
		containerProperties.setMessageListener((MessageListener) rec -> {
			recordsDelivered.add((ConsumerRecord) rec);
			consumeLatch.countDown();
			throw new RuntimeException("test");
		});
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(cf,
				containerProperties);
		container.setCommonErrorHandler(new CommonErrorHandler() {

			@Override
			public boolean seeksAfterHandling() {
				return false; // pause and use remainingRecords
			}

			@Override
			public boolean handleOne(Exception thrownException, ConsumerRecord<?, ?> record, Consumer<?, ?> consumer,
					MessageListenerContainer container) {

				return false; // not handled
			}

		});
		CountDownLatch pollLatch = new CountDownLatch(2);
		CountDownLatch rebalLatch = new CountDownLatch(1);
		CountDownLatch continueLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			Thread.sleep(50);
			pollLatch.countDown();
			switch (pollPhase.getAndIncrement()) {
				case 0 -> {
					rebal.get().onPartitionsAssigned(allAssignments);
					return allRecords;
				}
				case 1 -> {
					rebal.get().onPartitionsRevoked(revokedAssignments);
					rebal.get().onPartitionsAssigned(Collections.emptyList());
					rebalLatch.countDown();
					continueLatch.await(10, TimeUnit.SECONDS);
					return ConsumerRecords.empty();
				}
				default -> {
					return ConsumerRecords.empty();
				}
			}
		}).given(consumer).poll(any());
		container.start();
		assertThat(subscribeLatch.await(10, TimeUnit.SECONDS)).isTrue();
		KafkaMessageListenerContainer child = (KafkaMessageListenerContainer) KafkaTestUtils
				.getPropertyValue(container, "containers", List.class).get(0);
		assertThat(pollLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(pauseLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(rebalLatch.await(10, TimeUnit.SECONDS)).isTrue();
		ConsumerRecords remaining = KafkaTestUtils.getPropertyValue(child, "listenerConsumer.remainingRecords",
				ConsumerRecords.class);
		assertThat(remaining.count()).isEqualTo(2);
		assertThat(remaining.partitions()).contains(tp1, tp3);
		continueLatch.countDown();
		verify(consumer, atLeastOnce()).pause(any());
		verify(consumer, never()).resume(any());
		assertThat(consumeLatch.await(10, TimeUnit.SECONDS)).isTrue();
		container.stop();
		assertThat(recordsDelivered).hasSizeGreaterThanOrEqualTo(3);
		// partitions 0, 2 revoked during second poll.
		assertThat(recordsDelivered.get(0)).isEqualTo(record0);
		assertThat(recordsDelivered.get(1)).isEqualTo(record1);
		assertThat(recordsDelivered.get(2)).isEqualTo(record1);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	void pruneRevokedPartitionsFromPendingOutOfOrderCommitsLegacyAssignor() throws InterruptedException {
		TopicPartition tp0 = new TopicPartition("foo", 0);
		TopicPartition tp1 = new TopicPartition("foo", 1);
		List<TopicPartition> allAssignments = List.of(tp0, tp1);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> allRecordMap = new HashMap<>();
		allRecordMap.put(tp0,
				List.of(new ConsumerRecord("foo", 0, 0, null, "bar"), new ConsumerRecord("foo", 0, 1, null, "bar")));
		allRecordMap.put(tp1,
				List.of(new ConsumerRecord("foo", 1, 0, null, "bar"), new ConsumerRecord("foo", 1, 1, null, "bar")));
		ConsumerRecords allRecords = new ConsumerRecords<>(allRecordMap, Map.of());
		List<TopicPartition> afterRevokeAssignments = List.of(tp1);
		AtomicInteger pollPhase = new AtomicInteger();

		Consumer consumer = mock(Consumer.class);
		AtomicReference<ConsumerRebalanceListener> rebal = new AtomicReference<>();
		CountDownLatch subscribeLatch = new CountDownLatch(1);
		willAnswer(invocation -> {
			rebal.set(invocation.getArgument(1));
			subscribeLatch.countDown();
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		CountDownLatch pauseLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			pauseLatch.countDown();
			return null;
		}).given(consumer).pause(any());
		ConsumerFactory cf = mock(ConsumerFactory.class);
		given(cf.createConsumer(any(), any(), any(), any())).willReturn(consumer);
		given(cf.getConfigurationProperties())
				.willReturn(Collections.singletonMap(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setAckMode(AckMode.MANUAL);
		containerProperties.setMessageListener(ackOffset1());
		containerProperties.setAsyncAcks(true);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(cf,
				containerProperties);
		CountDownLatch pollLatch = new CountDownLatch(2);
		CountDownLatch rebalLatch = new CountDownLatch(1);
		CountDownLatch continueLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			Thread.sleep(50);
			pollLatch.countDown();
			switch (pollPhase.getAndIncrement()) {
				case 0 -> {
					rebal.get().onPartitionsAssigned(allAssignments);
					return allRecords;
				}
				case 1 -> {
					rebal.get().onPartitionsRevoked(allAssignments);
					rebal.get().onPartitionsAssigned(afterRevokeAssignments);
					rebalLatch.countDown();
					continueLatch.await(10, TimeUnit.SECONDS);
					return ConsumerRecords.empty();
				}
				default -> {
					return ConsumerRecords.empty();
				}
			}
		}).given(consumer).poll(any());
		container.start();
		assertThat(subscribeLatch.await(10, TimeUnit.SECONDS)).isTrue();
		KafkaMessageListenerContainer child = (KafkaMessageListenerContainer) KafkaTestUtils
				.getPropertyValue(container, "containers", List.class).get(0);
		assertThat(pollLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(pauseLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(rebalLatch.await(10, TimeUnit.SECONDS)).isTrue();
		Map offsets = KafkaTestUtils.getPropertyValue(child, "listenerConsumer.offsetsInThisBatch", Map.class);
		assertThat(offsets).hasSize(0);
		assertThat(KafkaTestUtils.getPropertyValue(child, "listenerConsumer.consumerPaused", Boolean.class)).isFalse();
		continueLatch.countDown();
		// no pause when re-assigned because all revoked
		verify(consumer).pause(any());
		verify(consumer, never()).resume(any());
		container.stop();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Test
	void pruneRevokedPartitionsFromPendingOutOfOrderCommitsCoopAssignor() throws InterruptedException {
		TopicPartition tp0 = new TopicPartition("foo", 0);
		TopicPartition tp1 = new TopicPartition("foo", 1);
		List<TopicPartition> allAssignments = List.of(tp0, tp1);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> allRecordMap = new HashMap<>();
		allRecordMap.put(tp0,
				List.of(new ConsumerRecord("foo", 0, 0, null, "bar"), new ConsumerRecord("foo", 0, 1, null, "bar")));
		allRecordMap.put(tp1,
				List.of(new ConsumerRecord("foo", 1, 0, null, "bar"), new ConsumerRecord("foo", 1, 1, null, "bar")));
		ConsumerRecords allRecords = new ConsumerRecords<>(allRecordMap, Map.of());
		AtomicInteger pollPhase = new AtomicInteger();

		Consumer consumer = mock(Consumer.class);
		AtomicReference<ConsumerRebalanceListener> rebal = new AtomicReference<>();
		CountDownLatch subscribeLatch = new CountDownLatch(1);
		willAnswer(invocation -> {
			rebal.set(invocation.getArgument(1));
			subscribeLatch.countDown();
			return null;
		}).given(consumer).subscribe(any(Collection.class), any());
		CountDownLatch pauseLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			pauseLatch.countDown();
			return null;
		}).given(consumer).pause(any());
		ConsumerFactory cf = mock(ConsumerFactory.class);
		given(cf.createConsumer(any(), any(), any(), any())).willReturn(consumer);
		given(cf.getConfigurationProperties())
				.willReturn(Collections.singletonMap(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
		ContainerProperties containerProperties = new ContainerProperties("foo");
		containerProperties.setGroupId("grp");
		containerProperties.setAckMode(AckMode.MANUAL);
		containerProperties.setMessageListener(ackOffset1());
		containerProperties.setAsyncAcks(true);
		ConcurrentMessageListenerContainer container = new ConcurrentMessageListenerContainer(cf,
				containerProperties);
		CountDownLatch pollLatch = new CountDownLatch(2);
		CountDownLatch rebalLatch = new CountDownLatch(1);
		CountDownLatch continueLatch = new CountDownLatch(1);
		willAnswer(inv -> {
			Thread.sleep(50);
			pollLatch.countDown();
			switch (pollPhase.getAndIncrement()) {
				case 0 -> {
					rebal.get().onPartitionsAssigned(allAssignments);
					return allRecords;
				}
				case 1 -> {
					rebal.get().onPartitionsRevoked(List.of(tp0));
					rebal.get().onPartitionsAssigned(List.of(new TopicPartition("foo", 2)));
					rebalLatch.countDown();
					continueLatch.await(10, TimeUnit.SECONDS);
					return ConsumerRecords.empty();
				}
				default -> {
					return ConsumerRecords.empty();
				}
			}
		}).given(consumer).poll(any());
		container.start();
		assertThat(subscribeLatch.await(10, TimeUnit.SECONDS)).isTrue();
		KafkaMessageListenerContainer child = (KafkaMessageListenerContainer) KafkaTestUtils
				.getPropertyValue(container, "containers", List.class).get(0);
		assertThat(pollLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(pauseLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(rebalLatch.await(10, TimeUnit.SECONDS)).isTrue();
		Map offsets = KafkaTestUtils.getPropertyValue(child, "listenerConsumer.offsetsInThisBatch", Map.class);
		assertThat(offsets).hasSize(1);
		assertThat(offsets.get(tp1)).isNotNull();
		assertThat(KafkaTestUtils.getPropertyValue(child, "listenerConsumer.consumerPaused", Boolean.class)).isTrue();
		continueLatch.countDown();
		verify(consumer, times(2)).pause(any());
		verify(consumer, never()).resume(any());
		container.stop();
	}

	@SuppressWarnings("rawtypes")
	private AcknowledgingMessageListener ackOffset1() {
		return new AcknowledgingMessageListener() {

			@Override
			public void onMessage(ConsumerRecord rec, @Nullable Acknowledgment ack) {
				if (rec.offset() == 1 && ack != null) {
					ack.acknowledge();
				}
			}

			@Override
			public void onMessage(Object data) {
			}

		};
	}

	public static class TestMessageListener1 implements MessageListener<String, String>, ConsumerSeekAware {

		private static final ThreadLocal<ConsumerSeekCallback> callbacks = new ThreadLocal<>();

		CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void onMessage(ConsumerRecord<String, String> data) {
			ConsumerSeekCallback callback = callbacks.get();
			if (latch.getCount() > 0) {
				callback.seekRelative("foo", 0, -30, true);
				callback.seekRelative("foo", 1, 30, true);
				callback.seekRelative("foo", 2, -30, false);
				callback.seekRelative("foo", 3, 30, false);
			}
			this.latch.countDown();
		}

		@Override
		public void registerSeekCallback(ConsumerSeekCallback callback) {
			callbacks.set(callback);
		}

		@Override
		public void onIdleContainer(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
			if (latch.getCount() > 0) {
				callback.seekRelative("foo", 0, -40, true);
				callback.seekRelative("foo", 1, 40, true);
				callback.seekRelative("foo", 2, -40, false);
				callback.seekRelative("foo", 3, 40, false);
			}
			this.latch.countDown();
		}

	}

	public static class TestMessageListener2 implements MessageListener<String, String>, ConsumerSeekAware {

		private static final ThreadLocal<ConsumerSeekCallback> callbacks = new ThreadLocal<>();

		CountDownLatch latch = new CountDownLatch(1);

		private Collection<TopicPartition> partitions;

		@Override
		public void onMessage(ConsumerRecord<String, String> data) {
			ConsumerSeekCallback callback = callbacks.get();
			if (latch.getCount() > 0) {
				callback.seekToTimestamp("foo", 0, 42L);
				callback.seekToTimestamp(this.partitions.stream()
						.filter(tp -> !tp.equals(new TopicPartition("foo", 0)))
						.collect(Collectors.toList()), 43L);
			}
			this.latch.countDown();
		}

		@Override
		public void registerSeekCallback(ConsumerSeekCallback callback) {
			callbacks.set(callback);
		}

		@Override
		public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
			this.partitions = assignments.keySet();
		}

		@Override
		public void onIdleContainer(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
			if (latch.getCount() > 0) {
				callback.seekToTimestamp("foo", 0, 42L);
				callback.seekToTimestamp(assignments.keySet(), 43L);
			}
			this.latch.countDown();
		}

	}

	public static class TestMessageListener3 implements MessageListener<String, String>, ConsumerSeekAware {

		private static final ThreadLocal<ConsumerSeekCallback> callbacks = new ThreadLocal<>();

		CountDownLatch latch = new CountDownLatch(2);

		@Override
		public void onMessage(ConsumerRecord<String, String> data) {

		}

		@Override
		public void registerSeekCallback(ConsumerSeekCallback callback) {
			callbacks.set(callback);
		}

		@Override
		public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
			if (latch.getCount() > 0) {
				int absoluteTarget1 = 20;
				int absoluteTarget2 = 21;
				int absoluteTarget3 = 22;
				int absoluteTarget4 = 23;
				callback.seek("test-topic", 0, current -> current > absoluteTarget1 ? absoluteTarget1 : current);
				callback.seek("test-topic", 1, current -> current > absoluteTarget2 ? absoluteTarget2 : current);
				callback.seek("test-topic", 2, current -> current > absoluteTarget3 ? absoluteTarget3 : current);
				callback.seek("test-topic", 3, current -> current > absoluteTarget4 ? absoluteTarget4 : current);
			}
			this.latch.countDown();
		}

		@Override
		public void onIdleContainer(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
			if (latch.getCount() > 0) {
				int absoluteTarget = 31;
				callback.seek("test-topic", 0, current -> current > absoluteTarget ? absoluteTarget : current);
				callback.seek("test-topic", 1, current -> current > absoluteTarget ? absoluteTarget : current);
				callback.seek("test-topic", 2, current -> current > absoluteTarget ? absoluteTarget : current);
				callback.seek("test-topic", 3, current -> current > absoluteTarget ? absoluteTarget : current);
			}
			this.latch.countDown();
		}

	}

	public static class TestMessageListener4 implements MessageListener<String, String>, ConsumerSeekAware {

		private static final ThreadLocal<ConsumerSeekCallback> callbacks = new ThreadLocal<>();

		CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void onMessage(ConsumerRecord<String, String> data) {
			ConsumerSeekCallback callback = callbacks.get();
			if (latch.getCount() > 0) {

				int absoluteTarget1 = 20;
				int absoluteTarget2 = 21;
				int absoluteTarget3 = 22;
				int absoluteTarget4 = 23;

				callback.seek("test-topic", 0, current -> current > absoluteTarget1 ? absoluteTarget1 : current);
				callback.seek("test-topic", 1, current -> current > absoluteTarget2 ? absoluteTarget2 : current);
				callback.seek("test-topic", 2, current -> current > absoluteTarget3 ? absoluteTarget3 : current);
				callback.seek("test-topic", 3, current -> current > absoluteTarget4 ? absoluteTarget4 : current);
			}
			this.latch.countDown();
		}

		@Override
		public void registerSeekCallback(ConsumerSeekCallback callback) {
			callbacks.set(callback);
		}

	}

}
