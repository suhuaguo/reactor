/*
 * Copyright (c) 2011-2015 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.processor;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.error.CancelException;
import reactor.core.error.Exceptions;
import reactor.core.error.SpecificationExceptions;
import reactor.core.processor.rb.MutableSignal;
import reactor.core.processor.rb.RequestTask;
import reactor.core.processor.rb.RingBufferSubscriberUtils;
import reactor.core.processor.rb.disruptor.*;
import reactor.core.support.BackpressureUtils;
import reactor.core.support.NamedDaemonThreadFactory;
import reactor.core.support.Publishable;
import reactor.core.support.SignalType;
import reactor.fn.Consumer;
import reactor.fn.Supplier;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * An implementation of a RingBuffer backed message-passing WorkProcessor.
 * <p>
 * The processor is very similar to {@link reactor.core.processor.RingBufferProcessor} but only partially respects the
 * Reactive Streams contract.
 * <p>
 * The purpose of this processor is to distribute the signals to only one of the subscribed subscribers and to share
 * the
 * demand amongst all subscribers. The scenario is akin to Executor or Round-Robin distribution. However there is
 * no guarantee the distribution will be respecting a round-robin distribution all the time.
 * <p>
 * The core use for this component is to scale up easily without suffering the overhead of an Executor and without
 * using
 * dedicated queues by subscriber, which is less used memory, less GC, more win.
 *
 * @param <E> Type of dispatched signal
 * @author Stephane Maldini
 */
public final class RingBufferWorkProcessor<E> extends ExecutorPoweredProcessor<E, E> {

	/**
	 * Create a new RingBufferWorkProcessor using {@link #SMALL_BUFFER_SIZE} backlog size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created.
	 *
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create() {
		return create(RingBufferWorkProcessor.class.getSimpleName(), SMALL_BUFFER_SIZE, new
		  LiteBlockingWaitStrategy(), true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using {@link #SMALL_BUFFER_SIZE} backlog size, blockingWait Strategy
	 * and the passed auto-cancel setting.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created.
	 *
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(boolean autoCancel) {
		return create(RingBufferWorkProcessor.class.getSimpleName(), SMALL_BUFFER_SIZE, new
		  LiteBlockingWaitStrategy(), autoCancel);
	}

	/**
	 * Create a new RingBufferWorkProcessor using {@link #SMALL_BUFFER_SIZE} backlog size, blockingWait Strategy
	 * and auto-cancel.
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param <E>     Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(ExecutorService service) {
		return create(service, SMALL_BUFFER_SIZE, new LiteBlockingWaitStrategy(), true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using {@link #SMALL_BUFFER_SIZE} backlog size, blockingWait Strategy
	 * and the passed auto-cancel setting.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param service    A provided ExecutorService to manage threading infrastructure
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(ExecutorService service, boolean autoCancel) {
		return create(service, SMALL_BUFFER_SIZE, new LiteBlockingWaitStrategy(), autoCancel);
	}


	/**
	 * Create a new RingBufferProcessor using the default buffer size 32, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created and will use the passed name to qualify
	 * the created threads.
	 *
	 * @param name       Use a new Cached ExecutorService and assign this name to the created threads
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(String name) {
		return create(name, SMALL_BUFFER_SIZE);
	}

	/**
	 * Create a new RingBufferProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created and will use the passed name to qualify
	 * the created threads.
	 *
	 * @param name       Use a new Cached ExecutorService and assign this name to the created threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(String name, int bufferSize) {
		return create(name, bufferSize, new LiteBlockingWaitStrategy(), true);
	}

	/**
	 * Create a new RingBufferProcessor using the passed buffer size, blockingWait Strategy
	 * and the passed auto-cancel setting.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created and will use the passed name to qualify
	 * the created threads.
	 *
	 * @param name       Use a new Cached ExecutorService and assign this name to the created threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(String name, int bufferSize, boolean autoCancel) {
		return create(name, bufferSize, new LiteBlockingWaitStrategy(), autoCancel);
	}


	/**
	 * Create a new RingBufferProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param service    A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(ExecutorService service, int bufferSize) {
		return create(service, bufferSize, new LiteBlockingWaitStrategy(), true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param service    A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(ExecutorService service, int bufferSize, boolean autoCancel) {
		return create(service, bufferSize, new LiteBlockingWaitStrategy(), autoCancel);
	}


	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created and will use the passed name to qualify
	 * the created threads.
	 *
	 * @param name       Use a new Cached ExecutorService and assign this name to the created threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy   A RingBuffer WaitStrategy to use instead of the default LiteBlockingWaitStrategy.
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(String name, int bufferSize, WaitStrategy
	  strategy) {
		return create(name, bufferSize, strategy, true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel settings.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created and will use the passed name to qualify
	 * the created threads.
	 *
	 * @param name       Use a new Cached ExecutorService and assign this name to the created threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy   A RingBuffer WaitStrategy to use instead of the default LiteBlockingWaitStrategy.
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(String name, int bufferSize, WaitStrategy
	  strategy, boolean autoCancel) {
		return new RingBufferWorkProcessor<E>(name, null, bufferSize, strategy, false, autoCancel);
	}

	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size and blockingWait Strategy settings
	 * but will auto-cancel.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param executor   A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy   A RingBuffer WaitStrategy to use instead of the default LiteBlockingWaitStrategy.
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(ExecutorService executor, int bufferSize, WaitStrategy
	  strategy) {
		return create(executor, bufferSize, strategy, true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size, wait strategy
	 * and auto-cancel settings.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param executor   A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy   A RingBuffer WaitStrategy to use instead of the default LiteBlockingWaitStrategy.
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> create(ExecutorService executor, int bufferSize, WaitStrategy
	  strategy, boolean autoCancel) {
		return new RingBufferWorkProcessor<E>(null, executor, bufferSize, strategy, false, autoCancel);
	}

	/**
	 * Create a new RingBufferWorkProcessor using {@link #SMALL_BUFFER_SIZE} backlog size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created.
	 *
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share() {
		return share(RingBufferWorkProcessor.class.getSimpleName(), SMALL_BUFFER_SIZE, new
		  LiteBlockingWaitStrategy(), true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using {@link #SMALL_BUFFER_SIZE} backlog size, blockingWait Strategy
	 * and the passed auto-cancel setting.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created.
	 *
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(boolean autoCancel) {
		return share(RingBufferWorkProcessor.class.getSimpleName(), SMALL_BUFFER_SIZE, new
		  LiteBlockingWaitStrategy(), autoCancel);
	}

	/**
	 * Create a new RingBufferWorkProcessor using {@link #SMALL_BUFFER_SIZE} backlog size, blockingWait Strategy
	 * and auto-cancel.
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param <E>     Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(ExecutorService service) {
		return share(service, SMALL_BUFFER_SIZE, new LiteBlockingWaitStrategy(), true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using {@link #SMALL_BUFFER_SIZE} backlog size, blockingWait Strategy
	 * and the passed auto-cancel setting.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param service    A provided ExecutorService to manage threading infrastructure
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(ExecutorService service, boolean autoCancel) {
		return share(service, SMALL_BUFFER_SIZE, new LiteBlockingWaitStrategy(), autoCancel);
	}


	/**
	 * Create a new RingBufferProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created and will use the passed name to qualify
	 * the created threads.
	 *
	 * @param name       Use a new Cached ExecutorService and assign this name to the created threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(String name, int bufferSize) {
		return share(name, bufferSize, new LiteBlockingWaitStrategy(), true);
	}

	/**
	 * Create a new RingBufferProcessor using the passed buffer size, blockingWait Strategy
	 * and the passed auto-cancel setting.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created and will use the passed name to qualify
	 * the created threads.
	 *
	 * @param name       Use a new Cached ExecutorService and assign this name to the created threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(String name, int bufferSize, boolean autoCancel) {
		return share(name, bufferSize, new LiteBlockingWaitStrategy(), autoCancel);
	}


	/**
	 * Create a new RingBufferProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param service    A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(ExecutorService service, int bufferSize) {
		return share(service, bufferSize, new LiteBlockingWaitStrategy(), true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param service    A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(ExecutorService service, int bufferSize, boolean autoCancel) {
		return share(service, bufferSize, new LiteBlockingWaitStrategy(), autoCancel);
	}


	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created and will use the passed name to qualify
	 * the created threads.
	 *
	 * @param name       Use a new Cached ExecutorService and assign this name to the created threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy   A RingBuffer WaitStrategy to use instead of the default LiteBlockingWaitStrategy.
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(String name, int bufferSize, WaitStrategy
	  strategy) {
		return share(name, bufferSize, strategy, true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size, blockingWait Strategy
	 * and auto-cancel settings.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * A new Cached ThreadExecutorPool will be implicitely created and will use the passed name to qualify
	 * the created threads.
	 *
	 * @param name       Use a new Cached ExecutorService and assign this name to the created threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy   A RingBuffer WaitStrategy to use instead of the default LiteBlockingWaitStrategy.
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(String name, int bufferSize, WaitStrategy
	  strategy, boolean autoCancel) {
		return new RingBufferWorkProcessor<E>(name, null, bufferSize, strategy, true, autoCancel);
	}

	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size and blockingWait Strategy settings
	 * but will auto-cancel.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param executor   A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy   A RingBuffer WaitStrategy to use instead of the default LiteBlockingWaitStrategy.
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(ExecutorService executor, int bufferSize, WaitStrategy
	  strategy) {
		return share(executor, bufferSize, strategy, true);
	}

	/**
	 * Create a new RingBufferWorkProcessor using the passed buffer size, wait strategy
	 * and auto-cancel settings.
	 * <p>
	 * A Shared Processor authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data.
	 * <p>
	 * The passed {@link java.util.concurrent.ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 *
	 * @param executor   A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy   A RingBuffer WaitStrategy to use instead of the default LiteBlockingWaitStrategy.
	 * @param autoCancel Should this propagate cancellation when unregistered by all subscribers ?
	 * @param <E>        Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> RingBufferWorkProcessor<E> share(ExecutorService executor, int bufferSize, WaitStrategy
	  strategy, boolean autoCancel) {
		return new RingBufferWorkProcessor<E>(null, executor, bufferSize, strategy, true, autoCancel);
	}


	/**
	 * Instance
	 */


	private final Sequence        workSequence       = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
	private final Queue<Sequence> cancelledSequences = new ConcurrentLinkedQueue<>();

	private final RingBuffer<MutableSignal<E>> ringBuffer;

	private final WaitStrategy readWait = new LiteBlockingWaitStrategy();

	private RingBufferWorkProcessor(String name,
	                                ExecutorService executor,
	                                int bufferSize,
	                                WaitStrategy waitStrategy,
	                                boolean share,
	                                boolean autoCancel) {
		super(name, executor, autoCancel);

		Supplier<MutableSignal<E>> factory = new Supplier<MutableSignal<E>>() {
			@Override
			public MutableSignal<E> get() {
				return new MutableSignal<>();
			}
		};
		Consumer<Void> spinObserver = new Consumer<Void>() {
			@Override
			public void accept(Void aVoid) {
				if (!alive() && SUBSCRIBER_COUNT.get(RingBufferWorkProcessor.this) == 0) throw CancelException.get();
			}
		};

		if (share) {
			this.ringBuffer = RingBuffer.createMultiProducer(
			  factory,
			  bufferSize,
			  waitStrategy,
			  spinObserver
			);
		} else {
			this.ringBuffer = RingBuffer.createSingleProducer(
			  factory,
			  bufferSize,
			  waitStrategy,
			  spinObserver
			);
		}
		ringBuffer.addGatingSequences(workSequence);

	}

	@Override
	public void subscribe(final Subscriber<? super E> subscriber) {
		if (null == subscriber) {
			throw SpecificationExceptions.spec_2_13_exception();
		}

		if(!alive() && ringBuffer.pending() == 0L){
			subscriber.onSubscribe(SignalType.NOOP_SUBSCRIPTION);
			subscriber.onComplete();
			return;
		}

		final WorkSignalProcessor<E> signalProcessor = new WorkSignalProcessor<E>(
		  subscriber,
		  this
		);
		try {

			signalProcessor.sequence.set(workSequence.get());

			incrementSubscribers();

			//bind eventProcessor sequence to observe the ringBuffer
			ringBuffer.addGatingSequences(signalProcessor.sequence);

			//prepare the subscriber subscription to this processor
			signalProcessor.setSubscription(new RingBufferSubscription(subscriber, signalProcessor));


			//start the subscriber thread
			executor.execute(signalProcessor);

		} catch (Throwable t) {
			ringBuffer.removeGatingSequence(signalProcessor.sequence);
			Exceptions.<E>publisher(t).subscribe(subscriber);
		}
	}

	@Override
	public void onNext(E o) {
		super.onNext(o);
		RingBufferSubscriberUtils.onNext(o, ringBuffer);
	}

	@Override
	public void onError(Throwable t) {
		super.onError(t);
		RingBufferSubscriberUtils.onError(t, ringBuffer);
		for(long n = ringBuffer.getCursor(); n < workSequence.get(); n++){
			RingBufferSubscriberUtils.onError(t, ringBuffer);
		}
		readWait.signalAllWhenBlocking();
	}

	@Override
	public void onComplete() {
		super.onComplete();
		RingBufferSubscriberUtils.onComplete(ringBuffer);
		for(long n = ringBuffer.getCursor(); n <= workSequence.get(); n++){
			RingBufferSubscriberUtils.onComplete(ringBuffer);
		}
		readWait.signalAllWhenBlocking();
	}

	public Publisher<Void> writeWith(final Publisher<? extends E> source) {
		return RingBufferSubscriberUtils.writeWith(source, ringBuffer);
	}

	@Override
	protected void requestTask(Subscription s) {
		new NamedDaemonThreadFactory("ringbufferwork-request-task", null, null, false).newThread(new RequestTask(
		  s,
		  new Consumer<Void>() {
			  @Override
			  public void accept(Void aVoid) {
				  if (!alive()) throw CancelException.INSTANCE;
			  }
		  },
		  null,
		  workSequence,
		  readWait,
		  this,
		  getCapacity() / 2
		)).start();
	}

	@Override
	protected void cancel(Subscription subscription) {
		//ignore since request task auto cancel
	}


	@Override
	public String toString() {
		return "RingBufferWorkProcessor{" +
		  ", ringBuffer=" + ringBuffer +
		  ", executor=" + executor +
		  ", workSequence=" + workSequence +
		  ", cancelledSequence=" + cancelledSequences +
		  '}';
	}

	@Override
	protected int decrementSubscribers() {
		int res = super.decrementSubscribers();
		readWait.signalAllWhenBlocking();
		return res;
	}

	@Override
	public long getCapacity() {
		return ringBuffer.getBufferSize();
	}

	@Override
	public boolean isWork() {
		return true;
	}

	RingBuffer<MutableSignal<E>> ringBuffer() {
		return ringBuffer;
	}

	private final class RingBufferSubscription implements Subscription, Publishable<E> {
		private final Subscriber<? super E> subscriber;
		private final WorkSignalProcessor   eventProcessor;

		public RingBufferSubscription(Subscriber<? super E> subscriber, WorkSignalProcessor eventProcessor) {
			this.subscriber = subscriber;
			this.eventProcessor = eventProcessor;
		}

		@Override
		public Publisher<E> upstream() {
			return RingBufferWorkProcessor.this;
		}

		@Override
		public void request(long n) {
			if(BackpressureUtils.checkRequest(n, subscriber)) {
				if (!eventProcessor.isRunning()) {
					return;
				}

				BackpressureUtils.getAndAdd(eventProcessor.pendingRequest, n);
			}
		}

		@Override
		public void cancel() {
			eventProcessor.halt();
		}
	}

	/**
	 * Disruptor WorkProcessor port that deals with pending demand.
	 * <p>
	 * Convenience class for handling the batching semantics of consuming entries from a {@link reactor.core.processor
	 * .rb.disruptor
	 * .RingBuffer}
	 * <p>
	 *
	 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an
	 *            event.
	 */
	private final static class WorkSignalProcessor<T> implements Runnable {

		private final AtomicBoolean running = new AtomicBoolean(false);

		private final Sequence sequence       = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
		private final Sequence pendingRequest = new Sequence(0);

		private final SequenceBarrier            barrier;
		private final RingBufferWorkProcessor<T> processor;
		private final Subscriber<? super T>      subscriber;

		private Subscription subscription;

		/**
		 * Construct a ringbuffer consumer that will automatically track the
		 * progress by updating
		 * its
		 * sequence
		 */
		public WorkSignalProcessor(Subscriber<? super T> subscriber,
		                           RingBufferWorkProcessor<T> processor) {
			this.processor = processor;
			this.subscriber = subscriber;

			this.barrier = processor.ringBuffer.newBarrier();
		}

		public Subscription getSubscription() {
			return subscription;
		}

		public void setSubscription(Subscription subscription) {
			this.subscription = subscription;
		}

		public Sequence getSequence() {
			return sequence;
		}

		public void halt() {
			running.set(false);
			barrier.alert();
		}

		public boolean isRunning() {
			return running.get();
		}

		/**
		 * It is ok to have another thread rerun this method after a halt().
		 */
		@Override
		public void run() {
			if (!running.compareAndSet(false, true)) {
				subscriber.onError(new IllegalStateException("Thread is already running"));
				processor.decrementSubscribers();
				return;
			}

			try {
				//while(processor.alive() && processor.upstreamSubscription == null);
				try {
					subscriber.onSubscribe(subscription);
				} catch (Throwable t) {
					Exceptions.throwIfFatal(t);
					subscriber.onError(t);
					return;
				}


				boolean processedSequence = true;
				long cachedAvailableSequence = Long.MIN_VALUE;
				long nextSequence = sequence.get();
				MutableSignal<T> event = null;

				if (!RingBufferSubscriberUtils.waitRequestOrTerminalEvent(
				  pendingRequest, processor.ringBuffer, barrier, subscriber, running, nextSequence
				)) {
					processor.ringBuffer.removeGatingSequence(sequence);
					return;
				}

				final boolean unbounded = pendingRequest.get() == Long.MAX_VALUE;

				if (replay(unbounded)) {
					running.set(false);
					return;
				}

				barrier.clearAlert();

				while (true) {
					try {
						// if previous sequence was processed - fetch the next sequence and set
						// that we have successfully processed the previous sequence
						// typically, this will be true
						// this prevents the sequence getting too far forward if an error
						// is thrown from the WorkHandler
						if (processedSequence) {
							processedSequence = false;
							do {
								nextSequence = processor.workSequence.get() + 1L;
								readNextEvent(processor.ringBuffer.get(nextSequence), unbounded);
								if(!unbounded) {
									pendingRequest.incrementAndGet();
								}
								sequence.set(nextSequence - 1L);
							}
							while (!processor.workSequence.compareAndSet(nextSequence - 1L, nextSequence));
						}

						if (cachedAvailableSequence >= nextSequence) {
							event = processor.ringBuffer.get(nextSequence);

							readNextEvent(processor.ringBuffer.get(nextSequence), unbounded);;

							//It's an unbounded subscriber or there is enough capacity to process the signal
							RingBufferSubscriberUtils.routeOnce(event, subscriber);

							processedSequence = true;

						} else {
							processor.readWait.signalAllWhenBlocking();
							cachedAvailableSequence = barrier.waitFor(nextSequence);
						}

					} catch (CancelException ce) {
						if (event != null &&
						  event.type == SignalType.NEXT &&
						  event.value != null) {
							sequence.set(nextSequence - 1L);
						} else {
							sequence.set(nextSequence);
						}
						processor.cancelledSequences.add(sequence);
						//barrier.alert();
						break;
					} catch (AlertException ex) {
						if (!running.get()) {
							sequence.set(nextSequence - 1L);
							processor.cancelledSequences.add(sequence);
							break;
						}
						barrier.clearAlert();
						//continue event-loop

					} catch (final Throwable ex) {
						subscriber.onError(ex);
						sequence.set(nextSequence);
						processedSequence = true;
					}
				}
			} finally {
				processor.decrementSubscribers();
				running.set(false);
				processor.readWait.signalAllWhenBlocking();
			}
		}

		private boolean replay(final boolean unbounded) {
			Sequence replayedSequence;
			MutableSignal<T> signal;
			while ((replayedSequence = processor.cancelledSequences.poll()) != null) {
				signal = processor.ringBuffer.get(replayedSequence.get() + 1L);
				try {
					if (signal.value == null) {
						barrier.waitFor(replayedSequence.get() + 1L);
					}
					readNextEvent(signal, unbounded);
					RingBufferSubscriberUtils.routeOnce(signal, subscriber);
					processor.ringBuffer.removeGatingSequence(replayedSequence);
				} catch (InterruptedException | AlertException | CancelException ce) {
					processor.ringBuffer.removeGatingSequence(sequence);
					processor.cancelledSequences.add(replayedSequence);
					return true;
				}
			}
			return false;
		}

		private void readNextEvent(MutableSignal<T> event, final boolean unbounded) throws AlertException {
			//if event is Next Signal we need to handle backpressure (pendingRequests)
			if (event.type == SignalType.NEXT) {
				if (event.value == null) {
					return;
				}

				//if bounded and out of capacity
				if (!unbounded || pendingRequest.addAndGet(-1L) < 0) {
					//re-add the retained capacity
					pendingRequest.incrementAndGet();

					//if current sequence does not yet match the published one
					//if (nextSequence < cachedAvailableSequence) {
					//pause until request
					while (pendingRequest.addAndGet(-1L) < 0) {
						pendingRequest.incrementAndGet();
						if (!isRunning()) {
							subscriber.onComplete();
							throw CancelException.INSTANCE;
						}
						//Todo Use WaitStrategy?
						LockSupport.parkNanos(1l);
					}
				}
			} else if (event.type != null) {
				//Complete or Error are terminal events, we shutdown the processor and process the signal
				running.set(false);
				RingBufferSubscriberUtils.route(event, subscriber);
				throw CancelException.INSTANCE;
			}
		}
	}

	@Override
	public long getAvailableCapacity() {
		return ringBuffer.remainingCapacity();
	}

}
