package com.netflix.dyno.contrib;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.netflix.dyno.connectionpool.OperationMonitor;
import com.netflix.dyno.connectionpool.impl.utils.EstimatedHistogram;
import com.netflix.dyno.contrib.EstimatedHistogramBasedCounter.EstimatedHistogramMean;
import com.netflix.dyno.contrib.EstimatedHistogramBasedCounter.EstimatedHistogramPercentile;
import com.netflix.servo.DefaultMonitorRegistry;
import com.netflix.servo.monitor.BasicCounter;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.tag.BasicTag;

public class DynoOPMonitor implements OperationMonitor {

	private final ConcurrentHashMap<String, DynoOpCounter> counterMap = new ConcurrentHashMap<String, DynoOpCounter>();
	private final ConcurrentHashMap<String, DynoTimingCounters> timerMap = new ConcurrentHashMap<String, DynoTimingCounters>();

	private final String appName;

	public DynoOPMonitor(String applicationName) {
		appName = applicationName;
	}
	
	@Override
	public void recordLatency(String opName, long duration, TimeUnit unit) {
		getOrCreateTimers(opName).recordLatency(duration, unit);
	}

	@Override
	public void recordSuccess(String opName) {
		getOrCreateCounter(opName, false).incrementSuccess();
	}

	@Override
	public void recordFailure(String opName, String reason) {
		getOrCreateCounter(opName, false).incrementFailure();
	}

    @Override
    public void recordSuccess(String opName, boolean compressionEnabled) {
        getOrCreateCounter(opName, compressionEnabled).incrementSuccess();
    }

    @Override
    public void recordFailure(String opName, boolean compressionEnabled, String reason) {
        getOrCreateCounter(opName, true).incrementFailure();
    }

    private class DynoOpCounter {
		
		private final Counter success;
        private final Counter successCompressionEnabled;
		private final Counter failure;
        private final Counter failureCompressionEnabled;
		
		private DynoOpCounter(String appName, String opName) {
			success = getNewCounter("Dyno__" + appName + "__" + opName + "__SUCCESS", opName, "false");
            successCompressionEnabled = getNewCounter("Dyno__" + appName + "__" + opName + "__SUCCESS", opName, "true");

            failure = getNewCounter("Dyno__" + appName + "__" + opName + "__ERROR", opName, "false");
            failureCompressionEnabled = getNewCounter("Dyno__" + appName + "__" + opName + "__ERROR", opName, "true");
		}
		
		private void incrementSuccess() {
			success.increment();
		}
		
		private void incrementFailure() {
			failure.increment();
		}
		
		private BasicCounter getNewCounter(String metricName, String opName, String compressionEnabled) {
			MonitorConfig config = MonitorConfig.builder(metricName)
					.withTag(new BasicTag("dyno_op", opName))
                    .withTag(new BasicTag("compression_enabled", compressionEnabled))
                    .build();
			return new BasicCounter(config);
		}
	}
	
	private DynoOpCounter getOrCreateCounter(String opName, boolean compressionEnabled) {

		String counterName = opName + "_" + compressionEnabled;
		DynoOpCounter counter = counterMap.get(counterName);

		if (counter != null) {
			return counter;
		}

		counter = new DynoOpCounter(appName, counterName);

		DynoOpCounter prevCounter = counterMap.putIfAbsent(counterName, counter);
		if (prevCounter != null) {
			return prevCounter;
		}

		DefaultMonitorRegistry.getInstance().register(counter.success);
		DefaultMonitorRegistry.getInstance().register(counter.failure);
		DefaultMonitorRegistry.getInstance().register(counter.successCompressionEnabled);
		DefaultMonitorRegistry.getInstance().register(counter.failureCompressionEnabled);

		return counter; 
	}

	private class DynoTimingCounters {
		
		private final EstimatedHistogramMean latMean; 
		private final EstimatedHistogramPercentile lat99;
		private final EstimatedHistogramPercentile lat995;
		private final EstimatedHistogramPercentile lat999;
		
		private final EstimatedHistogram estHistogram; 
		
		private DynoTimingCounters(String appName, String opName) {

			estHistogram = new EstimatedHistogram();
			latMean = new EstimatedHistogramMean("Dyno__" + appName + "__" + opName + "__latMean", opName, estHistogram);
			lat99 = new EstimatedHistogramPercentile("Dyno__" + appName + "__" + opName + "__lat990", opName, estHistogram, 0.99);
			lat995 = new EstimatedHistogramPercentile("Dyno__" + appName + "__" + opName + "__lat995", opName, estHistogram, 0.995);
			lat999 = new EstimatedHistogramPercentile("Dyno__" + appName + "__" + opName + "__lat999", opName, estHistogram, 0.999);
		}
		
		public void recordLatency(long duration, TimeUnit unit) {
			long durationMicros = TimeUnit.MICROSECONDS.convert(duration, unit);
			estHistogram.add(durationMicros);
		}
	}

	private DynoTimingCounters getOrCreateTimers(String opName) {
		
		DynoTimingCounters timer = timerMap.get(opName);
		if (timer != null) {
			return timer;
		}
		timer = new DynoTimingCounters(appName, opName);
		DynoTimingCounters prevTimer = timerMap.putIfAbsent(opName, timer);
		if (prevTimer != null) {
			return prevTimer;
		}
		DefaultMonitorRegistry.getInstance().register(timer.latMean);
		DefaultMonitorRegistry.getInstance().register(timer.lat99);
		DefaultMonitorRegistry.getInstance().register(timer.lat995);
		DefaultMonitorRegistry.getInstance().register(timer.lat999);
		return timer; 
	}
}
