/*
 * Copyright 2016-present the original author or authors.
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

package org.springframework.kafka.test.assertj;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.assertj.core.api.Condition;
import org.jspecify.annotations.Nullable;

/**
 * AssertJ custom {@link Condition}s.
 *
 * @author Artem Bilan
 * @author Gary Russell
 * @author Biju Kunjummen
 */
public final class KafkaConditions {

	private KafkaConditions() {
		// private ctor
	}

	/**
	 * @param key the key.
	 * @param <K> the type.
	 * @return a Condition that matches the key in a consumer record.
	 */
	public static <K> Condition<ConsumerRecord<K, ?>> key(K key) {
		return new ConsumerRecordKeyCondition<>(key);
	}

	/**
	 * @param value the value.
	 * @param <V> the type.
	 * @return a Condition that matches the value in a consumer record.
	 */
	public static <V> Condition<ConsumerRecord<?, V>> value(V value) {
		return new ConsumerRecordValueCondition<>(value);
	}

	/**
	 * @param key the key.
	 * @param value the value.
	 * @param <K> the key type.
	 * @param <V> the value type.
	 * @return a Condition that matches the key in a consumer record.
	 * @since 2.2.12
	 */
	public static <K, V> Condition<ConsumerRecord<K, V>> keyValue(K key, V value) {
		return new ConsumerRecordKeyValueCondition<K, V>(key, value);
	}

	/**
	 * @param value the timestamp.
	 * @return a Condition that matches the timestamp value in a consumer record.
	 * @since 1.3
	 */
	public static Condition<ConsumerRecord<?, ?>> timestamp(long value) {
		return timestamp(TimestampType.CREATE_TIME, value);
	}

	/**
	 * @param type the type of timestamp
	 * @param value the timestamp.
	 * @return a Condition that matches the timestamp value in a consumer record.
	 * @since 1.3
	 */
	public static Condition<ConsumerRecord<?, ?>> timestamp(TimestampType type, long value) {
		return new ConsumerRecordTimestampCondition(type, value);
	}

	/**
	 * @param partition the partition.
	 * @return a Condition that matches the partition in a consumer record.
	 */
	public static Condition<ConsumerRecord<?, ?>> partition(int partition) {
		return new ConsumerRecordPartitionCondition(partition);
	}

	public static class ConsumerRecordKeyCondition<K> extends Condition<ConsumerRecord<K, ?>> {

		private final @Nullable K key;

		public ConsumerRecordKeyCondition(K key) {
			super("a ConsumerRecord with 'key' " + key);
			this.key = key;
		}

		@Override
		@SuppressWarnings("NullAway") // Dataflow analysis limitation
		public boolean matches(@Nullable ConsumerRecord<@Nullable K, ?> value) {
			if (value == null) {
				return false;
			}
			return value.key() == null
					? this.key == null
					: value.key().equals(this.key);
		}

	}

	public static class ConsumerRecordValueCondition<V> extends Condition<ConsumerRecord<?, V>> {

		private final @Nullable V payload;

		public ConsumerRecordValueCondition(V payload) {
			super("a ConsumerRecord with 'value' " + payload);
			this.payload = payload;
		}

		@Override
		@SuppressWarnings("NullAway") // Dataflow analysis limitation
		public boolean matches(@Nullable ConsumerRecord<?, @Nullable V> value) {
			if (value == null) {
				return false;
			}
			return value.value() == null
					? this.payload == null
					: value.value().equals(this.payload);
		}

	}

	public static class ConsumerRecordKeyValueCondition<K, V> extends Condition<ConsumerRecord<K, V>> {

		private final ConsumerRecordKeyCondition<K> keyCondition;

		private final ConsumerRecordValueCondition<V> valueCondition;

		public ConsumerRecordKeyValueCondition(K key, V value) {
			super("a ConsumerRecord with 'key' " + key + " and 'value' " + value);
			this.keyCondition = new ConsumerRecordKeyCondition<>(key);
			this.valueCondition = new ConsumerRecordValueCondition<>(value);
		}

		@Override
		@SuppressWarnings("NullAway") // Overridden method does not define nullness
		public boolean matches(@Nullable ConsumerRecord<K, @Nullable V> value) {
			return this.keyCondition.matches(value) && this.valueCondition.matches(value);
		}

	}

	public static class ConsumerRecordTimestampCondition extends Condition<ConsumerRecord<?, ?>> {

		private final TimestampType type;

		private final long ts;

		public ConsumerRecordTimestampCondition(TimestampType type, long ts) {
			super("a ConsumerRecord with timestamp of type: " + type + " and timestamp: " + ts);
			this.type = type;
			this.ts = ts;
		}

		@Override
		public boolean matches(@Nullable ConsumerRecord<?, ?> value) {
			return value != null &&
					(value.timestampType() == this.type && value.timestamp() == this.ts);
		}

	}

	public static class ConsumerRecordPartitionCondition extends Condition<ConsumerRecord<?, ?>> {

		private final int partition;

		public ConsumerRecordPartitionCondition(int partition) {
			super("a ConsumerRecord with 'partition' " + partition);
			this.partition = partition;
		}

		@Override
		public boolean matches(@Nullable ConsumerRecord<?, ?> value) {
			return value != null && value.partition() == this.partition;
		}

	}

}
