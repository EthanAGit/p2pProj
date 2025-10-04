package peer.net;

import java.io.IOException;
import java.net.Socket;

/** Thin client: just opens a TCP connection to host:port and returns the Socket. */
public class Client {
    public Socket connect(String host, int port) throws IOException {
        return new Socket(host, port);
    }
}
