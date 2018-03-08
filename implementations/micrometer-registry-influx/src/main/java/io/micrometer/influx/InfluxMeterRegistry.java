/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.influx;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.util.DoubleFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * @author Jon Schneider
 */
public class InfluxMeterRegistry extends StepMeterRegistry {
    private final InfluxConfig config;
    private final InfluxTransport influxTransport;
    private final Logger logger = LoggerFactory.getLogger(InfluxMeterRegistry.class);

    public InfluxMeterRegistry(InfluxConfig config, InfluxTransport influxTransport, Clock clock, ThreadFactory threadFactory) {
        super(config, clock);
        this.influxTransport = influxTransport;
        this.config().namingConvention(new InfluxNamingConvention(NamingConvention.snakeCase));
        this.config = config;
        start(threadFactory);
    }

    public InfluxMeterRegistry(InfluxConfig config, Clock clock, ThreadFactory threadFactory) {
        this(config, InfluxTransport.create(config), clock, threadFactory);
    }

    public InfluxMeterRegistry(InfluxConfig config, Clock clock) {
        this(config, clock, Executors.defaultThreadFactory());
    }

    @Override
    protected void publish() {

        influxTransport.sendData(Observable.from(getMeters()).map(m -> {
            if (m instanceof Timer) {
                return writeTimer((Timer) m);
            }
            if (m instanceof DistributionSummary) {
                return writeSummary((DistributionSummary) m);
            }
            if (m instanceof FunctionTimer) {
                return writeTimer((FunctionTimer) m);
            }
            if (m instanceof TimeGauge) {
                return writeGauge(m.getId(), ((TimeGauge) m).value(getBaseTimeUnit()));
            }
            if (m instanceof Gauge) {
                return writeGauge(m.getId(), ((Gauge) m).value());
            }
            if (m instanceof FunctionCounter) {
                return writeCounter(m.getId(), ((FunctionCounter) m).count());
            }
            if (m instanceof Counter) {
                return writeCounter(m.getId(), ((Counter) m).count());
            }
            if (m instanceof LongTaskTimer) {
                return writeLongTaskTimer((LongTaskTimer) m);
            }
            return writeMeter(m);
        }));

    }

    class Field {
        final String key;
        final double value;

        Field(String key, double value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return key + "=" + DoubleFormat.decimalOrNan(value);
        }
    }

    private String writeMeter(Meter m) {
        Stream.Builder<Field> fields = Stream.builder();

        for (Measurement measurement : m.measure()) {
            String fieldKey = measurement.getStatistic().toString()
                .replaceAll("(.)(\\p{Upper})", "$1_$2").toLowerCase();
            fields.add(new Field(fieldKey, measurement.getValue()));
        }

        return influxLineProtocol(m.getId(), "unknown", fields.build(), clock.wallTime());
    }

    private String writeLongTaskTimer(LongTaskTimer timer) {
        Stream<Field> fields = Stream.of(
            new Field("active_tasks", timer.activeTasks()),
            new Field("duration", timer.duration(getBaseTimeUnit()))
        );

        return influxLineProtocol(timer.getId(), "long_task_timer", fields, clock.wallTime());
    }

    private String writeCounter(Meter.Id id, double count) {
        return influxLineProtocol(id, "counter", Stream.of(new Field("value", count)), clock.wallTime());
    }

    private String writeGauge(Meter.Id id, double value) {
        return influxLineProtocol(id, "gauge", Stream.of(new Field("value", value)), clock.wallTime());
    }

    private String writeTimer(FunctionTimer timer) {
        Stream<Field> fields = Stream.of(
            new Field("sum", timer.totalTime(getBaseTimeUnit())),
            new Field("count", timer.count()),
            new Field("mean", timer.mean(getBaseTimeUnit()))
        );

        return influxLineProtocol(timer.getId(), "histogram", fields, clock.wallTime());
    }

    private String writeTimer(Timer timer) {
        final HistogramSnapshot snapshot = timer.takeSnapshot(false);
        final Stream.Builder<Field> fields = Stream.builder();

        fields.add(new Field("sum", snapshot.total(getBaseTimeUnit())));
        fields.add(new Field("count", snapshot.count()));
        fields.add(new Field("mean", snapshot.mean(getBaseTimeUnit())));
        fields.add(new Field("upper", snapshot.max(getBaseTimeUnit())));

        return influxLineProtocol(timer.getId(), "histogram", fields.build(), clock.wallTime());
    }

    private String writeSummary(DistributionSummary summary) {
        final HistogramSnapshot snapshot = summary.takeSnapshot(false);
        final Stream.Builder<Field> fields = Stream.builder();

        fields.add(new Field("sum", snapshot.total()));
        fields.add(new Field("count", snapshot.count()));
        fields.add(new Field("mean", snapshot.mean()));
        fields.add(new Field("upper", snapshot.max()));

        return influxLineProtocol(summary.getId(), "histogram", fields.build(), clock.wallTime());
    }

    private String influxLineProtocol(Meter.Id id, String metricType, Stream<Field> fields, long time) {
        String tags = getConventionTags(id).stream()
            .map(t -> "," + t.getKey() + "=" + t.getValue())
            .collect(joining(""));

        return getConventionName(id)
            + tags + ",metric_type=" + metricType + " "
            + fields.map(Field::toString).collect(joining(","))
            + " " + time;
    }

    /**
     * For tag keys, tag values, and field keys always use a backslash character \ to escape:
     * commas, equal signs and spaces
     * <p>
     * Backslash (\) is not allowed as a last character and removed in this position
     *
     * @param tagKeyOrTagValueOrFieldKey measurement name
     * @return escaped measurement name
     */
    @VisibleForTesting
    static String escapeTagKeysTagValuesFieldKeys(String tagKeyOrTagValueOrFieldKey) {
        String escapedValue = tagKeyOrTagValueOrFieldKey.replaceAll("([,=\\s])", "\\$1");
        return escapedValue.endsWith("\\") ? escapedValue.substring(0, escapedValue.length() - 1) : escapedValue;
    }

    /**
     * For measurements always use a backslash character \ to escape:
     * commas and spaces
     * <p>
     * Backslash (\) is not allowed as a last character and removed in this position
     *
     * @param measurementName measurement name
     * @return escaped measurement name
     */
    @VisibleForTesting
    static String escapeMeasurementName(String measurementName) {
        String escapedValue = measurementName.replaceAll("([,\\s])", "\\$1");
        return escapedValue.endsWith("\\") ? escapedValue.substring(0, escapedValue.length() - 1) : escapedValue;
    }

    @VisibleForTesting
    static String escapeFieldValue(String fieldValue) {
        return fieldValue.replaceAll("\"", "\\\"").replaceAll("\\n", "\\\\");
    }

    @Override
    protected final TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }
}
