package peer.net;

import java.net.Socket;

public class Connection implements Runnable {
    private final Socket sock;
    private final String myId;

    public Connection(Socket sock, String myId) {
        this.sock = sock;
        this.myId = myId;
    }

    public void start() {
        new Thread(this, "Conn-" + myId + "->" + sock.getRemoteSocketAddress()).start();
    }

    @Override public void run() {
        try {
            System.out.println("[" + myId + "] connected: " + sock.getRemoteSocketAddress());
            // TODO: handshake, bitfield, message loop
            while (!sock.isClosed()) Thread.sleep(1000);
        } catch (Exception ignored) {}
    }
}
