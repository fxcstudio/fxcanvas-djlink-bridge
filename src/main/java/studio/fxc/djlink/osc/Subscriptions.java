package studio.fxc.djlink.osc;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stream subscriptions per topic ({@code /devices}, {@code /beats}, ...).
 *
 * A subscription is either on the client's default response route (follows
 * later {@code /respond} changes) or on an explicit port. Subscribing twice
 * with the same key replaces the earlier one, so the app's repeated startup
 * {@code stream} requests never duplicate messages.
 *
 * Not thread safe: used only from the bridge's event thread.
 */
public final class Subscriptions {
    private final Map<String, LinkedHashMap<String, Object>> byTopic = new HashMap<>();

    /** Subscribe {@code route}'s client to {@code topic}; {@code port} null means its response route. */
    public void subscribe(String topic, ResponseRoute route, Integer port) {
        String key = key(route, port);
        Object destination = (port == null) ? route : new InetSocketAddress(route.host(), port);
        byTopic.computeIfAbsent(topic, t -> new LinkedHashMap<>()).put(key, destination);
    }

    public void unsubscribe(String topic, ResponseRoute route, Integer port) {
        LinkedHashMap<String, Object> subs = byTopic.get(topic);
        if (subs != null) {
            subs.remove(key(route, port));
        }
    }

    public boolean hasSubscribers(String topic) {
        LinkedHashMap<String, Object> subs = byTopic.get(topic);
        return subs != null && !subs.isEmpty();
    }

    /** Current destination addresses for {@code topic}, in subscription order. */
    public List<InetSocketAddress> destinations(String topic) {
        LinkedHashMap<String, Object> subs = byTopic.get(topic);
        List<InetSocketAddress> result = new ArrayList<>();
        if (subs == null) return result;
        for (Object destination : subs.values()) {
            if (destination instanceof ResponseRoute) {
                result.add(((ResponseRoute) destination).address());
            } else {
                result.add((InetSocketAddress) destination);
            }
        }
        return result;
    }

    public void publish(Outbox outbox, String topic, OscMessage message) {
        for (InetSocketAddress to : destinations(topic)) {
            outbox.send(to, message);
        }
    }

    private static String key(ResponseRoute route, Integer port) {
        String client = route.host().getHostAddress() + ":" + route.sourcePort();
        return port == null ? client + "/default" : client + "/" + port;
    }
}
