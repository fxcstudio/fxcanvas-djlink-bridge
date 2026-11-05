package studio.fxc.djlink;

import studio.fxc.djlink.osc.OscMessage;
import studio.fxc.djlink.osc.Outbox;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Records sent messages for assertions. */
final class RecordingOutbox implements Outbox {
    static final class Sent {
        final InetSocketAddress to;
        final OscMessage message;

        Sent(InetSocketAddress to, OscMessage message) {
            this.to = to;
            this.message = message;
        }

        @Override
        public String toString() {
            return to.getPort() + " " + message;
        }
    }

    final List<Sent> sent = new ArrayList<>();

    @Override
    public void send(InetSocketAddress to, OscMessage message) {
        sent.add(new Sent(to, message));
    }

    List<OscMessage> messages() {
        return sent.stream().map(s -> s.message).collect(Collectors.toList());
    }

    List<String> lines() {
        return sent.stream().map(Sent::toString).collect(Collectors.toList());
    }

    void clear() {
        sent.clear();
    }
}
