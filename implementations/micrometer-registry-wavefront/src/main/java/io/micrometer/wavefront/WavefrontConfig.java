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
package io.micrometer.wavefront;

import io.micrometer.core.instrument.step.StepRegistryConfig;
import io.micrometer.core.lang.Nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

public interface WavefrontConfig extends StepRegistryConfig {
    WavefrontConfig DEFAULT = k -> null;

    @Override
    default Duration step() {
        String v = get(prefix() + ".step");
        return v == null ? Duration.ofSeconds(10) : Duration.parse(v);
    }

    @Override
    default String prefix() {
        return "wavefront";
    }

    default String host() {
        String v = get(prefix() + ".host");
        return v == null ? "localhost" : v;
    }

    default String port() {
        String v = get(prefix() + ".port");
        return v == null ? "2878" : v;
    }

    default String source() {
        String v = get(prefix() + ".source");
        if (v != null)
            return v;

        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException uhe) {
            return "unknown";
        }
    }

    /**
     * A prefix to add to every metric name to separate Micrometer-sourced metrics
     * from others in the Wavefront UI.
     */
    @Nullable
    default String globalPrefix() {
        return get(prefix() + ".globalPrefix");
    }
}
