package peer.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/** Thin server: listens on a port and hands each accepted Socket to a callback. */
public class Server implements Runnable {
    private final int port;
    private final Consumer<Socket> onAccept;
    private volatile boolean running = true;
    private ServerSocket server;

    public Server(int port, Consumer<Socket> onAccept) {
        this.port = port;
        this.onAccept = onAccept;
    }

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket(port)) {
            server = ss;
            while (running) {
                Socket s = ss.accept();          // blocks
                onAccept.accept(s);              // hand it to your Connection
            }
        } catch (IOException e) {
            if (running) e.printStackTrace();
        }
    }

    /** Stop listening and unblock accept(). */
    public void stop() {
        running = false;
        try {
            if (server != null && !server.isClosed()) server.close();
        } catch (IOException ignored) {}
    }
}