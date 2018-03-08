package io.micrometer.influx;

import rx.Observable;
import rx.functions.Action1;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

public abstract class InfluxTransport {

    public static InfluxTransport create(InfluxConfig influxConfig) throws URISyntaxException, MalformedURLException, UnsupportedEncodingException {

        URI uri = new URI(influxConfig.uri());

        switch (uri.getScheme()) {
            case "http":
            case "https":
                return new InfluxHttpTransport(influxConfig);
            default:
                throw new IllegalArgumentException("Unsupported scheme " + uri.getScheme());
        }

    }

    public abstract void sendData(Observable<String> lineProtocolObservable);

}
