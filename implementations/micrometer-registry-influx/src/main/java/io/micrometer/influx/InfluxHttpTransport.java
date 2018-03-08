package io.micrometer.influx;

import io.micrometer.core.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static java.util.stream.Collectors.joining;
import static rx.Observable.just;
import static rx.Observable.range;

public class InfluxHttpTransport extends InfluxTransport {

    private final Logger logger = LoggerFactory.getLogger(InfluxHttpTransport.class);

    private final InfluxConfig config;

    private boolean databaseExists = false;

    public InfluxHttpTransport(InfluxConfig config) {
        this.config = config;
    }

    public void sendData(Observable<String> lineProtocolObservable) {

        createDatabaseIfNecessary();

        lineProtocolObservable.window(config.batchSize()).forEach(lines -> {

            HttpURLConnection con = null;

            try {

                String write = "/write?consistency=" + config.consistency().toString().toLowerCase() + "&precision=ms&db=" + config.db();
                if (!isBlank(config.retentionPolicy())) {
                    write += "&rp=" + config.retentionPolicy();
                }
                URL influxEndpoint = URI.create(config.uri() + write).toURL();

                con = (HttpURLConnection) influxEndpoint.openConnection();

                con.setConnectTimeout((int) config.connectTimeout().toMillis());
                con.setReadTimeout((int) config.readTimeout().toMillis());
                con.setRequestMethod("POST");
                con.setRequestProperty("Content-Type", "plain/text");
                con.setDoOutput(true);

                authenticateRequest(con);

                if (config.compressed())
                    con.setRequestProperty("Content-Encoding", "gzip");

                int count;

                try (OutputStream os = con.getOutputStream()) {
                    if (config.compressed()) {
                        try (GZIPOutputStream gz = new GZIPOutputStream(os)) {
                            count = writeInfluxLines(lines, gz);
                            gz.flush();
                        }
                    } else {
                        count = writeInfluxLines(lines, os);
                    }
                    os.flush();
                }

                int status = con.getResponseCode();

                if (status >= 200 && status < 300) {
                    logger.info("successfully sent {} metrics to influx", count);
                    databaseExists = true;
                } else if (status >= 400) {
                    try (InputStream in = con.getErrorStream()) {
                        logger.error("failed to send metrics: " + new BufferedReader(new InputStreamReader(in))
                            .lines().collect(joining("\n")));
                    }
                } else {
                    logger.error("failed to send metrics: http " + status);
                }

            } catch (Throwable e) {
                logger.error("failed to send metrics", e);
            } finally {
                quietlyCloseUrlConnection(con);
            }

        });

    }

    private int writeInfluxLines(Observable<String> lines, OutputStream os) {
        return lines.
            zipWith(
                range(0, config.batchSize()),
                AbstractMap.SimpleEntry::new
            ).
            map(entry -> {
                try {
                    if (entry.getValue() > 0) {
                        os.write('\n');
                    }
                    os.write(entry.getKey().getBytes());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return just(entry.getValue());
            }).
            count().
            toBlocking().
            singleOrDefault(0);
    }

    private void createDatabaseIfNecessary() {
        if (!config.autoCreateDb() || databaseExists)
            return;

        HttpURLConnection con = null;
        try {

            URL queryEndpoint = URI.create(config.uri() + "/query?q=" + URLEncoder.encode("CREATE DATABASE \"" + config.db() + "\"", "UTF-8")).toURL();

            con = (HttpURLConnection) queryEndpoint.openConnection();
            con.setConnectTimeout((int) config.connectTimeout().toMillis());
            con.setReadTimeout((int) config.readTimeout().toMillis());
            con.setRequestMethod("POST");
            authenticateRequest(con);

            int status = con.getResponseCode();

            if (status >= 200 && status < 300) {
                logger.debug("influx database {} is ready to receive metrics", config.db());
                databaseExists = true;
            } else if (status >= 400) {
                try (InputStream in = con.getErrorStream()) {
                    logger.error("unable to create database '{}': {}", config.db(), new BufferedReader(new InputStreamReader(in))
                        .lines().collect(joining("\n")));
                }
            }
        } catch (Throwable e) {
            logger.error("unable to create database '{}'", config.db(), e);
        } finally {
            quietlyCloseUrlConnection(con);
        }
    }

    private void authenticateRequest(HttpURLConnection con) {
        if (config.userName() != null && config.password() != null) {
            String encoded = Base64.getEncoder().encodeToString((config.userName() + ":" +
                config.password()).getBytes(StandardCharsets.UTF_8));
            con.setRequestProperty("Authorization", "Basic " + encoded);
        }
    }

    private void quietlyCloseUrlConnection(@Nullable HttpURLConnection con) {
        try {
            if (con != null) {
                con.disconnect();
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * Modified from {@link org.apache.commons.lang.StringUtils#isBlank(String)}.
     *
     * @param str The string to check
     * @return {@code true} if the String is null or blank.
     */
    private static boolean isBlank(@Nullable String str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }

}
